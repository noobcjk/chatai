package com.ai.client.shell;

import com.ai.client.config.ChataiConfig;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 通过 Termux 的 RUN_COMMAND 服务，把命令投递给 Termux 的 bash 执行。
 *
 * <p>链路：{@code rish -c 'am startservice ...'} → Android ActivityManagerService →
 * Termux 的 {@code RunCommandService}（{@code action=com.termux.RUN_COMMAND}）→
 * {@code bash -c '<命令>'}，以 Termux 自己的 uid 运行，跟 shell（uid 2000）不是一回事。</p>
 *
 * <p>Termux 的插件 API 本来要求调用方持有 {@code com.termux.permission.RUN_COMMAND}
 * 且 Termux 设置里打开「允许外部应用执行」。这里假定目标 App 已经改过源码跳过这两道校验。</p>
 *
 * <p>结果回传走「临时文件 + 结束标记」：命令的输出重定向到 Termux 自己外部目录下的一个文件，
 * 末尾追加带退出码的标记；再用 shell 身份轮询读取，读到标记就算结束。之所以不用 Intent 回传，
 * 是因为 {@code am startservice} 是一次性投递，调用方拿不到执行结果。</p>
 */
public final class TermuxRunner {

    /** 一次投递的结果。 */
    public record Result(boolean ok, String note, String output) {
    }

    /** Termux 的 RUN_COMMAND 服务类名；fork 一般不会改类名。 */
    private static final String SERVICE_CLASS = "com.termux.app.RunCommandService";
    private static final String ACTION = "com.termux.RUN_COMMAND";
    private static final String EXTRA_PATH = "com.termux.RUN_COMMAND_PATH";
    private static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION";
    /** 结束标记前缀。 */
    private static final String DONE = "__CHATAI_DONE_";
    /** 等结果的最长秒数，要留出 rish 自身 25s 超时的余量。 */
    private static final int WAIT_SECONDS = 20;
    /** 回给模型的输出上限。 */
    private static final int MAX_OUTPUT = 16000;

    private TermuxRunner() {
    }

