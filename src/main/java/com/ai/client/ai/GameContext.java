package com.ai.client.ai;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * 把当前游戏状态拼成一行文字，随每次请求注入系统提示词。
 *
 * <p>状态在每次构造请求时现取，所以模型看到的是「发消息那一刻」的坐标、群系等。</p>
 */
public final class GameContext {

    private GameContext() {
    }

    /**
     * @return 形如「[当前游戏] 坐标 12/64/-30, 群系 plains, 时间 白天, 血量 18/20, 手持 铁剑」；
     *         还没进入世界（主菜单 / 正在连接）时返回空串，调用方跳过注入。
     */
    public static String describe() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            return "";
        }

        BlockPos pos = player.getBlockPos();
        String biome = world.getBiome(pos).getKey()
                .map(RegistryKey::getValue)
                .map(Identifier::getPath)
                .orElse("unknown");

        String time = world.isDay() ? "白天" : "夜晚";
        String health = Math.round(player.getHealth()) + "/" + Math.round(player.getMaxHealth());

        ItemStack held = player.getMainHandStack();
        String hand = held.isEmpty() ? "空手" : held.getName().getString();

        return "[当前游戏] 坐标 " + pos.getX() + "/" + pos.getY() + "/" + pos.getZ()
                + ", 群系 " + biome
                + ", 时间 " + time
                + ", 血量 " + health
                + ", 手持 " + hand;
    }
}
