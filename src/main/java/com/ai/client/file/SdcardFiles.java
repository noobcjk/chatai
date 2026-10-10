package com.ai.client.file;

import com.ai.client.config.ChataiConfig;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 直接用 App 自己的身份读写工作区（默认 {@code /sdcard}），不经过 rish / Shizuku / Termux。
 *
 * <p>宿主启动器（FCL、Pojav 等）的 manifest 里带了 {@code MANAGE_EXTERNAL_STORAGE}，在系统里授予
 * 「所有文件访问」之后，App 进程就能读写外置存储的大部分目录。比起借 shell（uid 2000）去绕一圈，
 * 这条路的优势是不依赖 Shizuku（不会被系统掐断）、不依赖 Termux、无跨进程等待，毫秒级返回。</p>
 *
 * <p>边界：只能碰「工作区」内的路径（默认 {@code /sdcard}，见
 * {@link com.ai.client.config.ChataiConfig#workspace}）；{@code /data/data/<其它应用>}、
 * {@code /system} 这类仍然要 shell 身份才能进。路径一律规范化（解掉符号链接和 {@code ..}）
 * 后再校验，防止穿越到工作区之外。</p>
 */
public final class SdcardFiles {

    /** 一次文件操作的结果。 */
    public record Result(boolean ok, String text) {
    }

    /** 外置存储白名单；工作区本身必须落在其中之一，避免被设成 {@code /} 越出沙箱。 */
    private static final List<String> ROOTS = List.of(
            "/sdcard", "/storage/emulated/0", "/storage/self/primary");

    /** 工作区默认值：配置留空 / 非法时回退到这里。 */
    private static final String DEFAULT_WORKSPACE = "/sdcard";

    /** 单次读回的最大字节数 / 字符数。 */
    private static final int MAX_READ = 16000;
    /** 列目录最多返回多少条。 */
    private static final int MAX_ENTRIES = 400;

    private SdcardFiles() {
    }

    /** 按 op 分发。{@code op} 为 list / read / write / append / mkdir / delete。 */
    public static Result run(String op, String path, String content) {
        String action = op == null ? "" : op.trim().toLowerCase();
        try {
            Path target = resolve(path);
            return switch (action) {
                case "list", "ls", "dir" -> list(target);
                case "read", "cat" -> read(target);
                case "write" -> write(target, content, false);
                case "append" -> write(target, content, true);
                case "mkdir" -> mkdir(target);
                case "delete", "rm" -> delete(target);
                default -> new Result(false, "不支持的 op：" + op + "。可用：list、read、write、append、mkdir、delete。");
            };
        } catch (SecurityException e) {
            return new Result(false, "被系统拒绝访问：" + e.getMessage() + "\n" + capability());
        } catch (IOException e) {
            return new Result(false, "操作失败：" + e.getMessage() + "\n" + capability());
        }
    }

    /**
     * 当前 App 对外置存储的实际权限，用来在失败时给出可操作的解释。
     */
    public static String capability() {
        return managerProbe() + "；" + writeProbe() + "；工作区=" + workspace();
    }

    // ------------------------------------------------------------------
    // 各操作
    // ------------------------------------------------------------------

    private static Result list(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return new Result(false, "路径不存在：" + dir);
        }
        if (!Files.isDirectory(dir)) {
            return new Result(false, dir + " 不是目录，用 read 读它。");
        }
        List<Path> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .limit(MAX_ENTRIES + 1L)
                    .forEach(entries::add);
        }
        if (entries.isEmpty()) {
            return new Result(true, dir + " 是空目录。");
        }
        boolean more = entries.size() > MAX_ENTRIES;
        StringBuilder sb = new StringBuilder(dir.toString()).append(" 共 ").append(entries.size()).append(" 项：");
        int shown = 0;
        for (Path entry : entries) {
            if (shown++ >= MAX_ENTRIES) {
                break;
            }
            String name = entry.getFileName().toString();
            if (Files.isDirectory(entry)) {
                sb.append('\n').append(name).append('/');
            } else {
                sb.append('\n').append(name).append("  (").append(humanSize(sizeOf(entry))).append(')');
            }
        }
        if (more) {
            sb.append("\n…（条目过多，只列出前 ").append(MAX_ENTRIES).append(" 项）");
        }
        return new Result(true, sb.toString());
    }

    private static Result read(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new Result(false, "文件不存在：" + path);
        }
        if (Files.isDirectory(path)) {
            return new Result(false, path + " 是目录，用 list。");
        }
        long size = sizeOf(path);
        byte[] head;
        try (InputStream in = Files.newInputStream(path)) {
            head = in.readNBytes(MAX_READ);
        }
        for (byte b : head) {
            if (b == 0) {
                return new Result(true, path + " 是二进制文件（" + humanSize(size) + "），不做文本展示。");
            }
        }
        String text = new String(head, StandardCharsets.UTF_8);
        if (text.length() > MAX_READ) {
            text = text.substring(0, MAX_READ);
        }
        if (size > head.length) {
            text = text + "\n…（已截断，文件共 " + humanSize(size) + "）";
        }
        return new Result(true, text.isEmpty() ? "（空文件）" : text);
    }

    private static Result write(Path path, String content, boolean append) throws IOException {
        if (Files.isDirectory(path)) {
            return new Result(false, path + " 是目录，不能写。");
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        byte[] data = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if (append) {
            Files.write(path, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } else {
            Files.write(path, data, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        notifyMedia(path);
        return new Result(true, (append ? "已追加 " : "已写入 ") + data.length + " 字节到 " + path);
    }

    private static Result mkdir(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            return new Result(true, "目录已存在：" + path);
        }
        Files.createDirectories(path);
        // 再确认一次：某些系统会把写入重定向到别的目录，这时别对模型说"成功"
        if (!Files.isDirectory(path)) {
            return new Result(false, "建目录后原地找不到它，写入可能被系统重定向了：" + path);
        }
        notifyMedia(path);
        return new Result(true, "已创建目录 " + path);
    }

    private static Result delete(Path path) throws IOException {
        if (!Files.exists(path)) {
            return new Result(false, "路径不存在：" + path);
        }
        if (Files.isDirectory(path)) {
            try (Stream<Path> stream = Files.list(path)) {
                if (stream.findAny().isPresent()) {
                    return new Result(false, path + " 不是空目录，先删掉里面的文件再删它。");
                }
            }
            Files.delete(path);
            notifyMedia(path);
            return new Result(true, "已删除空目录 " + path);
        }
        Files.delete(path);
        notifyMedia(path);
        return new Result(true, "已删除文件 " + path);
    }

    // ------------------------------------------------------------------
    // 路径与探测
    // ------------------------------------------------------------------

    /**
     * 把用户给的路径解析成工作区内的真实路径。
     *
     * <p>相对路径按「相对工作区」处理；绝对路径也必须在工作区内。用 {@code getCanonicalPath}
     * 解掉符号链接和 {@code ..}，再做边界校验——否则 {@code ../data/data/...} 这种就能穿出去。</p>
     */
    private static Path resolve(String raw) throws IOException {
        if (raw == null || raw.isBlank()) {
            throw new IOException("没给出路径。");
        }
        Path root = workspaceRoot();
        String text = raw.trim();
        Path candidate = text.startsWith("/") ? Paths.get(text) : root.resolve(text);
        String canonical = candidate.toFile().getCanonicalPath();
        String rootText = root.toFile().getCanonicalPath();
        if (canonical.equals(rootText) || canonical.startsWith(rootText + "/")) {
            return Paths.get(canonical);
        }
        throw new IOException("只能在工作区内操作（" + rootText + "），拒绝：" + canonical);
    }

    /**
     * 当前工作区，规范化后的绝对路径。
     *
     * <p>取配置里的 {@code workspace}，留空回退 {@link #DEFAULT_WORKSPACE}；再校验它落在外置存储
     * 白名单内，否则同样回退——避免被设成 {@code /} 把整个文件系统暴露给模型。</p>
     */
    public static Path workspaceRoot() throws IOException {
        String configured = ChataiConfig.get().workspace;
        String text = configured == null || configured.isBlank() ? DEFAULT_WORKSPACE : configured.trim();
        if (!text.startsWith("/")) {
            text = "/" + text;
        }
        String canonical = canonical(text);
        if (!underExternalRoot(canonical)) {
            canonical = canonical(DEFAULT_WORKSPACE);
        }
        return Paths.get(canonical);
    }

    /** 工作区的展示路径，供系统提示词与错误信息使用。 */
    public static String workspace() {
        String configured = ChataiConfig.get().workspace;
        return configured == null || configured.isBlank() ? DEFAULT_WORKSPACE : configured.trim();
    }

    private static String canonical(String path) throws IOException {
        return Paths.get(path).toFile().getCanonicalPath();
    }

    private static boolean underExternalRoot(String canonical) {
        for (String root : ROOTS) {
            if (canonical.equals(root) || canonical.startsWith(root + "/")) {
                return true;
            }
        }
        return false;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1L;
        }
    }

    private static String humanSize(long bytes) {
        if (bytes < 0) {
            return "大小未知";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    /**
     * 通知系统媒体库：这个路径变了。
     *
     * <p>直接走文件 API 建出来的文件和文件夹不会自动进媒体库索引，走 MediaStore 的文件管理器
     * 和 USB（MTP）视图就会「看不到」刚建的东西，得主动扫一下。纯反射，桌面环境下没有这些类，
     * 静默跳过，不影响文件本身。</p>
     */
    private static void notifyMedia(Path path) {
        try {
            Object app = Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            if (app == null) {
                return;
            }
            Class<?> context = Class.forName("android.content.Context");
            Class<?> listener = Class.forName("android.media.MediaScannerConnection$OnScanCompletedListener");
            Class.forName("android.media.MediaScannerConnection")
                    .getMethod("scanFile", context, String[].class, String[].class, listener)
                    .invoke(null, app, new String[]{path.toString()}, null, null);
        } catch (Throwable ignored) {
            // 非 Android 环境，或系统没有这个接口：不扫也不影响文件本身
        }
    }

    /** Android 11（API 30）起「所有文件访问」的开关状态；低版本没有这个接口。 */
    private static String managerProbe() {
        try {
            Class<?> environment = Class.forName("android.os.Environment");
            Object value = environment.getMethod("isExternalStorageManager").invoke(null);
            return "所有文件访问=" + (Boolean.TRUE.equals(value) ? "已授予" : "未授予");
        } catch (Throwable t) {
            return "所有文件访问=探测不到（Android 10 及以下无需该权限）";
        }
    }

    /** 直接往 /sdcard 根目录写一个探针文件再删掉，验证真实可写性。 */
    private static String writeProbe() {
        Path probe = Paths.get("/sdcard/.chatai_write_probe");
        try {
            Files.write(probe, "ok".getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(probe);
            return "实测外置存储可写=是";
        } catch (Throwable t) {
            return "实测外置存储可写=否（" + t.getClass().getSimpleName() + "）";
        }
    }
}
