package com.ai.client.shell;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * 客户端命令 {@code /getshell}，只用来验证 rish 环境。
 *
 * <p>纯客户端命令：不走服务端、不需要 op，也不会被 AI 对话界面当成消息发给模型。</p>
 *
 * <p>用法：{@code /getshell} 跑固定探针；{@code /getshell <命令>} 把命令丢给远端 shell。</p>
 */
public final class GetShellCommand {

    private GetShellCommand() {
    }

    public static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(ClientCommandManager.literal("getshell")
                        .executes(context -> execute(""))
                        .then(ClientCommandManager.argument("command", StringArgumentType.greedyString())
                                .executes(context -> execute(StringArgumentType.getString(context, "command"))))));
    }

    private static int execute(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        say(client, "§7[chatai] 正在测试 rish…");

        Thread worker = new Thread(() -> {
            String report = command.isBlank()
                    ? RishShell.probe()
                    : RishShell.runCommand(command).output();
            if (report.isBlank()) {
                report = "(无输出)";
            }
            String out = report;
            client.execute(() -> say(client, out));
        }, "chatai-getshell");
        worker.setDaemon(true);
        worker.start();
        return 1;
    }

    private static void say(MinecraftClient client, String message) {
        client.inGameHud.getChatHud().addMessage(Text.literal(message));
    }
}
