package com.ai.client.ai;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * AI 对话服务抽象。
 *
 * <p>接入真实接口时只需要实现本接口，GUI 层无需改动。</p>
 */
public interface AiService {

    /**
     * 流式发送对话历史。
     *
     * <p>返回的 future 在整段回复结束时完成，值为完整回复文本；
     * 每收到一小段增量内容时会回调 {@code onDelta}。</p>
     *
     * <p>注意：{@code onDelta} 在后台线程被调用，实现方不要阻塞；
     * 界面层需要自行切回客户端主线程再更新 UI。</p>
     *
     * @param history     按时间顺序排列的对话历史（最后一条是用户刚发的消息）
     * @param onDelta     正文增量文本回调
     * @param onReasoning 思考（思维链）增量文本回调；模型不返回思考内容时不会被调用
     */
    CompletableFuture<String> stream(List<ChatMessage> history, Consumer<String> onDelta,
                                     Consumer<String> onReasoning);
}
