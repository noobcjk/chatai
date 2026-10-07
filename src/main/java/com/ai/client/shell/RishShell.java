package com.ai.client.shell;

import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * rish（Shizuku 提供的 shell 入口）的释放与调用，目前只服务于 {@code /getshell} 测试。
 *
 * <p>rish 由两个文件组成：一个 {@code /system/bin/sh} 脚本 + 同目录的
 * {@code rish_shizuku.dex}。脚本内部再调 {@code app_process} 把 dex 拉起来。所以调用时
 * 被 {@code execve} 的是系统里的 {@code sh}，脚本本身只是被「读取」，绕开了 Android 10+
 * 对「可写目录里的文件不能执行」的限制。</p>
 *
 * <p>Android 14+ 起 {@code app_process} 拒绝加载可写的 dex，脚本会尝试 {@code chmod 400}。
 * 外置存储（/sdcard）上 chmod 是空操作，所以优先释放到 App 私有目录；只有私有目录里
 * 才能真正去掉写权限。</p>
 */
public final class RishShell {

    /** 一次命令的执行结果。 */
    public record Result(boolean ok, String note, String output) {
    }

    private static final String ASSET_DIR = "/assets/chatai/rish/";
    private static final String SCRIPT = "rish";
    private static final String DEX = "rish_shizuku.dex";
    /** 单条命令最长等待时间。 */
    private static final long TIMEOUT_SECONDS = 25L;

    private static Path workDir;
    private static String packageName;

    private RishShell() {
    }

