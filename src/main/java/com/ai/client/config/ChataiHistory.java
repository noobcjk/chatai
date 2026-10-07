package com.ai.client.config;

import com.ai.Chatai;
import com.ai.client.ai.ChatMessage;
import com.ai.client.ai.ChatSession;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 对话历史的本地持久化，保存在 {@code config/chatai-history.json}。
 *
 * <p>支持多个会话。旧版单会话格式（一个消息数组）在读取时会自动迁移成一个会话，
 * 不会丢历史。</p>
 */
public final class ChataiHistory {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    /** 每个会话最多保留多少条消息，避免文件无限增长。 */
    private static final int MAX_SAVED_MESSAGES = 200;

    private ChataiHistory() {
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("chatai-history.json");
    }

    /** 读取全部会话；文件不存在或损坏时返回空列表。 */
    public static List<ChatSession> load() {
        Path path = file();
        if (!Files.exists(path)) {
            return new ArrayList<>();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonElement root = JsonParser.parseReader(reader);
            List<ChatSession> sessions = new ArrayList<>();
            if (root != null && root.isJsonArray()) {
                JsonArray array = root.getAsJsonArray();
                boolean sessionFormat = !array.isEmpty()
                        && array.get(0).isJsonObject()
                        && array.get(0).getAsJsonObject().has("messages");
                if (sessionFormat) {
                    SessionEntry[] entries = GSON.fromJson(root, SessionEntry[].class);
                    if (entries != null) {
                        for (SessionEntry entry : entries) {
                            if (entry == null) {
                                continue;
                            }
                            ChatSession session = new ChatSession(entry.id, entry.title);
                            appendMessages(session, entry.messages);
                            session.summary = entry.summary == null ? "" : entry.summary;
                            session.summaryCount = Math.max(0, Math.min(entry.summaryCount, session.messages.size()));
                            sessions.add(session);
                        }
                    }
                } else {
                    // 旧版单会话格式：把它包成一个会话
                    Entry[] entries = GSON.fromJson(root, Entry[].class);
                    ChatSession session = new ChatSession();
                    appendMessages(session, toList(entries));
                    sessions.add(session);
                }
            }
            return sessions;
        } catch (Exception e) {
            Chatai.LOGGER.warn("读取对话历史失败: {}", e.toString());
            return new ArrayList<>();
        }
    }

    public static void save(List<ChatSession> sessions) {
        try {
            Path path = file();
            Files.createDirectories(path.getParent());

            List<SessionEntry> entries = new ArrayList<>();
            for (ChatSession session : sessions) {
                List<ChatMessage> messages = session.messages;
                int from = Math.max(0, messages.size() - MAX_SAVED_MESSAGES);
                // 超长会话会被截掉最旧的部分，摘要覆盖的条数要跟着往前挪，否则会错位
                int summaryCount = Math.max(0, Math.min(session.summaryCount, messages.size()) - from);
                SessionEntry entry = new SessionEntry(session.id, session.title, session.summary, summaryCount);
                for (int i = from; i < messages.size(); i++) {
                    ChatMessage message = messages.get(i);
                    entry.messages.add(new Entry(
                            message.role() == ChatMessage.Role.USER ? "user" : "assistant",
                            message.content(),
                            message.reasoning(),
                            session.hidden.contains(message)));
                }
                entries.add(entry);
            }

            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(entries, writer);
            }
        } catch (Exception e) {
            Chatai.LOGGER.warn("保存对话历史失败: {}", e.toString());
        }
    }

    private static void appendMessages(ChatSession session, List<Entry> entries) {
        if (entries == null) {
            return;
        }
        for (Entry entry : entries) {
            if (entry == null || entry.content == null || entry.content.isEmpty()) {
                continue;
            }
            ChatMessage message = "user".equalsIgnoreCase(entry.role)
                    ? ChatMessage.user(entry.content)
                    : ChatMessage.assistant(entry.content, entry.reasoning);
            session.messages.add(message);
            if (entry.hidden) {
                session.hidden.add(message);
            }
        }
    }

    private static List<Entry> toList(Entry[] entries) {
        List<Entry> list = new ArrayList<>();
        if (entries != null) {
            for (Entry entry : entries) {
                list.add(entry);
            }
        }
        return list;
    }

    /** 磁盘格式：一个会话。 */
    private static final class SessionEntry {
        String id;
        String title;
        /** 旧对话压缩后的摘要；空串表示没压缩过。 */
        String summary;
        /** 摘要覆盖了 messages 里前多少条。 */
        int summaryCount;
        List<Entry> messages = new ArrayList<>();

        SessionEntry() {
        }

        SessionEntry(String id, String title, String summary, int summaryCount) {
            this.id = id;
            this.title = title;
            this.summary = summary;
            this.summaryCount = summaryCount;
        }
    }

    /** 磁盘格式：一条消息，避免依赖 Gson 对 record 的支持。 */
    private static final class Entry {
        String role;
        String content;
        /** 思考型模型返回的思维链内容；没有则为空。 */
        String reasoning;
        /** true 表示只在界面隐藏、仍参与上下文。 */
        boolean hidden;

        Entry() {
        }

        Entry(String role, String content, String reasoning, boolean hidden) {
            this.role = role;
            this.content = content;
            this.reasoning = reasoning;
            this.hidden = hidden;
        }
    }
}