    /** 在 Termux 里跑一条命令并等结果。 */
    public static Result run(String command) {
        if (command == null || command.isBlank()) {
            return new Result(false, "没有给出要执行的命令。", "");
        }
        ChataiConfig config = ChataiConfig.get();
        String pkg = config.termuxPackage == null ? "" : config.termuxPackage.trim();
        if (pkg.isEmpty()) {
            return new Result(false, "没配置 Termux 包名（termuxPackage）。", "");
        }
        String bash = config.termuxBash == null ? "" : config.termuxBash.trim();
        if (bash.isEmpty()) {
            return new Result(false, "没配置 Termux 里 bash 的路径（termuxBash）。", "");
        }

        // 每次用一个新文件名：复用同名文件会把上一次的结束标记当成本次结果
        String out = "/sdcard/Android/data/" + pkg + "/files/chatai/out_" + System.currentTimeMillis() + ".log";
        String dir = out.substring(0, out.lastIndexOf('/'));
        // 输出落到 Termux 自己的外部目录：它能写，shell（uid 2000）也能读
        String script = termuxEnv(bash)
                + "mkdir -p " + quote(dir)
                + "; ( " + command.trim() + " ) > " + quote(out) + " 2>&1"
                + "; echo " + DONE + "$? >> " + quote(out);

        // 命令里可能含换行、引号、反斜杠、逗号、括号等；而 am 的 --esa 会「按逗号切分再对
        // 反斜杠转义」，这些字符都会被它改写，导致多行命令/源码被破坏。改用 base64 承载整段
        // 脚本：编码表只有 A-Za-z0-9+/=，不含逗号、引号、反斜杠，怎么解析都不会碰到；到了
        // Termux 再由 base64 -d 还原，原样交给 bash，多行与各种符号都能保留。
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_8));
        String arguments = "-c,echo " + encoded + " | base64 -d | bash";

        List<String> argv = new ArrayList<>(List.of(
                "am", "startservice",
                "-n", pkg + "/" + SERVICE_CLASS,
                "-a", ACTION,
                "--es", EXTRA_PATH, bash,
                "--esa", EXTRA_ARGUMENTS, arguments,
                "--ez", EXTRA_BACKGROUND, "true",
                "--ei", EXTRA_SESSION_ACTION, "0"));

        RishShell.Result launch = RishShell.run(List.of("-c", join(argv)));
        if (!launch.ok()) {
            return new Result(false, "投递到 Termux 失败（" + launch.note() + "）", launch.output().strip());
        }

        // 一次 rish 调用里等标记出现再整体 cat 回来，省得反复起 rish 进程
        String wait = "n=0; while [ $n -lt " + (WAIT_SECONDS * 2) + " ]; do "
                + "grep -q " + DONE + " " + quote(out) + " 2>/dev/null && break; "
                + "sleep 0.5; n=$((n+1)); done; "
                + "cat " + quote(out) + " 2>/dev/null; rm -f " + quote(out);
        RishShell.Result read = RishShell.run(List.of("-c", wait));
        String raw = read.output();

        int mark = raw.lastIndexOf(DONE);
        if (mark < 0) {
            String partial = raw.strip();
            return new Result(false, "等 Termux 回结果超时（" + WAIT_SECONDS + "s）"
                    + (partial.isEmpty() ? "" : "，已拿到的输出：\n" + truncate(partial)), partial);
        }
        int lineEnd = raw.indexOf('\n', mark);
        String code = raw.substring(mark + DONE.length(), lineEnd < 0 ? raw.length() : lineEnd).trim();
        String text = (raw.substring(0, mark) + (lineEnd < 0 ? "" : raw.substring(lineEnd + 1))).strip();

        boolean ok = "0".equals(code);
        String body = text.isEmpty() ? "（无输出）" : truncate(text);
        return new Result(ok, "exit=" + code, body);
    }

    private static String truncate(String text) {
        return text.length() <= MAX_OUTPUT ? text : text.substring(0, MAX_OUTPUT) + "\n…（输出过长，已截断）";
    }

    /** 把 argv 逐个单引号包起来再拼成一条命令，交给远端 sh 解析。 */
    private static String join(List<String> argv) {
        List<String> quoted = new ArrayList<>(argv.size());
        for (String arg : argv) {
            quoted.add(quote(arg));
        }
        return String.join(" ", quoted);
    }

    /** POSIX 单引号包裹：内部的单引号写成 {@code '\''}。 */
    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    /**
     * 补上 Termux 自己的环境变量。
     *
     * <p>RunCommandService 起的 bash 是「非登录、非交互」的，拿到的 PATH 往往是 Android 默认的
     * 那套（{@code /system/bin}…），里面没有 {@code $PREFIX/bin}——于是 clang、python、node 这些
     * 只在 Termux 里的命令会报 {@code command not found}，而用户在 Termux 交互界面里手敲却正常。</p>
     *
     * <p>前缀直接从配置的 bash 路径推出来：{@code <files>/usr/bin/bash} → PREFIX 为
     * {@code <files>/usr}、HOME 为 {@code <files>/home}。</p>
     */
    private static String termuxEnv(String bash) {
        // bash 形如 <files>/usr/bin/bash，先退两级拿 PREFIX，再退一级拿 <files>
        int binSlash = bash.lastIndexOf('/');
        if (binSlash < 0) {
            return "";
        }
        String binDir = bash.substring(0, binSlash);       // <files>/usr/bin
        int prefixSlash = binDir.lastIndexOf('/');
        if (prefixSlash < 0) {
            return "";
        }
        String prefix = binDir.substring(0, prefixSlash);  // <files>/usr
        int filesSlash = prefix.lastIndexOf('/');
        String files = filesSlash < 0 ? prefix : prefix.substring(0, filesSlash); // <files>
        return "export PREFIX=" + quote(prefix)
                + "; export HOME=" + quote(files + "/home")
                + "; export TMPDIR=" + quote(prefix + "/tmp")
                // PATH 用「引号包裹的字面量 + 未加引号的 $PATH」拼接，保证 $PATH 能被展开
                + "; export PATH=" + quote(prefix + "/bin:" + prefix + "/bin/applets:") + "$PATH"
                + "; ";
    }
}
