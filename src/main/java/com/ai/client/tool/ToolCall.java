package com.ai.client.tool;

/**
 * 模型请求的一次工具调用。
 *
 * @param id        调用 id，回填结果时要带上
 * @param name      工具名
 * @param arguments 模型给的参数，JSON 字符串（可能为空或非法，调用方要容错）
 */
public record ToolCall(String id, String name, String arguments) {
}
