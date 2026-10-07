package com.ai.client.tool;

/**
 * 工具执行结果，会作为 {@code role=tool} 消息回填给模型。
 *
 * @param ok      是否成功
 * @param content 给模型看的文本结果
 */
public record ToolResult(boolean ok, String content) {

    public static ToolResult ok(String content) {
        return new ToolResult(true, content);
    }

    public static ToolResult error(String content) {
        return new ToolResult(false, content);
    }
}
