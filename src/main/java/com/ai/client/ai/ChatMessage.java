package com.ai.client.ai;

/**
 * 一条对话消息。整个 AI 对话模块都围绕它传递数据。
 *
 * <p>{@code reasoning} 是思考型模型返回的思维链内容（DeepSeek 的
 * {@code reasoning_content} 等）。它可以为空；为空时界面不显示「思考」折叠块。</p>
 */
public record ChatMessage(Role role, String content, String reasoning) {

    public ChatMessage {
        if (reasoning == null) {
            reasoning = "";
        }
    }

    public enum Role {
        USER,
        ASSISTANT
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(Role.USER, content, "");
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(Role.ASSISTANT, content, "");
    }

    public static ChatMessage assistant(String content, String reasoning) {
        return new ChatMessage(Role.ASSISTANT, content, reasoning);
    }

    /** 是否有可展示的思考内容。 */
    public boolean hasReasoning() {
        return this.reasoning != null && !this.reasoning.isBlank();
    }
}
