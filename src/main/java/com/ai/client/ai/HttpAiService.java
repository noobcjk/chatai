package com.ai.client.ai;

import com.ai.Chatai;
import com.ai.client.config.ChataiConfig;
import com.ai.client.skill.SkillRegistry;
import com.ai.client.tool.GameTools;
import com.ai.client.tool.ToolApprover;
import com.ai.client.tool.ToolCall;
import com.ai.client.tool.ToolResult;
import com.ai.client.tool.ToolSpec;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 通用 OpenAI 格式（/chat/completions）的对话实现，支持 SSE 流式输出与工具调用。
 *
 * <pre>
 * POST {baseUrl}/chat/completions
 * Authorization: Bearer {apiKey}
 * { "model": "...", "stream": true, "messages": [ {"role":"...","content":"..."} ] }
 * </pre>
 *
 * <p>所有网络请求都是异步的，不会阻塞客户端主线程。</p>
 *
 * <p>开启工具后，一次用户消息可能触发多轮请求：模型返回 {@code tool_calls} →
 * 本地执行 → 以 {@code role=tool} 回填 → 再请求，直到模型给出最终正文（最多
 * {@link GameTools#MAX_ROUNDS} 轮）。中间的工具消息只存在于本次请求的临时上下文里，
 * 不会写进会话历史。</p>
 */
public class HttpAiService implements AiService {

    private static final Gson GSON = new Gson();
    private static final int MAX_ECHO_LENGTH = 300;
    /** 最多带上多少条历史消息发给接口，避免上下文无限增长。 */
    private static final int MAX_CONTEXT_MESSAGES = 20;

    private final ChataiConfig config;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    /** 每次请求结束后上报本次消耗的 token 总数（用于宠物扣血）。 */
    private java.util.function.LongConsumer usageListener;
    /** 当前正在飞行的请求，取消时用来中断底层交换。 */
    private volatile CompletableFuture<?> inFlight;
    /** 接口明确拒绝 tools 参数后置位，之后不再发送。 */
    private volatile boolean toolsRejected;
    /** 用来消费阻塞的响应流，避免占用主线程。 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "chatai-stream");
        thread.setDaemon(true);
        return thread;
    });

    public HttpAiService(ChataiConfig config) {
        this.config = config;
    }

    /** 注册 token 用量回调；回调在后台线程触发。 */
    public void setUsageListener(java.util.function.LongConsumer listener) {
        this.usageListener = listener;
    }

    @Override
    public CompletableFuture<String> stream(List<ChatMessage> history, Consumer<String> onDelta,
                                            Consumer<String> onReasoning) {
        return stream(history, onDelta, onReasoning, "", 0, null, null);
    }

    /**
     * 带上下文压缩摘要与工具调用的流式请求。
     *
     * @param summary        旧对话的摘要；空表示没压缩过
     * @param skipLeading    history 里被 {@code summary} 覆盖的前若干条，不再逐条发送
     * @param approver       危险工具的确认桥；为 null 时危险工具直接拒绝
     * @param onToolActivity 每次工具调用结束后回调一行活动文本；可为 null
     */
    public CompletableFuture<String> stream(List<ChatMessage> history, Consumer<String> onDelta,
                                            Consumer<String> onReasoning, String summary, int skipLeading,
                                            ToolApprover approver, Consumer<String> onToolActivity) {
        if (isBlank(this.config.apiKey)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("未填写 API Key，请点击右上角“设置”"));
        }

        CompletableFuture<String> result = CompletableFuture.supplyAsync(
                () -> runConversation(history, onDelta, onReasoning, summary, skipLeading, approver, onToolActivity),
                this.executor);
        // 外部 cancel(true) 时一并中断底层 HTTP 交换，避免后台线程还卡在读取上
        result.whenComplete((value, error) -> {
            if (result.isCancelled()) {
                CompletableFuture<?> pending = this.inFlight;
                if (pending != null) {
                    pending.cancel(true);
                }
            }
        });
        return result;
    }

    /**
     * 测试通信：发一条最短的对话请求，验证地址 / Key / 模型是否可用。
     *
     * @return 模型回复的文本（可能为空字符串）
     */
    public CompletableFuture<String> testConnection() {
        if (isBlank(this.config.apiKey)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("API Key 为空，请先填写"));
        }

        JsonObject body = baseBody(false);
        body.addProperty("max_tokens", 16);
        JsonArray messages = new JsonArray();
        messages.add(messageNode("user", "ping"));
        body.add("messages", messages);

        HttpRequest request = buildRequest(body, false);
        if (request == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("接口地址不合法：" + this.config.baseUrl));
        }

        return this.httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    requireSuccess(response.statusCode(), response.body());
                    return extractContent(tryParse(response.body(), response.body()), false);
                });
    }

    /**
     * 一次性补全（非流式），用于会话自动命名、压缩摘要之类的静默辅助请求。
     *
     * <p>只发单条 {@code prompt}，不附带系统提示词、工具与对话历史；不计入宠物 token
     * 用量，也不回调任何监听器。</p>
     *
     * @return 模型回复的文本（可能为空字符串）
     */
    public CompletableFuture<String> complete(String prompt, int maxTokens) {
        if (isBlank(this.config.apiKey)) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("未填写 API Key，请点击右上角“设置”"));
        }

        JsonObject body = baseBody(false);
        body.addProperty("max_tokens", maxTokens);
        JsonArray messages = new JsonArray();
        messages.add(messageNode("user", prompt));
        body.add("messages", messages);

        HttpRequest request = buildRequest(body, false);
        if (request == null) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("接口地址不合法：" + this.config.baseUrl));
        }

        return this.httpClient
                .sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    requireSuccess(response.statusCode(), response.body());
                    return extractContent(tryParse(response.body(), response.body()), false);
                });
    }

    // ------------------------------------------------------------------
    // 对话主循环
    // ------------------------------------------------------------------

    /** 组上下文 → 请求 → 执行工具 → 再请求，直到模型给出最终正文。 */
    private String runConversation(List<ChatMessage> history, Consumer<String> onDelta,
                                   Consumer<String> onReasoning, String summary, int skipLeading,
                                   ToolApprover approver, Consumer<String> onToolActivity) {
        List<ToolSpec> specs = this.config.enableTools ? GameTools.specs() : List.of();
        boolean useTools = !specs.isEmpty() && !this.toolsRejected;

        JsonArray convo = new JsonArray();

        // 系统消息按固定顺序拼：系统提示词 → 当前游戏状态 → 技能 → 历史摘要
        StringBuilder system = new StringBuilder();
        appendBlock(system, this.config.systemPrompt);
        appendBlock(system, GameContext.describe());
        if (useTools) {
            appendBlock(system, "需要游戏内的实时信息（状态、背包、附近实体）或要执行操作时，"
                    + "请直接调用提供的工具，不要凭空猜测；执行操作前会由玩家确认。");
        }
        appendBlock(system, skillsBlock(useTools));
        if (!isBlank(summary)) {
            appendBlock(system, "[之前对话的摘要]\n" + summary.trim());
        }
        if (system.length() > 0) {
            convo.add(messageNode("system", system.toString()));
        }

        // 被摘要覆盖的前若干条不再逐条发送；再只带最近若干条，并保证第一条是 user
        int notBefore = Math.max(0, Math.min(skipLeading, history.size()));
        int from = Math.max(notBefore, history.size() - MAX_CONTEXT_MESSAGES);
        while (from < history.size() && history.get(from).role() != ChatMessage.Role.USER) {
            from++;
        }
        for (int i = from; i < history.size(); i++) {
            ChatMessage message = history.get(i);
            convo.add(messageNode(
                    message.role() == ChatMessage.Role.USER ? "user" : "assistant",
                    message.content()));
        }

        StringBuilder answer = new StringBuilder();
        for (int round = 0; ; round++) {
            Outcome outcome = requestRound(convo, useTools ? specs : null, onDelta, onReasoning);
            answer.append(outcome.content);
            // 本轮若因接口拒绝 tools 而退回，后面也不再发
            useTools = useTools && !this.toolsRejected;
            if (outcome.calls.isEmpty() || !useTools || round + 1 >= GameTools.MAX_ROUNDS) {
                return answer.toString();
            }

            convo.add(assistantToolCallNode(outcome));
            for (ToolCall call : outcome.calls) {
                ToolResult result = executeTool(call, approver);
                if (onToolActivity != null) {
                    onToolActivity.accept(toolTrace(call, result));
                }
                convo.add(toolResultNode(call.id(), result.content()));
            }
        }
    }

    /** 一次请求；接口不接受 tools 时去掉工具字段重试一次。 */
    private Outcome requestRound(JsonArray convo, List<ToolSpec> tools,
                                 Consumer<String> onDelta, Consumer<String> onReasoning) {
        JsonObject body = baseBody(true);
        body.add("messages", convo);
        boolean withTools = tools != null && !tools.isEmpty();
        if (withTools) {
            body.add("tools", toolsNode(tools));
        }
        try {
            return requestOnce(body, onDelta, onReasoning);
        } catch (RuntimeException e) {
            if (withTools && !this.toolsRejected && looksLikeToolsUnsupported(e)) {
                this.toolsRejected = true;
                Chatai.LOGGER.warn("接口不接受 tools 参数，本次起退回普通对话：{}", e.getMessage());
                body.remove("tools");
                return requestOnce(body, onDelta, onReasoning);
            }
            throw e;
        }
    }

    private Outcome requestOnce(JsonObject body, Consumer<String> onDelta, Consumer<String> onReasoning) {
        HttpRequest request = buildRequest(body, true);
        if (request == null) {
            throw new IllegalArgumentException("接口地址不合法：" + this.config.baseUrl);
        }
        CompletableFuture<HttpResponse<Stream<String>>> responseFuture =
                this.httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofLines());
        this.inFlight = responseFuture;
        HttpResponse<Stream<String>> response;
        try {
            response = responseFuture.join();
        } catch (CompletionException e) {
            this.inFlight = null;
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException runtime ? runtime : new RuntimeException(cause);
        }
        try {
            return consume(response, onDelta, onReasoning);
        } finally {
            this.inFlight = null;
        }
    }

    /** 危险工具先过确认桥，其它工具直接执行。 */
    private ToolResult executeTool(ToolCall call, ToolApprover approver) {
        JsonObject args = parseArgs(call.arguments());
        if (GameTools.isDangerous(call.name(), args)) {
            if (approver == null) {
                return ToolResult.error("该操作需要玩家确认，但当前没有确认界面，已拒绝执行。");
            }
            Boolean allowed;
            try {
                allowed = approver.confirm(call.name(), GameTools.detailOf(call.name(), args)).join();
            } catch (RuntimeException e) {
                allowed = Boolean.FALSE;
            }
            if (!Boolean.TRUE.equals(allowed)) {
                return ToolResult.error("玩家拒绝执行该操作。");
            }
        }
        return GameTools.run(call.name(), args);
    }

    // ------------------------------------------------------------------
    // 请求体
    // ------------------------------------------------------------------

    private JsonObject baseBody(boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", this.config.model);

        // 推理强度：仅在当前模型声明了档位时才发送，避免打给不支持该参数的模型
        String effort = this.config.reasoningEffort;
        if (!isBlank(effort) && !this.config.currentEffortLabels().isEmpty()) {
            body.addProperty("reasoning_effort", this.config.effortValue(effort));
        }

        if (stream) {
            body.addProperty("stream", true);
            // 让服务端在最后一个 SSE 包里带上 usage；不支持的端会忽略。
            JsonObject streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true);
            body.add("stream_options", streamOptions);
        }
        return body;
    }

    /** 拼一段非空文本到系统消息里，段与段之间空一行。 */
    private static void appendBlock(StringBuilder target, String block) {
        if (isBlank(block)) {
            return;
        }
        if (target.length() > 0) {
            target.append("\n\n");
        }
        target.append(block.trim());
    }

    /**
     * 技能段落。
     *
     * <p>能用工具时只列已启用技能的名字 + 描述，正文交给 {@code load_skill} 按需拉取；
     * 不能用时退回原行为，把已启用技能的正文整段注入。</p>
     */
    private String skillsBlock(boolean toolsEnabled) {
        List<SkillRegistry.Skill> on = enabledSkills();
        if (on.isEmpty()) {
            return "";
        }
        if (toolsEnabled) {
            StringBuilder sb = new StringBuilder("[可用技能]\n");
            for (SkillRegistry.Skill skill : on) {
                sb.append("- ").append(skill.name()).append("：").append(skill.description()).append('\n');
            }
            sb.append("\n需要某个技能的完整规则时，调用 load_skill 工具读取它的正文。");
            return sb.toString();
        }
        StringBuilder sb = new StringBuilder();
        for (SkillRegistry.Skill skill : on) {
            sb.append("[已启用技能：").append(skill.name()).append("]\n")
                    .append(skill.body()).append("\n\n");
        }
        return sb.toString().trim();
    }

    /** 已启用的技能（按注册表顺序）。 */
    private List<SkillRegistry.Skill> enabledSkills() {
        List<String> enabled = this.config.enabledSkills == null ? List.of() : this.config.enabledSkills;
        List<SkillRegistry.Skill> on = new ArrayList<>();
        for (SkillRegistry.Skill skill : SkillRegistry.skills()) {
            if (enabled.contains(skill.name())) {
                on.add(skill);
            }
        }
        return on;
    }

    private static JsonArray toolsNode(List<ToolSpec> specs) {
        JsonArray array = new JsonArray();
        for (ToolSpec spec : specs) {
            JsonObject function = new JsonObject();
            function.addProperty("name", spec.name());
            function.addProperty("description", spec.description());
            function.add("parameters", spec.parameters());

            JsonObject tool = new JsonObject();
            tool.addProperty("type", "function");
            tool.add("function", function);
            array.add(tool);
        }
        return array;
    }

    /** 把模型的 tool_calls 回填成 assistant 消息。 */
    private static JsonObject assistantToolCallNode(Outcome outcome) {
        JsonObject node = new JsonObject();
        node.addProperty("role", "assistant");
        if (outcome.content.isEmpty()) {
            node.add("content", JsonNull.INSTANCE);
        } else {
            node.addProperty("content", outcome.content.toString());
        }
        JsonArray calls = new JsonArray();
        for (ToolCall call : outcome.calls) {
            JsonObject function = new JsonObject();
            function.addProperty("name", call.name());
            function.addProperty("arguments",
                    isBlank(call.arguments()) ? "{}" : call.arguments());

            JsonObject item = new JsonObject();
            item.addProperty("id", call.id());
            item.addProperty("type", "function");
            item.add("function", function);
            calls.add(item);
        }
        node.add("tool_calls", calls);
        return node;
    }

    private static JsonObject toolResultNode(String id, String content) {
        JsonObject node = new JsonObject();
        node.addProperty("role", "tool");
        node.addProperty("tool_call_id", id == null ? "" : id);
        node.addProperty("content", isBlank(content) ? "（无返回）" : content);
        return node;
    }

    private static JsonObject parseArgs(String arguments) {
        if (isBlank(arguments)) {
            return new JsonObject();
        }
        try {
            JsonElement element = JsonParser.parseString(arguments);
            return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    /** 工具活动提示，直接拼进回复里让玩家看到模型做了什么。 */
    private static String toolTrace(ToolCall call, ToolResult result) {
        String head = "［工具］" + call.name();
        String args = call.arguments() == null ? "" : call.arguments().trim();
        if (!args.isEmpty() && !"{}".equals(args)) {
            head += " " + abbreviate(args);
        }
        String body = result.content() == null ? "" : result.content().replace('\n', ' ');
        return "\n" + head + " → " + abbreviate(body) + "\n";
    }

    private static boolean looksLikeToolsUnsupported(RuntimeException e) {
        String message = e.getMessage() == null ? "" : e.getMessage();
        return message.contains("HTTP 400") || message.contains("HTTP 404") || message.contains("HTTP 422");
    }

    private HttpRequest buildRequest(JsonObject body, boolean streaming) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint()))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream, application/json")
                    .header("Authorization", "Bearer " + this.config.apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body), StandardCharsets.UTF_8));
            // 流式对话可能连续输出很久，设「总时限」会在生成到一半时被强行掐断，
            // 所以流式请求不设总时限（靠连接超时兜底，卡住时可点「停止」取消）；
            // 非流式的辅助请求（测试 / 摘要）保留较短时限。
            if (!streaming) {
                builder.timeout(Duration.ofSeconds(120));
            }
            return builder.build();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String endpoint() {
        String base = this.config.normalizedBaseUrl();
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    // ------------------------------------------------------------------
    // 响应
    // ------------------------------------------------------------------

    /** 一次响应的结果：累积的正文 + 模型请求的工具调用。 */
    private static final class Outcome {
        final StringBuilder content = new StringBuilder();
        final List<ToolCall> calls = new ArrayList<>();
    }

    /** 流式增量里按 index 累积的 tool_call 片段。 */
    private static final class ToolCallBuilder {
        String id = "";
        final StringBuilder name = new StringBuilder();
        final StringBuilder arguments = new StringBuilder();

        ToolCall build() {
            return new ToolCall(this.id, this.name.toString(), this.arguments.toString());
        }
    }

    private Outcome consume(HttpResponse<Stream<String>> response, Consumer<String> onDelta,
                            Consumer<String> onReasoning) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException("HTTP " + response.statusCode() + " "
                    + abbreviate(response.body().collect(Collectors.joining(" "))));
        }

        Outcome outcome = new Outcome();
        String contentType = response.headers().firstValue("Content-Type").orElse("");
        long[] reportedUsage = {0L};
        Map<Integer, ToolCallBuilder> builders = new TreeMap<>();

        if (contentType.contains("text/event-stream")) {
            // 标准 SSE：每行形如 data: {...}
            response.body().forEach(line -> {
                String payload = ssePayload(line);
                if (payload == null) {
                    return;
                }
                JsonObject json = tryParse(payload, null);
                if (json == null) {
                    return;
                }
                readUsage(json, reportedUsage);
                JsonObject delta = deltaNode(json);
                if (delta == null) {
                    return;
                }
                String reasoning = readReasoning(delta);
                if (!reasoning.isEmpty() && onReasoning != null) {
                    onReasoning.accept(reasoning);
                }
                String text = readContent(delta);
                if (!text.isEmpty()) {
                    outcome.content.append(text);
                    if (onDelta != null) {
                        onDelta.accept(text);
                    }
                }
                readToolCalls(delta, builders);
            });
        } else {
            // 兼容不支持流式、直接返回完整 JSON 的服务端
            String text = response.body().collect(Collectors.joining("\n"));
            JsonObject json = tryParse(text, text);
            if (json != null) {
                readUsage(json, reportedUsage);
                JsonObject message = messageNodeOf(json);
                if (message != null) {
                    String reasoning = readReasoning(message);
                    if (!reasoning.isEmpty() && onReasoning != null) {
                        onReasoning.accept(reasoning);
                    }
                    String content = readContent(message);
                    if (!content.isEmpty()) {
                        outcome.content.append(content);
                        if (onDelta != null) {
                            onDelta.accept(content);
                        }
                    }
                    readToolCalls(message, builders);
                }
            }
        }

        for (ToolCallBuilder builder : builders.values()) {
            outcome.calls.add(builder.build());
        }
        reportUsage(reportedUsage[0], outcome.content.length());
        return outcome;
    }

    /** 累积一段 tool_calls 增量；{@code index} 缺失时按数组下标处理。 */
    private static void readToolCalls(JsonObject node, Map<Integer, ToolCallBuilder> builders) {
        if (node == null || !node.has("tool_calls") || !node.get("tool_calls").isJsonArray()) {
            return;
        }
        JsonArray array = node.getAsJsonArray("tool_calls");
        for (int i = 0; i < array.size(); i++) {
            JsonElement itemElement = array.get(i);
            if (!itemElement.isJsonObject()) {
                continue;
            }
            JsonObject item = itemElement.getAsJsonObject();
            int index = item.has("index") && !item.get("index").isJsonNull() ? item.get("index").getAsInt() : i;
            ToolCallBuilder builder = builders.computeIfAbsent(index, key -> new ToolCallBuilder());
            String id = readString(item, "id");
            if (!id.isEmpty()) {
                builder.id = id;
            }
            if (item.has("function") && item.get("function").isJsonObject()) {
                JsonObject function = item.getAsJsonObject("function");
                String name = readString(function, "name");
                if (!name.isEmpty()) {
                    builder.name.append(name);
                }
                String arguments = readString(function, "arguments");
                if (!arguments.isEmpty()) {
                    builder.arguments.append(arguments);
                }
            }
        }
    }

    /** 从返回里读 usage.total_tokens（本次输入 + 输出的总量，取最后一个非零值）。 */
    private static void readUsage(JsonObject json, long[] sink) {
        if (json == null || !json.has("usage") || json.get("usage").isJsonNull()) {
            return;
        }
        try {
            JsonObject usage = json.getAsJsonObject("usage");
            long total = longField(usage, "total_tokens");
            if (total <= 0) {
                // 少数端不给 total_tokens：用「输入 + 输出」拼一个
                total = longField(usage, "prompt_tokens") + longField(usage, "completion_tokens");
            }
            if (total > 0) {
                sink[0] = total;
            }
        } catch (Exception ignored) {
            // usage 结构意外，退回估算
        }
    }

    /** 读一个可能缺失的整数字段；缺失或非法时返回 0。 */
    private static long longField(JsonObject json, String key) {
        if (!json.has(key) || json.get(key).isJsonNull()) {
            return 0L;
        }
        try {
            return json.get(key).getAsLong();
        } catch (Exception e) {
            return 0L;
        }
    }

    /** 上报本次总用量：优先用服务端的 total_tokens，拿不到按输出字符数估算。 */
    private void reportUsage(long reported, int outputChars) {
        if (this.usageListener == null) {
            return;
        }
        long tokens = reported;
        if (tokens <= 0) {
            // 粗略估算：约 4 个字符 = 1 token
            tokens = Math.max(1, outputChars / 4L);
        }
        try {
            this.usageListener.accept(tokens);
        } catch (RuntimeException ignored) {
            // 上报失败不影响对话
        }
    }

    private static String ssePayload(String line) {
        if (line == null) {
            return null;
        }
        String trimmed = line.trim();
        if (!trimmed.startsWith("data:")) {
            return null;
        }
        String payload = trimmed.substring("data:".length()).trim();
        if (payload.isEmpty() || "[DONE]".equals(payload)) {
            return null;
        }
        return payload;
    }

    /** choices[0].delta；拿不到返回 null。 */
    private static JsonObject deltaNode(JsonObject json) {
        JsonObject choice = firstChoice(json);
        if (choice == null || !choice.has("delta") || !choice.get("delta").isJsonObject()) {
            return null;
        }
        return choice.getAsJsonObject("delta");
    }

    /** choices[0].message；拿不到返回 null。 */
    private static JsonObject messageNodeOf(JsonObject json) {
        JsonObject choice = firstChoice(json);
        if (choice == null || !choice.has("message") || !choice.get("message").isJsonObject()) {
            return null;
        }
        return choice.getAsJsonObject("message");
    }

    private static JsonObject firstChoice(JsonObject json) {
        if (json == null) {
            return null;
        }
        JsonArray choices = json.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty() || !choices.get(0).isJsonObject()) {
            return null;
        }
        return choices.get(0).getAsJsonObject();
    }

    /** 取非流式内容：{@code reasoning=true} 取思考内容，否则取正文 choices[0].message.* 。 */
    private static String extractContent(JsonObject json, boolean reasoning) {
        JsonObject message = messageNodeOf(json);
        if (message == null) {
            if (reasoning) {
                return "";
            }
            JsonArray choices = json == null ? null : json.getAsJsonArray("choices");
            if (choices != null && !choices.isEmpty()) {
                return "";
            }
            throw new RuntimeException("返回中没有 choices：" + abbreviate(String.valueOf(json)));
        }
        return reasoning ? readReasoning(message) : readContent(message);
    }

    private static String readContent(JsonObject node) {
        if (node == null || node.get("content") == null || node.get("content").isJsonNull()) {
            return "";
        }
        try {
            return node.get("content").getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    /**
     * 读取思考内容。不同服务端字段名不一致，这里做兼容：
     * DeepSeek 用 {@code reasoning_content}，部分实现用 {@code reasoning}。
     */
    private static String readReasoning(JsonObject node) {
        if (node == null) {
            return "";
        }
        String value = readString(node, "reasoning_content");
        return value.isEmpty() ? readString(node, "reasoning") : value;
    }

    private static String readString(JsonObject node, String key) {
        if (node == null || !node.has(key) || node.get(key).isJsonNull()) {
            return "";
        }
        try {
            return node.get(key).getAsString();
        } catch (Exception e) {
            return "";
        }
    }

    private static void requireSuccess(int statusCode, String body) {
        if (statusCode < 200 || statusCode >= 300) {
            throw new RuntimeException("HTTP " + statusCode + " " + abbreviate(body));
        }
    }

    private static JsonObject tryParse(String text, String echoForError) {
        try {
            return JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            if (echoForError != null) {
                throw new RuntimeException("返回内容不是合法 JSON：" + abbreviate(echoForError));
            }
            return null;
        }
    }

    private static JsonObject messageNode(String role, String content) {
        JsonObject node = new JsonObject();
        node.addProperty("role", role);
        node.addProperty("content", content);
        return node;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= MAX_ECHO_LENGTH ? flat : flat.substring(0, MAX_ECHO_LENGTH) + "…";
    }
}
