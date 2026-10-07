package com.ai.client.skill;

import com.ai.Chatai;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * 技能（Skill）注册表。
 *
 * <p>技能放在 {@code config/chatai/skills/} 下，两种写法都可以：</p>
 * <ul>
 *     <li>{@code config/chatai/skills/<技能名>/SKILL.md}（推荐）；</li>
 *     <li>{@code config/chatai/skills/<技能名>.md}。</li>
 * </ul>
 *
 * <p>文件开头可以用 YAML frontmatter 声明名字与描述：</p>
 *
 * <pre>
 * ---
 * name: translate
 * description: 把文本翻译成指定语言
 * ---
 * 这里写技能正文，启用后会整段注入系统提示词。
 * </pre>
 *
 * <p>没写 frontmatter 时，用目录名 / 文件名当名字，正文第一行非空文本当描述。</p>
 *
 * <p>如果是「SKILL.md + 附属 md」的多文件技能（例如 {@code references/*.md}），
 * 技能目录下所有其它 {@code .md} 会合并到技能正文后面一起注入；
 * 模型在游戏里没法自己读文件，所以只能一次性带上。</p>
 */
public final class SkillRegistry {

    /** 一个技能：名字、一句话描述、正文。 */
    public record Skill(String name, String description, String body, Path file) {
    }

    private static final String FILE_NAME = "SKILL.md";

    private static List<Skill> skills = new ArrayList<>();
    private static boolean scanned;

    private SkillRegistry() {
    }

    /** 技能根目录。 */
    public static Path directory() {
        return FabricLoader.getInstance().getConfigDir().resolve("chatai").resolve("skills");
    }

    /** 技能根目录，不存在时自动创建，省得手动建。 */
    public static Path ensureDirectory() {
        Path dir = directory();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            Chatai.LOGGER.warn("创建技能目录失败 {}: {}", dir, e.toString());
        }
        return dir;
    }

    /** 全部技能；首次访问时自动扫描一次。 */
    public static List<Skill> skills() {
        if (!scanned) {
            reload();
        }
        return skills;
    }

    /** 重新扫描技能目录；界面打开技能面板时调用，方便新放进来的技能立刻可见。 */
    public static void reload() {
        scanned = true;
        List<Skill> found = new ArrayList<>();
        Path dir = ensureDirectory();
        if (Files.isDirectory(dir)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
                for (Path child : stream) {
                    if (Files.isDirectory(child)) {
                        Path file = child.resolve(FILE_NAME);
                        if (Files.isRegularFile(file)) {
                            read(file, child.getFileName().toString(), child, found);
                        }
                    } else if (isMarkdown(child)) {
                        read(child, stripExtension(child.getFileName().toString()), null, found);
                    }
                }
            } catch (IOException e) {
                Chatai.LOGGER.warn("扫描技能目录失败: {}", e.toString());
            }
        }
        found.sort(Comparator.comparing(Skill::name, String.CASE_INSENSITIVE_ORDER));
        skills = found;
    }

    /** 按名字查找技能；找不到返回 null。 */
    public static Skill byName(String name) {
        if (name == null) {
            return null;
        }
        for (Skill skill : skills()) {
            if (skill.name().equalsIgnoreCase(name)) {
                return skill;
            }
        }
        return null;
    }

    private static boolean isMarkdown(Path path) {
        return Files.isRegularFile(path)
                && path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".md");
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static void read(Path file, String fallbackName, Path companionDir, List<Skill> out) {
        String raw;
        try {
            raw = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Chatai.LOGGER.warn("读取技能文件失败 {}: {}", file, e.toString());
            return;
        }

        String name = fallbackName;
        String description = "";
        String body = raw;

        String[] lines = raw.split("\r?\n", -1);
        if (lines.length > 1 && lines[0].trim().equals("---")) {
            int end = -1;
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].trim().equals("---")) {
                    end = i;
                    break;
                }
            }
            if (end > 0) {
                for (int i = 1; i < end; i++) {
                    int colon = lines[i].indexOf(':');
                    if (colon <= 0) {
                        continue;
                    }
                    String key = lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String value = unquote(lines[i].substring(colon + 1).trim());
                    if (value.isEmpty()) {
                        continue;
                    }
                    if (key.equals("name")) {
                        name = value;
                    } else if (key.equals("description")) {
                        description = value;
                    }
                }
                body = String.join("\n", Arrays.asList(lines).subList(end + 1, lines.length)).trim();
            }
        }

        if (description.isEmpty()) {
            for (String line : body.split("\r?\n")) {
                String text = line.trim();
                if (!text.isEmpty()) {
                    description = text;
                    break;
                }
            }
        }
        if (description.isEmpty()) {
            description = "(无描述)";
        }
        if (companionDir != null) {
            body = mergeCompanions(body, companionDir, file);
        }
        out.add(new Skill(name, description, body, file));
    }

    /**
     * 把技能目录下所有附属 {@code .md} 合并到正文后面（递归，主文件除外）。
     *
     * <p>排序：正文里引用到的排前面（按首次出现位置），其余按相对路径字母序。合并段带
     * {@code [附属文件：相对路径]} 标记，方便模型对应。</p>
     */
    private static String mergeCompanions(String body, Path dir, Path mainFile) {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(SkillRegistry::isMarkdown)
                    .filter(path -> !path.equals(mainFile))
                    .forEach(files::add);
        } catch (IOException e) {
            Chatai.LOGGER.warn("扫描技能附属文件失败 {}: {}", dir, e.toString());
            return body;
        }
        if (files.isEmpty()) {
            return body;
        }
        files.sort(Comparator
                .comparingInt((Path path) -> referenceRank(body, dir, path))
                .thenComparing(path -> dir.relativize(path).toString(), String.CASE_INSENSITIVE_ORDER));

        StringBuilder merged = new StringBuilder(body);
        for (Path path : files) {
            String content;
            try {
                content = Files.readString(path, StandardCharsets.UTF_8).trim();
            } catch (IOException e) {
                continue;
            }
            if (content.isEmpty()) {
                continue;
            }
            String relative = dir.relativize(path).toString().replace('\\', '/');
            merged.append("\n\n[附属文件：").append(relative).append("]\n").append(content);
        }
        return merged.toString();
    }

    /** 附属文件在正文里首次出现的位置；没被引用返回 {@link Integer#MAX_VALUE}。 */
    private static int referenceRank(String body, Path dir, Path file) {
        int rank = Integer.MAX_VALUE;
        String relative = dir.relativize(file).toString().replace('\\', '/');
        int byPath = body.indexOf(relative);
        if (byPath >= 0) {
            rank = byPath;
        }
        int byName = body.indexOf(file.getFileName().toString());
        if (byName >= 0 && byName < rank) {
            rank = byName;
        }
        return rank;
    }

    private static String unquote(String value) {
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
