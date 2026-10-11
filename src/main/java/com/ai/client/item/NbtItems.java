package com.ai.client.item;

import com.ai.client.file.SdcardFiles;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket;
import net.minecraft.registry.Registries;

import java.util.ArrayList;
import java.util.List;

/**
 * 带 NBT 的物品的发放与修改。
 *
 * <p>走的是创造模式的「设置创造模式槽位」包（{@link CreativeInventoryActionC2SPacket}）：
 * 客户端直接把构造好的 {@link ItemStack}（含任意 NBT）塞进某个背包槽位，服务端照单全收。
 * 因此不要求玩家有 op 权限，但**要求当前是创造模式**；生存模式下服务端会忽略这个包，
 * 所以这里会先检查并直接报错，避免出现「说成功其实没生效」。</p>
 *
 * <p>槽位沿用背包索引：0-8 是快捷栏，9-35 是主背包；网线上的槽位号对快捷栏要 +36，
 * 由 {@link #packetSlot(int)} 换算。</p>
 */
public final class NbtItems {

    /** 一次操作的结果。 */
    public record Result(boolean ok, String text) {
    }

    /** 不指定槽位：自动找第一个空位，找不到就替换手持。 */
    public static final int AUTO_SLOT = -1;

    /** 单次读取的文本上限（load 的文件、界面上粘贴的内容）。 */
    public static final int MAX_TEXT_BYTES = 1024 * 1024;

    /** 背包槽位数量（不含盔甲与副手）。 */
    private static final int INVENTORY_SLOTS = 36;
    /** 快捷栏槽位数，换算网线槽位时要加它。 */
    private static final int HOTBAR_SLOTS = 9;
    /** 一次最多发放多少件，防止内容里写太多把背包刷爆。 */
    private static final int MAX_BATCH = 64;

    private NbtItems() {
    }

    /** 按 op 分发。{@code op} 为 read / give / set / load。 */
    public static Result run(String op, String id, int count, String nbt, int slot, String path) {
        String action = op == null ? "" : op.trim().toLowerCase();
        return switch (action) {
            case "read", "show" -> read(slot);
            case "give" -> give(id, count, nbt, slot);
            case "set", "modify" -> set(nbt);
            case "load", "json" -> load(path);
            default -> new Result(false, "不支持的 op：" + op + "。可用：read、give、set、load。");
        };
    }

    // ------------------------------------------------------------------
    // 各操作
    // ------------------------------------------------------------------

