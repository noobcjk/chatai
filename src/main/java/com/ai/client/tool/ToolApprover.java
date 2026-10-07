package com.ai.client.tool;

import java.util.concurrent.CompletableFuture;

/**
 * 危险操作的确认桥：由界面层实现，在客户端线程弹确认框。
 *
 * <p>工具在后台线程执行，实现方需要自己切回客户端线程弹窗，再在玩家选择后完成返回的 future。</p>
 */
public interface ToolApprover {

    /**
     * @param toolName 工具名
     * @param detail   展示给玩家的细节（比如即将执行的命令）
     * @return 玩家点「执行」完成 true；点「取消」或超时完成 false
     */
    CompletableFuture<Boolean> confirm(String toolName, String detail);
}
