package com.ai.client;

import com.ai.client.gui.ChatAiScreen;
import com.ai.client.pet.PetSounds;
import com.ai.client.shell.GetShellCommand;
import com.ai.client.skill.SkillRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/**
 * 客户端入口：注册按键并处理打开界面。
 */
public class ChataiClient implements ClientModInitializer {

    /** 按键分类的翻译键。 */
    public static final String KEY_CATEGORY = "key.categories.chatai";

    private static KeyBinding openChatKey;

    @Override
    public void onInitializeClient() {
        // 注册宠物音效（触发静态字段初始化）
        PetSounds.init();

        // 提前把 config/chatai/skills 建出来，玩家直接往里丢技能文件即可
        SkillRegistry.ensureDirectory();

        // 客户端命令 /getshell，用来验证 rish 环境
        GetShellCommand.register();

        openChatKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.chatai.open",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_Z,          // 按 Z 打开
                KEY_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openChatKey.wasPressed()) {
                // 没有其它界面打开时才打开，避免重复开/关
                if (client.currentScreen == null) {
                    client.setScreen(new ChatAiScreen());
                }
            }
        });
    }
}
