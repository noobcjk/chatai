package com.ai.client.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 一个独立的对话会话（多开对话用）。
 *
 * <p>{@code title} 为空表示还没起名，界面上显示默认的「新对话」，并在发出第一条
 * 用户消息后用消息内容自动命名。</p>
 */
public final class ChatSession {

    /** 磁盘持久化用的稳定标识。 */
    public String id;
    /** 会话标题；空串表示未命名。 */
    public String title;
    /** 该会话的消息列表。 */
    public final List<ChatMessage> messages = new ArrayList<>();
    /** 旧对话压缩后的摘要；空串表示还没压缩过。 */
    public String summary = "";
    /** 摘要覆盖了 {@link #messages} 里前多少条（这些不再逐条发给模型）。 */
    public int summaryCount;
    /**
     * 只从界面移除、但仍参与上下文的消息（按对象身份记录）。
     * 运行时状态，通过历史文件里的 hidden 字段恢复。
     */
    public final transient Set<ChatMessage> hidden = Collections.newSetFromMap(new IdentityHashMap<>());

    public ChatSession() {
        this(UUID.randomUUID().toString(), "");
    }

    public ChatSession(String id, String title) {
        this.id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
        this.title = title == null ? "" : title;
    }
}
