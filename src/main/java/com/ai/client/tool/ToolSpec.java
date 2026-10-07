package com.ai.client.tool;

import com.google.gson.JsonObject;

/**
 * 一个可被模型调用的工具定义，对应 OpenAI {@code tools[].function}。
 *
 * @param name        工具名（模型调用时回传的名字）
 * @param description 给模型看的用途说明
 * @param parameters  JSON Schema 形式的参数定义
 * @param dangerous   是否会修改游戏状态；true 时执行前要玩家确认
 */
public record ToolSpec(String name, String description, JsonObject parameters, boolean dangerous) {
}
