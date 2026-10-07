package com.ai.client.tool;

import com.ai.client.config.ChataiConfig;
import com.ai.client.shell.RishShell;
import com.ai.client.shell.TermuxRunner;
import com.ai.client.skill.SkillRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Box;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 暴露给模型的工具集合：游戏只读查询、命令执行、技能正文加载。
 *
 * <p>工具在后台线程被调用，凡是要碰游戏状态的一律切回客户端线程再取，避免跨线程访问
 * {@link MinecraftClient} 的字段。</p>
 */
public final class GameTools {

    /** 一次回复里最多连续调用多少轮工具，防止模型来回刷。 */
    public static final int MAX_ROUNDS = 6;
    /** 切回客户端线程取游戏状态的等待上限。 */
    private static final long CLIENT_WAIT_SECONDS = 20;
    /** shell 工具默认允许的命令名；配置里 {@code shellAllowlist} 非空时以配置为准。 */
    public static final List<String> DEFAULT_SHELL_ALLOWLIST = List.of(
            "pm", "cmd", "getprop", "dumpsys", "settings", "am", "wm", "input",
            "id", "uname", "ls", "cat", "ps", "df", "date", "echo",
            "grep", "head", "tail", "sort", "uniq", "wc", "cut", "tr");
    /**
     * shell 工具除字母 / 数字 / 空格之外默认还允许出现的符号（白名单）。
     *
     * <p>不在其中的符号一律拒绝，含管道 {@code |}、路径分隔 {@code /}、参数前缀 {@code -} 等。
     * 配置里 {@code shellAllowedChars} 非空时以配置为准。</p>
     */
    public static final String DEFAULT_SHELL_ALLOWED_CHARS = "|!\"#%'+-./:=@[]^_,";
    /** shell 工具回给模型的输出上限，避免把上下文撑爆。 */
    private static final int MAX_SHELL_OUTPUT = 16000;

    private GameTools() {
    }

    /** 全部工具定义。没进入世界时也照常提供，调用后由各工具自己回报错误。 */
    public static List<ToolSpec> specs() {
        List<ToolSpec> list = new ArrayList<>();
        list.add(new ToolSpec("get_player_state",
                "读取玩家当前状态：坐标、维度、血量、饥饿、游戏模式、时间、天气、手持物品。",
                noParams(), false));
        list.add(new ToolSpec("get_inventory",
                "列出玩家背包里的物品及数量。",
                noParams(), false));
        list.add(new ToolSpec("get_nearby_entities",
                "列出玩家附近的实体（生物、玩家、掉落物等）及距离。",
                schema(props(jsonProp("radius", "integer", "搜索半径（方块），默认 16，最大 64")), "radius"), false));
        list.add(new ToolSpec("run_command",
                "以玩家身份执行一条 Minecraft 命令，不要带前导斜杠。例如：give @s diamond 1、time set day、tp @s 0 64 0。",
                schema(props(jsonProp("command", "string", "要执行的命令，不含前导斜杠")), "command"), true));
        list.add(new ToolSpec("shell",
                "以 Android shell 身份（uid 2000，经 Shizuku 提权）执行一条命令，用于查系统信息或做 shell 层面的操作。"
                        + "命令名必须是这些之一：" + String.join("、", allowlist())
                        + "。可以用 | 串联管道（管道每一段的命令名都要在白名单里，例如 ps | grep termux）；"
                        + "不支持重定向和分号串联，也不能带路径。执行前会先让玩家确认。",
                schema(props(jsonProp("command", "string", "要执行的命令，例如 ps | grep termux")), "command"), true));
        list.add(new ToolSpec("termux",
                "在 Termux（Android 终端 App）里执行一条 bash 命令并返回输出。命令以完整 shell 语法运行，"
                        + "支持管道和重定向，可以用 Termux 里装好的工具（python、ffmpeg、nmap、curl 等），"
                        + "也能读写 Termux 自己的文件。一次只发一条命令，最多等 20 秒，长任务请自己在命令里加 timeout 或放后台。",
                schema(props(jsonProp("command", "string", "要执行的 bash 命令，例如 whoami 或 python3 -c 'print(1)'")), "command"), true));
        if (!SkillRegistry.skills().isEmpty()) {
            list.add(new ToolSpec("load_skill",
                    "读取某个技能的完整说明正文；需要该技能的具体规则时再调用。",
                    schema(props(jsonProp("name", "string", "技能名，见系统提示词里的可用技能列表")), "name"), false));
        }
        return list;
    }

