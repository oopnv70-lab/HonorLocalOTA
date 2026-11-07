package io.github.honorlocalota;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

/**
 * 以 root 身份执行一条脚本，只为本模块的两个开关服务。
 *
 * ── 为什么必须走 root（实测结论，不是推断）──
 *   开关文件在 /data/local_ota/，标签 u:object_r:local_ota_file:s0。
 *   从 /sys/fs/selinux/policy 里抽出的全部 allow 规则只有四个域：
 *       adbd / platform_app_all / shell / update_engine
 *   本模块 App 跑在 u:r:untrusted_app:s0:c104,c257,c512,c768，
 *   既不在其中，连 getattr 都没有权限，于是：
 *       · 写不进去  → 点开关只会「保存失败」
 *       · 读不出来  → 静默回落到默认值，界面永远显示同一个状态
 *   root 进程在 u:r:ksu:s0，可以写；写出来的文件属主 root、
 *   标签仍是 local_ota_file（目录的 type_transition 决定），
 *   platform_app_all 有 file read 权限，所以 OUC 侧照常读得到。
 *
 * ── 为什么内容用 base64 传 ──
 *   开关文件里带中文注释和换行，直接拼进 shell 命令要处理引号、
 *   换行、特殊字符的转义。base64 之后只有 A-Za-z0-9+/=，
 *   不需要任何转义，也就不会出现「命令拼错导致失败」这类问题。
 *
 * ── 为什么用 ProcessBuilder 而不是 Runtime.exec(String) ──
 *   Runtime.exec 会把整串再拆一次词，路径里一旦有空格就会被拆散。
 *   ProcessBuilder 传的是数组，参数原样进 su，不经过二次解析。
 */
final class RootShell {

    /** su 的实际路径。Android 上就是 /system/bin/su，KernelSU 会把它挂上去。 */
    private static final String SU = "/system/bin/su";

    /** 单条脚本的超时。su 正常在几十毫秒内返回，给足余量防卡死。 */
    private static final long TIMEOUT_MS = 8000;

    /** 执行结果。code 为 -1 表示没能启动 su 或超时。 */
    static final class Out {
        final int code;
        final String text;

        Out(int code, String text) {
            this.code = code;
            this.text = text;
        }

        boolean ok() {
            return code == 0;
        }
    }

    private RootShell() {
    }

    /**
     * 执行一条 sh 脚本（在 root 下）。
     *
     * 脚本里不要用单引号包裹含单引号的内容——base64 方案已经避开了这个需求。
     */
    static Out run(String script) {
        Process p = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(SU, "-c", script);
            pb.redirectErrorStream(true);
            p = pb.start();

            OutputStream stdin = p.getOutputStream();
            stdin.close();   // 不喂输入，避免 su 等 stdin 卡住

            final Process fp = p;
            Thread killer = new Thread(() -> {
                try {
                    Thread.sleep(TIMEOUT_MS);
                } catch (InterruptedException ignored) {
                    return;
                }
                try {
                    fp.destroy();
                } catch (Throwable ignored) {
                }
            });
            killer.setDaemon(true);
            killer.start();

            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            InputStream in = p.getInputStream();
            byte[] b = new byte[4096];
            int n;
            while ((n = in.read(b)) > 0) {
                buf.write(b, 0, n);
            }
            int code = p.waitFor();
            killer.interrupt();
            return new Out(code, new String(buf.toByteArray(), "UTF-8"));
        } catch (Throwable t) {
            return new Out(-1, String.valueOf(t));
        } finally {
            if (p != null) {
                try {
                    p.destroy();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 把内容写到 path（root 下），并把它改成 644。
     *
     * @return null 表示成功，否则是错误描述（含命令输出，便于定位）
     */
    static String write(String path, String content) {
        String b64;
        try {
            b64 = base64(content.getBytes("UTF-8"));
        } catch (Throwable t) {
            return "编码失败：" + t;
        }
        // 目录可能还不存在（正常情况 init 会建好，这里兜底）
        String dir = path.substring(0, path.lastIndexOf('/'));
        String script = "mkdir -p '" + dir + "'"
                + " && echo '" + b64 + "' | base64 -d > '" + path + "'"
                + " && chmod 644 '" + path + "'";
        Out o = run(script);
        if (!o.ok()) {
            return "root 写入失败(code=" + o.code + ")：" + o.text.trim();
        }
        return null;
    }

    /**
     * 读 path 的内容（root 下）。
     *
     * @return 文件内容；失败返回 null
     */
    static String read(String path) {
        Out o = run("cat '" + path + "' 2>/dev/null");
        if (!o.ok() || o.text.isEmpty()) {
            return null;
        }
        return o.text;
    }

    /**
     * 当前进程是不是本模块自己的 App 进程（域 untrusted_app）。
     *
     * 用来自我判断该走哪条读路径：
     *   · 模块 App（untrusted_app）→ 必须走 su，否则读不到
     *   · OUC 进程（platform_app_all）→ 直接读，不该也不允许调 su
     *
     * 读 /proc/self/attr/current 是读进程自己的属性，untrusted_app 允许，
     * 不依赖任何额外权限，也不会被 SELinux 拦。
     */
    static boolean selfNeedsRoot() {
        FileInputStream in = null;
        try {
            in = new FileInputStream("/proc/self/attr/current");
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String s = br.readLine();
            return s != null && s.contains("untrusted_app");
        } catch (Throwable t) {
            // 读不到属性时按「需要 root」处理：
            // 走 su 只多一次几十毫秒的调用，走错方向则会导致静默读不到配置。
            return true;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** su 是否真的能用。界面上用来给出「没 root 就存不了」的明确提示。 */
    static boolean available() {
        Out o = run("id");
        return o.ok() && o.text.contains("uid=0");
    }

    // ─────────────── base64 ───────────────

    private static final char[] B64 =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    /**
     * 标准 base64 编码。
     *
     * 不用 android.util.Base64，也不用 java.util.Base64：
     * 前者在这个类被 OUC 进程加载时无所谓，但保持零依赖更省心；
     * 后者要 API 26+，虽然 minSdk 是 26，但自己写十几行更保险。
     */
    private static String base64(byte[] in) {
        StringBuilder sb = new StringBuilder((in.length + 2) / 3 * 4);
        int i = 0;
        while (i + 2 < in.length) {
            int v = ((in[i] & 0xFF) << 16) | ((in[i + 1] & 0xFF) << 8) | (in[i + 2] & 0xFF);
            sb.append(B64[(v >>> 18) & 0x3F]);
            sb.append(B64[(v >>> 12) & 0x3F]);
            sb.append(B64[(v >>> 6) & 0x3F]);
            sb.append(B64[v & 0x3F]);
            i += 3;
        }
        int rest = in.length - i;
        if (rest == 1) {
            int v = (in[i] & 0xFF) << 16;
            sb.append(B64[(v >>> 18) & 0x3F]);
            sb.append(B64[(v >>> 12) & 0x3F]);
            sb.append("==");
        } else if (rest == 2) {
            int v = ((in[i] & 0xFF) << 16) | ((in[i + 1] & 0xFF) << 8);
            sb.append(B64[(v >>> 18) & 0x3F]);
            sb.append(B64[(v >>> 12) & 0x3F]);
            sb.append(B64[(v >>> 6) & 0x3F]);
            sb.append('=');
        }
        return sb.toString();
    }
}