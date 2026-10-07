package com.ai.client.pet;

import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

/**
 * 宠物音效注册（hit.mp3 / feed.mp3 已转成 ogg）。
 */
public final class PetSounds {

    public static final SoundEvent HIT = register("pet.hit");
    public static final SoundEvent FEED = register("pet.feed");

    private PetSounds() {
    }

    private static SoundEvent register(String path) {
        Identifier id = new Identifier("chatai", path);
        return Registry.register(Registries.SOUND_EVENT, id, SoundEvent.of(id));
    }

    /** 触发类加载，让静态字段完成注册。 */
    public static void init() {
    }
}