    /** 读取某个槽位（不给就用手持）的物品 id、数量与 NBT。 */
    private static Result read(int slot) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) {
            return new Result(false, "玩家还没进入世界。");
        }
        int index = slot < 0 ? player.getInventory().selectedSlot : slot;
        if (index < 0 || index >= INVENTORY_SLOTS) {
            return new Result(false, "槽位要在 0-35 之间（0-8 是快捷栏）。");
        }
        ItemStack stack = player.getInventory().getStack(index);
        if (stack.isEmpty()) {
            return new Result(true, "槽位 " + index + " 是空的。");
        }
        StringBuilder sb = new StringBuilder("槽位 ").append(index).append("：")
                .append(itemId(stack)).append(" x").append(stack.getCount());
        NbtCompound tag = stack.getNbt();
        sb.append(tag == null || tag.getKeys().isEmpty() ? "\n没有 NBT。" : "\nNBT：" + tag);
        return new Result(true, sb.toString());
    }

    /** 发放一件带 NBT 的物品。 */
    private static Result give(String id, int count, String nbt, int slot) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        Result guard = requireCreative(player);
        if (guard != null) {
            return guard;
        }
        ItemStack stack;
        try {
            stack = build(id, count, nbt);
        } catch (IllegalArgumentException e) {
            return new Result(false, e.getMessage());
        }
        if (slot >= INVENTORY_SLOTS) {
            return new Result(false, "槽位要在 0-35 之间（0-8 是快捷栏）。");
        }
        int index = slot < 0 ? autoSlot(player) : slot;
        send(player, index, stack);
        return new Result(true, "已把 " + itemId(stack) + " x" + stack.getCount()
                + " 放进槽位 " + index + (hasNbt(stack) ? "（含 NBT）" : "") + "。");
    }

    /**
     * 修改手持物品的 NBT。
     *
     * <p>合并语义：给出的键覆盖同名键，没提到的键保留（例如只改名字不会把附魔弄丢）。</p>
     */
    private static Result set(String nbt) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        Result guard = requireCreative(player);
        if (guard != null) {
            return guard;
        }
        if (nbt == null || nbt.isBlank()) {
            return new Result(false, "set 需要给出 nbt（SNBT 字符串）。");
        }
        int index = player.getInventory().selectedSlot;
        ItemStack held = player.getInventory().getStack(index);
        if (held.isEmpty()) {
            return new Result(false, "手上没有物品。先手持要改的物品，或用 give 发一件。");
        }
        NbtCompound patch;
        try {
            patch = StringNbtReader.parse(nbt.trim());
        } catch (Exception e) {
            return new Result(false, "NBT 解析失败：" + e.getMessage());
        }
        if (patch.getKeys().isEmpty()) {
            return new Result(false, "给出的 NBT 是空的，没东西可改。");
        }
        NbtCompound merged = held.getNbt() == null ? new NbtCompound() : held.getNbt().copy();
        merged.copyFrom(patch);
        ItemStack stack = held.copy();
        stack.setNbt(merged);
        send(player, index, stack);
        return new Result(true, "已更新槽位 " + index + " 的 " + itemId(stack)
                + " 的 NBT，合并了这些键：" + String.join("、", patch.getKeys()) + "。");
    }

    /** 读取工作区里的文件并加载。 */
    private static Result load(String path) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        Result guard = requireCreative(player);
        if (guard != null) {
            return guard;
        }
        if (path == null || path.isBlank()) {
            return new Result(false, "load 需要给出文件路径。");
        }
        String content;
        try {
            content = SdcardFiles.readText(path, MAX_TEXT_BYTES);
        } catch (Exception e) {
            return new Result(false, "读取文件失败：" + e.getMessage());
        }
        return applyText(content, path);
    }

    /**
     * 直接加载一段内容，界面上手写 / 从文件读来的都用这个入口。
     *
     * <p>吃两种东西：</p>
     * <ul>
     *   <li>json 包装：{@code {"items":[{id,count,nbt,slot}]}}，或裸数组 {@code [...]}；</li>
     *   <li>裸 SNBT：一个完整物品 {@code {id:"...",Count:1b,tag:{...}}}，
     *       或物品列表 {@code [{...},{...}]}。</li>
     * </ul>
     */
    public static Result applyText(String content) {
        return applyText(content, "内容");
    }

    private static Result applyText(String content, String label) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        Result guard = requireCreative(player);
        if (guard != null) {
            return guard;
        }
        String text = content == null ? "" : content.trim();
        if (text.isEmpty()) {
            return new Result(false, "内容是空的。");
        }

        // 先按 json 包装试一次；不是 json（SNBT 的键名不带引号，本来也不是合法 json）就走 SNBT
        JsonArray items = null;
        try {
            items = itemsOf(JsonParser.parseString(text));
        } catch (Exception ignored) {
            // 交给下面的 SNBT 分支处理
        }
        if (items != null) {
            return giveFromJson(player, items, label);
        }
        return giveFromSnbt(player, text, label);
    }

    // ------------------------------------------------------------------
    // 两条加载路径
    // ------------------------------------------------------------------

    private static Result giveFromJson(ClientPlayerEntity player, JsonArray items, String label) {
        if (items.isEmpty()) {
            return new Result(false, "items 数组是空的。");
        }
        List<String> lines = new ArrayList<>();
        int given = 0;
        for (JsonElement element : items) {
            if (given >= MAX_BATCH) {
                lines.add("…（超过 " + MAX_BATCH + " 件，其余跳过）");
                break;
            }
            if (!element.isJsonObject()) {
                lines.add("跳过：数组项不是对象。");
                continue;
            }
            JsonObject entry = element.getAsJsonObject();
            String id = string(entry, "id");
            int count = integer(entry, "count", 1);
            String nbt = string(entry, "nbt");
            int slot = integer(entry, "slot", AUTO_SLOT);
            if (id.isEmpty()) {
                lines.add("跳过：缺少 id。");
                continue;
            }
            if (slot >= INVENTORY_SLOTS) {
                lines.add("跳过 " + id + "：slot 要在 0-35 之间。");
                continue;
            }
            ItemStack stack;
            try {
                stack = build(id, count, nbt);
            } catch (IllegalArgumentException e) {
                lines.add("跳过 " + id + "：" + e.getMessage());
                continue;
            }
            int index = slot < 0 ? autoSlot(player) : slot;
            send(player, index, stack);
            given++;
            lines.add(given + ". " + itemId(stack) + " x" + stack.getCount()
                    + " → 槽位 " + index + (hasNbt(stack) ? "（含 NBT）" : ""));
        }
        if (given == 0) {
            return new Result(false, "没有发放任何物品：\n" + String.join("\n", lines));
        }
        return new Result(true, "已按 " + label + " 发放 " + given + " 件：\n" + String.join("\n", lines));
    }

    /** 裸 SNBT：顶层是单个物品的复合，或者一串物品的列表。 */
    private static Result giveFromSnbt(ClientPlayerEntity player, String text, String label) {
        // 顶层两种形状都套一层再解析，就不用分别写两遍语法
        NbtCompound wrapper;
        try {
            wrapper = StringNbtReader.parse("{v:" + text + "}");
        } catch (Exception e) {
            return new Result(false, "SNBT 解析失败：" + e.getMessage());
        }
        NbtElement value = wrapper.get("v");
        List<NbtCompound> entries = new ArrayList<>();
        if (value instanceof NbtCompound compound) {
            entries.add(compound);
        } else if (value instanceof NbtList list) {
            for (NbtElement element : list) {
                if (element instanceof NbtCompound compound) {
                    entries.add(compound);
                }
            }
        }
        if (entries.isEmpty()) {
            return new Result(false, "没解析出物品：顶层要写成 {id:...} 或 [{id:...}, {id:...}]。");
        }

        List<String> lines = new ArrayList<>();
        int given = 0;
        for (NbtCompound entry : entries) {
            if (given >= MAX_BATCH) {
                lines.add("…（超过 " + MAX_BATCH + " 件，其余跳过）");
                break;
            }
            if (!entry.contains("id")) {
                lines.add("跳过：缺少 id" + bedrockHint(entry));
                continue;
            }
            ItemStack stack = ItemStack.fromNbt(entry.copy());
            if (stack.isEmpty()) {
                lines.add("跳过 " + entry.getString("id") + "：id 无效或不可用。");
                continue;
            }
            stack.setCount(Math.min(stack.getCount(), stack.getMaxCount()));
            int index = autoSlot(player);
            send(player, index, stack);
            given++;
            lines.add(given + ". " + itemId(stack) + " x" + stack.getCount()
                    + " → 槽位 " + index + (hasNbt(stack) ? "（含 NBT）" : ""));
        }
        if (given == 0) {
            return new Result(false, "没有发放任何物品：\n" + String.join("\n", lines));
        }
        return new Result(true, "已按 " + label + " 发放 " + given + " 件：\n" + String.join("\n", lines));
    }

    /** 基岩版物品用的是另一套键名，命中时给一句人看得懂的提示。 */
    private static String bedrockHint(NbtCompound compound) {
        if (compound.contains("WasPickedUp") || compound.contains("Block")
                || compound.contains("Name")) {
            return "（这看起来是基岩版的物品格式：用 Name 而不是 id，Java 读不了）";
        }
        return "";
    }

    // ------------------------------------------------------------------
    // 构造与发送
    // ------------------------------------------------------------------

    /**
     * 用「完整物品 NBT」构造物品：{@code {id:"...", Count:n, tag:{...}}}。
     *
     * <p>不直接查注册表，而是交给 {@link ItemStack#fromNbt} —— 这样 id 的合法性、数量上限、
     * 各字段的默认值都由游戏自己按当前版本规则处理，mod 加了物品也能认。</p>
     */
    private static ItemStack build(String id, int count, String nbt) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("没给出物品 id。");
        }
        NbtCompound full = new NbtCompound();
        full.putString("id", id.trim());
        full.putInt("Count", Math.max(1, count));
        if (nbt != null && !nbt.isBlank()) {
            try {
                full.put("tag", StringNbtReader.parse(nbt.trim()));
            } catch (Exception e) {
                throw new IllegalArgumentException("NBT 解析失败：" + e.getMessage());
            }
        }
        ItemStack stack = ItemStack.fromNbt(full);
        if (stack == null || stack.isEmpty()) {
            throw new IllegalArgumentException("物品 id 无效或不可用：" + id);
        }
        stack.setCount(Math.min(Math.max(1, count), stack.getMaxCount()));
        return stack;
    }

    /** 把物品塞进背包的第 {@code index} 格并同步给服务端。 */
    private static void send(ClientPlayerEntity player, int index, ItemStack stack) {
        player.networkHandler.sendPacket(new CreativeInventoryActionC2SPacket(packetSlot(index), stack));
        // 本地先落一份，不然界面要等服务端回包才更新
        player.getInventory().setStack(index, stack.copy());
    }

    /** 背包索引换成网线槽位号：快捷栏（0-8）对应 36-44，主背包（9-35）与索引一致。 */
    private static int packetSlot(int index) {
        return index < HOTBAR_SLOTS ? index + INVENTORY_SLOTS : index;
    }

    /** 第一个空格子；全满就用当前手持那一格。 */
    private static int autoSlot(ClientPlayerEntity player) {
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < INVENTORY_SLOTS; i++) {
            if (inventory.getStack(i).isEmpty()) {
                return i;
            }
        }
        return inventory.selectedSlot;
    }

    private static Result requireCreative(ClientPlayerEntity player) {
        if (player == null || player.networkHandler == null) {
            return new Result(false, "玩家还没进入世界。");
        }
        if (!player.isCreative()) {
            return new Result(false, "只有创造模式才能直接发放 / 修改带 NBT 的物品（服务端会忽略这类包）。");
        }
        return null;
    }

    private static String itemId(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).toString();
    }

    private static boolean hasNbt(ItemStack stack) {
        NbtCompound tag = stack.getNbt();
        return tag != null && !tag.getKeys().isEmpty();
    }

    // ------------------------------------------------------------------
    // json 读取
    // ------------------------------------------------------------------

    /** 接受 {@code {"items":[...]}}，也接受裸数组 {@code [...]}；都不是返回 null。 */
    private static JsonArray itemsOf(JsonElement root) {
        if (root == null) {
            return null;
        }
        if (root.isJsonArray()) {
            return root.getAsJsonArray();
        }
        if (root.isJsonObject()) {
            JsonElement items = root.getAsJsonObject().get("items");
            if (items != null && items.isJsonArray()) {
                return items.getAsJsonArray();
            }
        }
        return null;
    }

    private static String string(JsonObject object, String key) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return "";
        }
        try {
            return value.getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        JsonElement value = object.get(key);
        if (value == null || value.isJsonNull()) {
            return fallback;
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