    /**
     * 该工具是否需要在执行前让玩家确认。
     *
     * <p>{@code shell} 和 {@code termux} 的确认策略可以在设置界面切换（自动同意 / 确认后执行）。</p>
     */
    public static boolean isDangerous(String name) {
        if ("shell".equals(name) || "termux".equals(name)) {
            return !ChataiConfig.get().shellAutoApprove;
        }
        return "run_command".equals(name);
    }

    /** 给确认框展示的细节文本。 */
    public static String detailOf(String name, JsonObject args) {
        if ("run_command".equals(name)) {
            return "/" + stringArg(args, "command");
        }
        if ("shell".equals(name)) {
            return "$ " + stringArg(args, "command");
        }
        if ("termux".equals(name)) {
            return "termux$ " + stringArg(args, "command");
        }
        return name;
    }

    /** 执行一次工具调用。参数非法或环境不满足时返回 {@link ToolResult#error}。 */
    public static ToolResult run(String name, JsonObject args) {
        try {
            return switch (name) {
                case "get_player_state" -> onClient(GameTools::playerState);
                case "get_inventory" -> onClient(GameTools::inventory);
                case "get_nearby_entities" -> onClient(() -> nearby(intArg(args, "radius", 16)));
                case "run_command" -> onClient(() -> command(stringArg(args, "command")));
                case "shell" -> shellTool(stringArg(args, "command"));
                case "termux" -> termuxTool(stringArg(args, "command"));
                case "load_skill" -> skill(stringArg(args, "name"));
                default -> ToolResult.error("未知工具：" + name);
            };
        } catch (RuntimeException e) {
            return ToolResult.error("工具执行失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 各工具实现
    // ------------------------------------------------------------------

    private static ToolResult playerState() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            return ToolResult.error("玩家还没进入世界。");
        }
        StringBuilder sb = new StringBuilder();
        sb.append("坐标 ").append(player.getBlockPos().getX())
                .append('/').append(player.getBlockPos().getY())
                .append('/').append(player.getBlockPos().getZ());
        sb.append("，维度 ").append(world.getRegistryKey().getValue());
        sb.append("，血量 ").append(Math.round(player.getHealth())).append('/')
                .append(Math.round(player.getMaxHealth()));
        sb.append("，饥饿 ").append(player.getHungerManager().getFoodLevel()).append("/20");
        sb.append("，游戏模式 ").append(gameMode(player));
        sb.append("，世界时间 ").append(world.getTimeOfDay()).append("（")
                .append(world.isDay() ? "白天" : "夜晚").append("）");
        sb.append("，天气 ").append(weather(world));
        ItemStack held = player.getMainHandStack();
        sb.append("，手持 ").append(held.isEmpty() ? "空手"
                : held.getName().getString() + " x" + held.getCount());
        return ToolResult.ok(sb.toString());
    }

    private static ToolResult inventory() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return ToolResult.error("玩家还没进入世界。");
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        collect(player.getInventory().main, counts);
        collect(player.getInventory().offHand, counts);
        if (counts.isEmpty()) {
            return ToolResult.ok("背包是空的。");
        }
        StringBuilder sb = new StringBuilder("背包共 ").append(counts.size()).append(" 种物品：");
        int shown = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (shown++ >= 40) {
                sb.append(" …（其余省略）");
                break;
            }
            sb.append('\n').append(entry.getKey()).append(" x").append(entry.getValue());
        }
        return ToolResult.ok(sb.toString());
    }

    private static ToolResult nearby(int radius) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = client.world;
        if (player == null || world == null) {
            return ToolResult.error("玩家还没进入世界。");
        }
        int r = Math.max(1, Math.min(radius, 64));
        Box box = player.getBoundingBox().expand(r);
        List<Entity> entities = world.getEntitiesByClass(Entity.class, box, entity -> entity != player);
        if (entities.isEmpty()) {
            return ToolResult.ok("半径 " + r + " 格内没有其它实体。");
        }
        StringBuilder sb = new StringBuilder("半径 " + r + " 格内有 ").append(entities.size()).append(" 个实体：");
        int shown = 0;
        for (Entity entity : entities) {
            if (shown++ >= 20) {
                sb.append("\n…（其余省略）");
                break;
            }
            int distance = (int) Math.round(Math.sqrt(player.squaredDistanceTo(entity)));
            sb.append('\n').append(entity.getName().getString()).append("（").append(distance).append(" 格）");
        }
        return ToolResult.ok(sb.toString());
    }

    private static ToolResult command(String command) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (command == null || command.isBlank()) {
            return ToolResult.error("没有给出要执行的命令。");
        }
        ClientPlayerEntity player = client.player;
        if (player == null || player.networkHandler == null) {
            return ToolResult.error("玩家还没进入世界，无法执行命令。");
        }
        String cleaned = command.trim();
        while (cleaned.startsWith("/")) {
            cleaned = cleaned.substring(1);
        }
        player.networkHandler.sendChatMessage("/" + cleaned);
        return ToolResult.ok("已发送命令：" + cleaned);
    }

    private static ToolResult skill(String name) {
        if (name == null || name.isBlank()) {
            return ToolResult.error("没有给出技能名。");
        }
        SkillRegistry.Skill skill = SkillRegistry.byName(name.trim());
        if (skill == null) {
            List<String> names = new ArrayList<>();
            for (SkillRegistry.Skill each : SkillRegistry.skills()) {
                names.add(each.name());
            }
            return ToolResult.error("没有名为 " + name + " 的技能。可用技能：" + String.join("、", names));
        }
        return ToolResult.ok(skill.body());
    }

    /**
     * {@code shell} 工具：命令名白名单 + 符号白名单之后，交给 rish 以 shell 身份执行。
     *
     * <p>故意不走「拼字符串丢给 sh」那条路：只放行字母 / 数字 / 空格和白名单里的符号，
     * 拆句、改流、命令替换用到的字符都不在其中；命令名（管道每段第一个词）也必须在
     * 白名单里，剩下的就只是一个普通 argv。两个白名单都可在 GUI 里改。</p>
     */
    private static ToolResult shellTool(String command) {
        if (command == null || command.isBlank()) {
            return ToolResult.error("没有给出要执行的命令。");
        }
        String cleaned = command.trim();
        String allowedChars = allowedChars();
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '\n' || c == '\r') {
                return ToolResult.error("命令里不能换行。");
            }
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '\t') {
                continue;
            }
            if (allowedChars.indexOf(c) >= 0) {
                continue;
            }
            return ToolResult.error("命令里不能出现 " + c + " 这个符号（可在「设置 → shell 白名单」里放行）。");
        }
        List<String> allowed = allowlist();
        // 支持管道：按 | 拆段，每一段的命令名都要在白名单里，避免用管道夹带任意命令
        for (String segment : cleaned.split("\\|")) {
            String part = segment.trim();
            if (part.isEmpty()) {
                return ToolResult.error("管道两侧都必须是白名单里的命令。");
            }
            int gap = part.indexOf(' ');
            String segmentHead = gap < 0 ? part : part.substring(0, gap);
            if (segmentHead.contains("/")) {
                return ToolResult.error("只能写白名单里的命令名，不能带路径。");
            }
            if (!allowed.contains(segmentHead)) {
                return ToolResult.error(segmentHead + " 不在白名单里。可用命令：" + String.join("、", allowed));
            }
        }

        RishShell.Result result;
        try {
            result = RishShell.runCommand(cleaned);
        } catch (RuntimeException e) {
            return ToolResult.error("执行失败：" + e.getMessage());
        }

        String output = result.output().strip();
        if (output.length() > MAX_SHELL_OUTPUT) {
            output = output.substring(0, MAX_SHELL_OUTPUT) + "\n…（输出过长，已截断）";
        }
        if (output.isEmpty()) {
            output = "（无输出）";
        }
        return result.ok()
                ? ToolResult.ok(output)
                : ToolResult.error("命令执行失败（" + result.note() + "）：\n" + output);
    }

    /** {@code termux} 工具：把命令投递给 Termux 的 bash 执行并回传输出。 */
    private static ToolResult termuxTool(String command) {
        if (command == null || command.isBlank()) {
            return ToolResult.error("没有给出要执行的命令。");
        }
        TermuxRunner.Result result = TermuxRunner.run(command);
        String output = result.output() == null ? "" : result.output().strip();
        if (output.isEmpty()) {
            output = "（无输出）";
        }
        return result.ok()
                ? ToolResult.ok(output)
                : ToolResult.error("Termux 执行失败（" + result.note() + "）：\n" + output);
    }

    /** shell 工具的白名单：配置里有就用配置的，否则用内置默认表。 */
    private static List<String> allowlist() {
        List<String> configured = ChataiConfig.get().shellAllowlist;
        return configured == null || configured.isEmpty() ? DEFAULT_SHELL_ALLOWLIST : configured;
    }

    /** shell 工具的符号白名单：配置里有就用配置的，否则用内置默认表。 */
    private static String allowedChars() {
        String configured = ChataiConfig.get().shellAllowedChars;
        return configured == null || configured.isBlank() ? DEFAULT_SHELL_ALLOWED_CHARS : configured;
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private static void collect(List<ItemStack> stacks, Map<String, Integer> counts) {
        if (stacks == null) {
            return;
        }
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            counts.merge(stack.getName().getString(), stack.getCount(), Integer::sum);
        }
    }

    private static String gameMode(ClientPlayerEntity player) {
        if (player.isSpectator()) {
            return "旁观";
        }
        if (player.isCreative()) {
            return "创造";
        }
        return "生存/冒险";
    }

    private static String weather(ClientWorld world) {
        if (world.isThundering()) {
            return "雷雨";
        }
        return world.isRaining() ? "下雨" : "晴朗";
    }

    /** 把取游戏状态的动作切回客户端线程执行并等待结果。 */
    private static ToolResult onClient(Supplier<ToolResult> action) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.isOnThread()) {
            return action.get();
        }
        CompletableFuture<ToolResult> future = new CompletableFuture<>();
        client.execute(() -> {
            try {
                future.complete(action.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(CLIENT_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            return ToolResult.error("读取游戏状态超时。");
        }
    }

    private static JsonObject noParams() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return schema;
    }

    private static JsonObject schema(JsonObject properties, String... required) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties);
        JsonArray requiredArray = new JsonArray();
        for (String name : required) {
            requiredArray.add(name);
        }
        if (!requiredArray.isEmpty()) {
            schema.add("required", requiredArray);
        }
        return schema;
    }

    private static JsonObject props(JsonObject... entries) {
        JsonObject properties = new JsonObject();
        for (JsonObject entry : entries) {
            properties.add(entry.get("name").getAsString(), entry.getAsJsonObject("schema"));
        }
        return properties;
    }

    private static JsonObject jsonProp(String name, String type, String description) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", type);
        schema.addProperty("description", description);
        JsonObject wrapper = new JsonObject();
        wrapper.addProperty("name", name);
        wrapper.add("schema", schema);
        return wrapper;
    }

    private static String stringArg(JsonObject args, String key) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            return "";
        }
        try {
            return args.get(key).getAsString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static int intArg(JsonObject args, String key, int fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