    /**
     * 把 rish + dex 释放到第一个可用目录并返回它。
     *
     * <p>「可用」的判定标准：能建目录、能写入、能读出非空的 dex。</p>
     */
    public static synchronized Path ensure() throws IOException {
        if (workDir != null) {
            return workDir;
        }
        IOException last = null;
        for (Path candidate : candidates()) {
            try {
                prepare(candidate);
                workDir = candidate;
                return workDir;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("没有可用的释放目录") : last;
    }

    /** App 私有目录优先；外置 config 目录只作兜底。 */
    private static List<Path> candidates() {
        List<Path> list = new ArrayList<>();
        String pkg = packageName();
        if (pkg != null) {
            list.add(Path.of("/data/user/0", pkg, "files", "chatai", "rish"));
            list.add(Path.of("/data/data", pkg, "files", "chatai", "rish"));
        }
        list.add(FabricLoader.getInstance().getConfigDir().resolve("chatai").resolve("rish"));
        return list;
    }

    /** 当前 App 的包名；识别不到返回 null。 */
    public static synchronized String packageName() {
        if (packageName != null) {
            return packageName;
        }
        // 1) 启动器把游戏目录放在 /storage/emulated/0/Android/data/<包名>/files/... 时的常见情形
        Pattern pattern = Pattern.compile("/Android/data/([^/]+)/");
        for (Path base : new Path[]{FabricLoader.getInstance().getGameDir(),
                FabricLoader.getInstance().getConfigDir()}) {
            Matcher matcher = pattern.matcher(base.toAbsolutePath().toString().replace('\\', '/'));
            if (matcher.find()) {
                packageName = matcher.group(1);
                return packageName;
            }
        }
        // 2) 退而求其次：进程名就是包名（Android 会给 App 主进程设置）
        for (String pid : new String[]{"self", parentPid()}) {
            if (pid == null) {
                continue;
            }
            String name = readCmdline(pid);
            if (name != null && name.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")) {
                packageName = name;
                return packageName;
            }
        }
        return null;
    }

    private static String parentPid() {
        try {
            String stat = Files.readString(Path.of("/proc/self/stat"), StandardCharsets.UTF_8);
            // 形如 "1234 (java) S 5678 ..."；进程名可能带空格，所以取最后一个 ')' 之后再切
            int close = stat.lastIndexOf(')');
            if (close < 0) {
                return null;
            }
            String[] rest = stat.substring(close + 1).trim().split("\\s+");
            return rest.length >= 2 ? rest[1] : null;
        } catch (IOException e) {
            return null;
        }
    }

    private static String readCmdline(String pid) {
        try {
            byte[] raw = Files.readAllBytes(Path.of("/proc", pid, "cmdline"));
            String text = new String(raw, StandardCharsets.UTF_8).replace('\0', ' ').trim();
            int space = text.indexOf(' ');
            return space < 0 ? text : text.substring(0, space);
        } catch (IOException e) {
            return null;
        }
    }

    private static void prepare(Path dir) throws IOException {
        Files.createDirectories(dir);

        extract(ASSET_DIR + SCRIPT, dir.resolve(SCRIPT));

        Path dex = dir.resolve(DEX);
        // 上一轮可能把 dex 锁成只读了，重写前先解锁
        makeWritable(dex);
        extract(ASSET_DIR + DEX, dex);

        // Android 14+ 才要求 dex 不可写；12/13 可写也能加载，所以这里只尽力而为，不算失败
        makeReadOnly(dex);
        if (Files.size(dex) <= 0) {
            throw new IOException("释放出来的 dex 是空的：" + dex);
        }
    }

    private static void extract(String resource, Path target) throws IOException {
        try (InputStream in = RishShell.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("mod 里缺少资源 " + resource);
            }
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void makeWritable(Path path) {
        File file = path.toFile();
        file.setReadable(true, true);
        file.setWritable(true, true);
    }

    private static void makeReadOnly(Path path) {
        File file = path.toFile();
        file.setReadable(true, true);
        file.setWritable(false, true);
    }

    /**
     * 执行一条命令。
     *
     * <p>rish 的用法是「把 {@code sh} 换成 {@code rish}」，所以要写成 {@code rish -c '<命令>'}
     * ——等价于远端执行 {@code /system/bin/sh -c '<命令>'}。直接传 {@code rish id} 会变成远端
     * {@code /system/bin/sh id}，被当成脚本文件名。</p>
     */
    public static Result runCommand(String command) {
        return run(List.of("-c", command));
    }

    /**
     * 以 shell 身份跑 rish，参数原样透传。
     *
     * @param args 传给 rish 自身的参数，例如 {@code ["-c", "id"]}
     */
    public static Result run(List<String> args) {
        return runRish(args, false);
    }

    /** 带 {@code sh -x} 跟踪的 rish，卡住时用来看脚本停在哪一行。 */
    public static Result runTraced(List<String> args) {
        return runRish(args, true);
    }

    /** 本地 {@code sh -c}，不经过 rish，作为「我们能不能拉起 shell」的对照组。 */
    public static Result runLocal(String command) {
        return exec(List.of("/system/bin/sh", "-c", command), null);
    }

    private static Result runRish(List<String> args, boolean trace) {
        Path dir;
        try {
            dir = ensure();
        } catch (IOException e) {
            return new Result(false, "释放 rish 失败：" + e.getMessage(), "");
        }

        List<String> command = new ArrayList<>();
        command.add("/system/bin/sh");
        if (trace) {
            command.add("-x");
        }
        command.add(dir.resolve(SCRIPT).toString());
        command.addAll(args);
        return exec(command, dir);
    }

    /**
     * 跑一个进程，把 stdout / stderr 合起来收回去。
     *
     * <p>必须另开一条线程读流、再 {@code waitFor}：直接 {@code readAllBytes()} 的话，
     * 子进程一卡住读流就永远阻塞，超时判断根本轮不到执行。</p>
     *
     * @param workDir 非空时把进程工作目录切过去，并注入 {@code RISH_APPLICATION_ID}
     */
    private static Result exec(List<String> command, Path workDir) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        // 启动器给 JVM 设的库路径会让 app_process 加载到不兼容的 so，这里去掉
        builder.environment().remove("LD_LIBRARY_PATH");
        if (workDir != null) {
            builder.directory(workDir.toFile());
            String pkg = packageName();
            if (pkg != null) {
                builder.environment().put("RISH_APPLICATION_ID", pkg);
            }
        }

        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return new Result(false, "启动失败：" + e, "");
        }

        StringBuilder sink = new StringBuilder();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    synchronized (sink) {
                        sink.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException ignored) {
                // 进程被强杀时会抛，忽略
            }
        }, "chatai-rish-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                reader.join(1000);
                return new Result(false, "执行超时（" + TIMEOUT_SECONDS + "s）", snapshot(sink));
            }
            reader.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(false, "被中断", snapshot(sink));
        }

        int code = process.exitValue();
        return new Result(code == 0, "exit=" + code, snapshot(sink));
    }

    private static String snapshot(StringBuilder sink) {
        synchronized (sink) {
            return sink.toString();
        }
    }

    /** {@code /getshell} 的默认探针：看环境、看释放目录、看 shell 身份。 */
    public static String probe() {
        StringBuilder report = new StringBuilder();
        report.append("环境架构：").append(System.getProperty("os.arch")).append('\n');
        report.append("识别到的包名：").append(packageName() == null ? "(失败)" : packageName()).append('\n');

        Path dir;
        try {
            dir = ensure();
        } catch (IOException e) {
            return report.append("释放 rish：失败 → ").append(e.getMessage()).append('\n').toString();
        }
        report.append("释放目录：").append(dir).append('\n');
        report.append("dex 只读：").append(!Files.isWritable(dir.resolve(DEX))).append('\n');

        // 对照组：本地 sh，不经过 rish。能返回 App 自己的 uid，就说明我们拉起 shell 这条链路是通的
        Result local = runLocal("id");
        report.append("\n[对照] $ sh -c id  (").append(local.note()).append(")\n")
                .append(local.output().strip()).append('\n');

        Result id = runCommand("id");
        report.append("\n$ rish -c id  (").append(id.note()).append(")\n")
                .append(id.output().strip()).append('\n');

        if (id.ok() && id.output().contains("uid=2000")) {
            Result sdk = runCommand("getprop ro.build.version.sdk");
            report.append("\n$ rish -c 'getprop ro.build.version.sdk'  (").append(sdk.note()).append(")\n")
                    .append(sdk.output().strip()).append('\n');
            report.append("\n✅ rish 可用：已经拿到 shell（uid 2000）。");
            return report.toString();
        }

        // 没起来：再跑一次带 -x 的，轨迹能看出脚本停在哪一步
        Result trace = runTraced(List.of("-c", "id"));
        report.append("\n$ sh -x rish -c id  (").append(trace.note()).append(")\n")
                .append(trace.output().strip()).append('\n');
        report.append("\n❌ rish 没起来，把上面整段发我。");
        return report.toString();
    }
}
