package com.ai.client.config;

import com.ai.Chatai;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模组配置，保存在 {@code config/chatai.json}。
 *
 * <p>字段都是 public 的，方便 Gson 直接序列化，也方便设置界面读写。</p>
 */
public class ChataiConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static ChataiConfig instance;

    /** OpenAI 兼容接口的 Base URL，例如 https://api.openai.com/v1 。 */
    public String baseUrl = "https://api.openai.com/v1";
    /** API Key（Bearer Token）。 */
    public String apiKey = "";
    /** 模型名，例如 gpt-4o-mini / deepseek-chat / doubao-... 。 */
    public String model = "gpt-3.5-turbo";
    /** 系统提示词。 */
    public String systemPrompt = "你是一个友善的 Minecraft 游戏助手，回答尽量简洁。";

    /**
     * 删除单条消息时是否同步从上下文移除。
     * false（默认）：只在界面上隐藏，仍随请求发给模型；
     * true：从上下文里一并删掉。
     */
    public boolean deleteRemovesFromContext = false;

    /**
     * 可选模型列表，供对话界面底部的「模型 + 推理强度」控件使用。
     *
     * <p>每个模型通过 {@code efforts} 声明推理档位（显示名 → 端点取值，例如
     * {@code "low": "low"}）；未声明档位的模型不显示滑块，也不会发送
     * {@code reasoning_effort}。留空或删除字段时，仅提供当前 {@link #model}，
     * 档位尝试从内置知识库读取。</p>
     */
    public List<ModelPreset> models = new ArrayList<>();
    /** 当前选定的推理强度档位名（对应 {@link ModelPreset#efforts} 的 key）；空表示不发送。 */
    public String reasoningEffort = "";

    /**
     * 上下文自动压缩阈值，按发送内容的字符数粗略估算（约 4 字符 = 1 token）。
     *
     * <p>一轮回复结束后，如果没被摘要覆盖的消息总字符数超过这个值，就静默把其中较旧的
     * 部分交给模型压成摘要；界面上也能手动点「压缩」。</p>
     */
    public int compressAtChars = 6000;

    /** 已启用的技能名（对应 {@code config/chatai/skills} 里的 name），启用后正文会注入系统提示词。 */
    public List<String> enabledSkills = new ArrayList<>();

    /**
     * {@code shell} 工具允许执行的命令名白名单（管道每段取第一个词）。
     *
     * <p>留空则用内置默认表（{@link com.ai.client.tool.GameTools#DEFAULT_SHELL_ALLOWLIST}）。
     * 可在「设置 → shell 白名单」界面里改。</p>
     */
    public List<String> shellAllowlist = new ArrayList<>();

    /**
     * {@code shell} 工具除字母 / 数字 / 空格之外还允许出现的符号（白名单）。
     *
     * <p>留空则用内置默认（{@link com.ai.client.tool.GameTools#DEFAULT_SHELL_ALLOWED_CHARS}）。
     * 不在白名单里的符号一律拒绝，可在「设置 → shell 白名单」界面里改。</p>
     */
    public String shellAllowedChars = "";

    /**
     * {@code shell} 工具的确认策略。
     *
     * <p>false（默认）：每次执行前弹确认框；true：模型请求后直接执行，不再确认。</p>
     */
    public boolean shellAutoApprove = false;

    /** Termux 的包名；ZeroTermux 之类的分支如果改了包名，改这里。 */
    public String termuxPackage = "com.termux";
    /** Termux 里 bash 的绝对路径。装了 bash 才有；只装了 sh 的话改成 .../files/usr/bin/sh。 */
    public String termuxBash = "/data/data/com.termux/files/usr/bin/bash";

    /**
     * 是否把工具调用（{@code tools} 字段）发给模型。
     *
     * <p>开启后模型可以调用游戏查询 / 命令执行等工具，已启用技能的正文改为按需通过
     * {@code load_skill} 拉取，不再整段注入。接口不支持 {@code tools} 时会自动退回不带工具的模式。</p>
     */
    public boolean enableTools = true;

    public static ChataiConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("chatai.json");
    }

    private static ChataiConfig load() {
        Path path = file();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                ChataiConfig config = GSON.fromJson(reader, ChataiConfig.class);
                if (config != null) {
                    config.dropLegacyDefaults();
                    config.repairLists();
                    config.ensureEffortValid();
                    return config;
                }
            } catch (Exception e) {
                Chatai.LOGGER.warn("读取配置失败: {}", e.toString());
            }
        }
        return new ChataiConfig();
    }

    public void save() {
        try {
            Files.createDirectories(file().getParent());
            try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (Exception e) {
            Chatai.LOGGER.warn("保存配置失败: {}", e.toString());
        }
    }

    /** 配置里缺失的列表字段补成空列表，避免旧配置读出来是 null。 */
    private void repairLists() {
        if (this.models == null) {
            this.models = new ArrayList<>();
        }
        if (this.enabledSkills == null) {
            this.enabledSkills = new ArrayList<>();
        }
        if (this.shellAllowlist == null) {
            this.shellAllowlist = new ArrayList<>();
        }
    }

    /** 去掉末尾多余的斜杠，避免拼出 //chat/completions 。 */
    public String normalizedBaseUrl() {
        String base = this.baseUrl == null ? "" : this.baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    // ------------------------------------------------------------------
    // 模型 / 推理强度
    // ------------------------------------------------------------------

    /** 早期版本内置的三个示例模型，只用于在读取旧配置时把它们清掉。 */
    private static final List<ModelPreset> LEGACY_DEFAULTS = legacyDefaults();

    private static List<ModelPreset> legacyDefaults() {
        List<ModelPreset> list = new ArrayList<>();
        list.add(preset("gpt-5", "GPT-5", "minimal", "low", "medium", "high"));
        list.add(preset("o3", "o3", "low", "medium", "high"));
        list.add(preset("o4-mini", "o4-mini", "low", "medium", "high"));
        return list;
    }

    /**
     * 去掉旧配置里「原样未改」的内置示例模型。
     *
     * <p>早期版本会把它们写进 config/chatai.json，升级后模型列表会凭空多出三项；
     * 只有 id、显示名、档位都跟内置值完全一致才删，用户自己加/改过的不会误伤。</p>
     */
    private void dropLegacyDefaults() {
        if (this.models == null) {
            return;
        }
        this.models.removeIf(ChataiConfig::isLegacyDefault);
    }

    private static boolean isLegacyDefault(ModelPreset preset) {
        if (preset == null) {
            return false;
        }
        for (ModelPreset legacy : LEGACY_DEFAULTS) {
            if (legacy.id.equals(preset.id) && legacy.name.equals(preset.name)
                    && legacy.efforts.equals(preset.efforts)) {
                return true;
            }
        }
        return false;
    }

    private static ModelPreset preset(String id, String name, String... efforts) {
        ModelPreset preset = new ModelPreset();
        preset.id = id;
        preset.name = name;
        for (String effort : efforts) {
            preset.efforts.put(effort, effort);
        }
        return preset;
    }

    /** 实际可用的模型列表：配置里的 models（过滤无效项），并保证当前模型在其中。 */
    public List<ModelPreset> resolvedModels() {
        List<ModelPreset> list = new ArrayList<>();
        if (this.models != null) {
            for (ModelPreset preset : this.models) {
                if (preset != null && preset.id != null && !preset.id.isBlank()) {
                    if (preset.efforts == null) {
                        preset.efforts = new LinkedHashMap<>();
                    }
                    list.add(preset);
                }
            }
        }
        String current = this.model == null ? "" : this.model;
        boolean found = false;
        for (ModelPreset preset : list) {
            if (preset.id.equals(current)) {
                found = true;
                break;
            }
        }
        if (!found) {
            ModelPreset preset = new ModelPreset();
            preset.id = current;
            preset.name = current.isBlank() ? "（未设置模型）" : current;
            preset.efforts = new LinkedHashMap<>(ReasoningEffortCatalog.builtin(current));
            list.add(0, preset);
        }
        return list;
    }

    /** 当前模型对应的档位定义；找不到时返回 null。 */
    public ModelPreset currentPreset() {
        String current = this.model == null ? "" : this.model;
        for (ModelPreset preset : resolvedModels()) {
            if (preset.id.equals(current)) {
                return preset;
            }
        }
        return null;
    }

    /** 当前模型的档位显示名（按声明顺序）；不足两档时调用方应隐藏滑块。 */
    public List<String> currentEffortLabels() {
        ModelPreset preset = currentPreset();
        if (preset == null || preset.efforts == null) {
            return List.of();
        }
        return new ArrayList<>(preset.efforts.keySet());
    }

    /** 把档位显示名翻译成端点实际接受的取值。 */
    public String effortValue(String label) {
        ModelPreset preset = currentPreset();
        if (preset == null || preset.efforts == null || label == null) {
            return label;
        }
        return preset.efforts.getOrDefault(label, label);
    }

    /** 把当前档位收敛到合法值：模型不足两档时清空，否则缺省取中间档。 */
    public void ensureEffortValid() {
        List<String> labels = currentEffortLabels();
        if (labels.size() < 2) {
            this.reasoningEffort = "";
            return;
        }
        if (this.reasoningEffort == null || !labels.contains(this.reasoningEffort)) {
            this.reasoningEffort = labels.get(labels.size() / 2);
        }
    }

    /** 单个模型条目：id、显示名，以及档位表（显示名 → 端点取值）。 */
    public static class ModelPreset {
        public String id = "";
        public String name = "";
        public Map<String, String> efforts = new LinkedHashMap<>();
    }
}
