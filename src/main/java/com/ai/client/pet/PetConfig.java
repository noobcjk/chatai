package com.ai.client.pet;

import com.ai.Chatai;
import com.ai.client.config.ChataiConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 宠物的持久化状态，保存在 {@code config/chatai-pet.json}。
 *
 * <p>字段全是基本类型，Gson 直接反射即可，不需要 DTO。</p>
 */
public class PetConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static PetConfig instance;

    /** 立绘边长（逻辑像素）。固定值，不再提供调整入口。 */
    public static final int PET_SIZE = 176;
    /** 音效音量（0~1）。固定值，不再提供调整入口。 */
    public static final float SOUND_VOLUME = 0.8F;

    /** 平板读数：true = 累计消耗 token（默认），false = 余额。 */
    public boolean readoutTokenMode = true;
    /** 累计消耗的 token。 */
    public long totalTokens = 0;
    /** 已经扣过几次血（每满 5000 输出 token 一次）。 */
    public long hpLossCount = 0;
    /** 剩余血量。 */
    public int hp = 20;
    public int maxHp = 20;
    /** 上次看到的余额，用于判断扣费 / 充值。 */
    public double lastBalance = -1.0;
    /** 余额接口地址（默认 DeepSeek）。 */
    public String balanceUrl = "https://api.deepseek.com/user/balance";
    /** 为 true 时余额接口复用对话的 API Key。 */
    public boolean reuseChatKey = true;
    /** reuseChatKey 为 false 时使用。 */
    public String balanceKey = "";

    public boolean soundEnabled = true;
    public boolean showPet = true;

    public static PetConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("chatai-pet.json");
    }

    private static PetConfig load() {
        Path path = file();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                PetConfig config = GSON.fromJson(reader, PetConfig.class);
                if (config != null) {
                    return config;
                }
            } catch (Exception e) {
                Chatai.LOGGER.warn("读取宠物状态失败: {}", e.toString());
            }
        }
        return new PetConfig();
    }

    public void save() {
        try {
            Files.createDirectories(file().getParent());
            try (Writer writer = Files.newBufferedWriter(file(), StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
        } catch (Exception e) {
            Chatai.LOGGER.warn("保存宠物状态失败: {}", e.toString());
        }
    }

    /** 余额接口实际使用的 Key。 */
    public String effectiveBalanceKey() {
        if (this.reuseChatKey) {
            return ChataiConfig.get().apiKey;
        }
        return this.balanceKey;
    }
}
