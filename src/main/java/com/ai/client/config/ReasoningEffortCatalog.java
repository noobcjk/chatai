package com.ai.client.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 推理强度档位的内置知识库（对应 dsh-reasoning-effort 的 entries 目录）。
 *
 * <p>键是模型 id 的片段，命中即返回该模型的档位表（显示名 → 端点实际取值）。
 * 未收录的模型返回空表，此时不显示滑块、也不发送 {@code reasoning_effort}，
 * 需要用户在 {@code config/chatai.json} 的 {@code models} 里自行声明，行为与
 * DSH 侧「模型没声明档位就不显示滑块」一致。</p>
 */
public final class ReasoningEffortCatalog {

    private ReasoningEffortCatalog() {
    }

    /** 模型 id 片段 → 档位表（保持声明顺序）。 */
    private static final Map<String, Map<String, String>> KNOWN = new LinkedHashMap<>();

    static {
        KNOWN.put("gpt-5", levels("minimal", "low", "medium", "high"));
        KNOWN.put("o3", levels("low", "medium", "high"));
        KNOWN.put("o4", levels("low", "medium", "high"));
        KNOWN.put("grok-3-mini", levels("low", "high"));
        KNOWN.put("grok-4", levels("low", "high"));
    }

    private static Map<String, String> levels(String... values) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String value : values) {
            map.put(value, value);
        }
        return map;
    }

    /** 按模型 id 查内置档位；未收录返回空表（调用方需要判空）。 */
    public static Map<String, String> builtin(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return Collections.emptyMap();
        }
        String id = modelId.toLowerCase();
        for (Map.Entry<String, Map<String, String>> entry : KNOWN.entrySet()) {
            if (id.contains(entry.getKey())) {
                return new LinkedHashMap<>(entry.getValue());
            }
        }
        return Collections.emptyMap();
    }
}
