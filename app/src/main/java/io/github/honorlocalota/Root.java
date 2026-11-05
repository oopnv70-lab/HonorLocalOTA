package io.github.honorlocalota;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 所有需要 root 的动作集中在这里。
 *
 * 唯一注意点：ProcessBuilder 必须 redirectErrorStream(true)，否则先读完 stdout
 * 再读 stderr 时，两端会互相阻塞在管道缓冲区上（su 的 stderr 通常也有内容）。
 */
final class Root {

    private Root() {
    }

    /** 执行一段 sh，返回合并后的输出，末尾附一行 exit=N。 */
    static String exec(String script) {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder("su", "-c", script);
            pb.redirectErrorStream(true);
            p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            int code = p.waitFor();
            sb.append("exit=").append(code).append('\n');
        } catch (Throwable t) {
            sb.append("执行失败：").append(t).append('\n');
        } finally {
            if (p != null) {
                p.destroy();
            }
        }
        return sb.toString();
    }

    /** 用 root 列一个目录的文件名（只一层）。 */
    static List<String> ls(String dir) {
        List<String> out = new ArrayList<>();
        String r = exec("ls -1 " + q(dir) + " 2>/dev/null");
        for (String line : r.split("\n")) {
            String n = line.trim();
            if (n.isEmpty() || n.startsWith("exit=") || n.startsWith("执行失败")) {
                continue;
            }
            out.add(n);
        }
        return out;
    }

    /** df 的可用字节数；解析不到返回 -1。 */
    static long availBytes(String path) {
        String r = exec("df -k " + q(path) + " | tail -1");
        for (String line : r.split("\n")) {
            String[] p = line.trim().split("\\s+");
            if (p.length < 5) {
                continue;
            }
            try {
                long kb = Long.parseLong(p[3]);
                if (kb > 0) {
                    return kb * 1024L;
                }
            } catch (NumberFormatException ignored) {
                // 继续找下一行
            }
        }
        return -1;
    }

    /** 单引号包裹，内部单引号按 POSIX 规则转义，路径含空格也不会散。 */
    static String q(String p) {
        return "'" + p.replace("'", "'\\''") + "'";
    }

    /** 人类可读的 GiB 字符串。 */
    static String gib(long bytes) {
        if (bytes < 0) {
            return "未知";
        }
        return String.format(java.util.Locale.US, "%.2f GiB", bytes / 1024.0 / 1024.0 / 1024.0);
    }
}