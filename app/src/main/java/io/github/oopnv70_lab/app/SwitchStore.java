package io.github.oopnv70_lab.app;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;

/**
 * 两个开关的读写。
 *
 * 这个类被两个进程同时使用：
 *   ① 模块自己的 App 进程（设置界面）—— 域 u:r:untrusted_app:s0
 *   ② com.hihonor.ouc 进程（hook 里读）—— 域 platform_app_all
 *
 * ── 两个进程的访问方式不一样，这是本类最关键的一点 ──
 *   从 /sys/fs/selinux/policy 里抽出的 local_ota_file 全部 allow 规则：
 *       adbd / platform_app_all / shell / update_engine
 *   只有这四个域。所以：
 *     · OUC 进程（platform_app_all）→ 直接读就行，有 read 权限。
 *     · 模块 App（untrusted_app）→ 不在名单里，连 getattr 都没有，
 *       直接读会静默失败、直接写会抛异常。
 *       必须走 RootShell（su 到 ksu 域）才能访问。
 *   早期版本只用了直接读写，结果就是「点开关永远保存失败、
 *   界面永远显示原样」——因为读和写两条路都被 SELinux 拦掉了。
 *   现在用 RootShell.selfNeedsRoot() 在运行时分流。
 *
 * 存储就是 Const.SWITCH_FILE 那个纯文本文件，格式 key=value。
 * 读不到的时候一律回落到默认值，
 * 这是刻意选择的：宁可多屏蔽一次，也不要在配置异常时把整台机器的
 * 自动更新重新放开——那正是用户装这个模块想避免的事。
 */
final class SwitchStore {

    private SwitchStore() {
    }

    // ─────────────── 屏蔽系统更新 ───────────────

    /** 读「屏蔽系统更新」开关。任何异常都返回默认值（true）。 */
    static boolean isBlockUpdate() {
        return readBool(Const.KEY_BLOCK_UPDATE, Const.DEFAULT_BLOCK_UPDATE);
    }

    /**
     * 写「屏蔽系统更新」开关。
     *
     * @return null 表示成功，否则是错误描述
     */
    static String setBlockUpdate(boolean on) {
        return writeKey(Const.KEY_BLOCK_UPDATE, on ? "true" : "false");
    }

    // ─────────────── 全量包 ───────────────

    /** 读「强制请求全量包」开关。任何异常都返回默认值（false）。 */
    static boolean isForceFullPackage() {
        return readBool(Const.KEY_FORCE_FULL_PACKAGE, Const.DEFAULT_FORCE_FULL_PACKAGE);
    }

    /**
     * 写「强制请求全量包」开关。
     *
     * @return null 表示成功，否则是错误描述
     */
    static String setForceFullPackage(boolean on) {
        return writeKey(Const.KEY_FORCE_FULL_PACKAGE, on ? "true" : "false");
    }

    /**
     * 「屏蔽更新当前是否真的生效」。
     *
     * 两个开关是互斥的：要拿全量包，就不能同时把更新提示压成
     * 「已是最新版本」——那用户根本看不到服务器回来的新版本，
     * 也就没有入口去点它。
     *
     * ── 为什么用「读时判定」而不是「打开全量包时自动关掉屏蔽」──
     *   自动关掉需要额外记住「这个 false 是程序替你关的」，
     *   否则用户关掉全量包之后没法恢复原状态，得再手动开一次。
     *   而且两个键写同一个文件，中间存在改写到一半的窗口。
     *   这里只是一个纯函数，无状态、无竞态，
     *   界面那边也不用改开关本身，只在旁边显示一句「已被覆盖」。
     */
    static boolean isBlockEffective() {
        return isBlockUpdate() && !isForceFullPackage();
    }

    /**
     * 把设置页那个红点的数据源清零（Settings.Secure 的 hn_new_system_update）。
     *
     * ── 为什么需要这一步 ──
     *   红点的读方是 com.android.settings：dashboard.r0 用 ContentObserver
     *   盯着这个键，值一变就刷新 SystemUpdatePreference 的角标。
     *   写方只有 OUC 的 r9.w1.V6()，模块靠 hook 它把值压成 0。
     *
     *   但 hook 只在「OUC 下次写」的时候生效。如果模块装好时这个键
     *   已经是 1（旧版本留下的，或者屏蔽开关关着的时候收到的更新），
     *   那么要等到 OUC 再检查一次更新才会被清零——用户在这段时间里
     *   依旧会看到红点，很容易误判成"没生效"。
     *   所以打开屏蔽开关时顺手清一次，让设置页立刻同步。
     *
     * ── 为什么要 root ──
     *   WRITE_SECURE_SETTINGS 是 signature|privileged 权限，
     *   本模块 App 是普通应用，写不了。走 su 调 settings 命令即可。
     *
     * ── 失败不算错 ──
     *   清不掉只是"红点晚一点消失"，hook 仍然会在 OUC 下次写入时压住它。
     *   所以这里不把失败上报成开关保存失败，只作为提示。
     *
     * @return null 表示已清零或本来就已是 0，否则是错误描述
     */
    static String clearRedDot() {
        String cur = RootShell.run("settings get secure " + Const.KEY_HN_NEW_SYSTEM_UPDATE).text;
        if (cur != null && cur.trim().equals(String.valueOf(Const.NO_NEW_UPDATE))) {
            return null;
        }
        RootShell.Out o = RootShell.run("settings put secure "
                + Const.KEY_HN_NEW_SYSTEM_UPDATE + " " + Const.NO_NEW_UPDATE);
        if (o.ok()) {
            return null;
        }
        return "红点未清零（" + o.text.trim() + "）";
    }

    /** 开关文件当前是否存在（按本进程能否看到判断，仅作界面参考）。 */
    static boolean exists() {
        try {
            if (RootShell.selfNeedsRoot()) {
                return RootShell.run("[ -f '" + Const.SWITCH_FILE + "' ] && echo 1").ok();
            }
            return new File(Const.SWITCH_FILE).exists();
        } catch (Throwable t) {
            return false;
        }
    }

    // ─────────────── 底层读写 ───────────────

    /**
     * 读一个布尔键，认 true/1/on/yes 与 false/0/off/no（大小写不敏感）。
     *
     * 键缺失或值不认识时返回 def，不抛异常。
     * 两个开关共用这一份，避免各写一遍解析逻辑导致行为不一致。
     */
    private static boolean readBool(String key, boolean def) {
        String v = readKey(key);
        if (v == null) {
            return def;
        }
        v = v.trim().toLowerCase();
        if ("0".equals(v) || "false".equals(v) || "off".equals(v) || "no".equals(v)) {
            return false;
        }
        if ("1".equals(v) || "true".equals(v) || "on".equals(v) || "yes".equals(v)) {
            return true;
        }
        return def;
    }

    /**
     * 从开关文件里取一个键。
     *
     * 不把整个文件解析成 Map：只需要一个键，逐行扫到就返回，
     * 少一次对象分配，也避免解析过程中的异常影响读取。
     */
    private static String readKey(String key) {
        String text = readAll();
        if (text == null) {
            return null;
        }
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (key.equals(line.substring(0, eq).trim())) {
                return line.substring(eq + 1).trim();
            }
        }
        return null;
    }

    /** 读整个文件。按进程域选择直接读还是走 su。失败返回 null。 */
    private static String readAll() {
        if (RootShell.selfNeedsRoot()) {
            return RootShell.read(Const.SWITCH_FILE);
        }
        FileInputStream in = null;
        try {
            File f = new File(Const.SWITCH_FILE);
            if (!f.exists() || !f.canRead()) {
                return null;
            }
            in = new FileInputStream(f);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * 写入一个键，保留文件里其它键。
     *
     * 整个文件一次性重写（先拼好完整内容再落盘），
     * 而不是原地改某一行：这样不存在「改到一半」的中间态，
     * 也就不会出现 OUC 侧读到半个文件从而回落到默认值的情况。
     */
    private static String writeKey(String key, String value) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 系统包投放模块开关。改完立刻生效，不需要重启「软件更新」。\n");

        // 已有的其它键原样保留
        String old = readAll();
        if (old != null) {
            for (String raw : old.split("\n")) {
                String t = raw.trim();
                if (t.isEmpty() || t.startsWith("#")) {
                    continue;
                }
                int eq = t.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                if (key.equals(t.substring(0, eq).trim())) {
                    continue;   // 这个键由下面统一写
                }
                sb.append(t).append('\n');
            }
        }
        sb.append(key).append('=').append(value).append('\n');

        return writeAll(sb.toString());
    }

    /** 整文件写入。按进程域选择直接写还是走 su。 */
    private static String writeAll(String content) {
        if (RootShell.selfNeedsRoot()) {
            return RootShell.write(Const.SWITCH_FILE, content);
        }
        // OUC 进程理论上也能写（策略给了 write_create），
        // 但它不该去写用户配置，这里仍然实现，避免两套语义分叉。
        java.io.FileOutputStream out = null;
        try {
            File dir = new File(Const.DST_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                return "目录不存在且创建失败：" + Const.DST_DIR;
            }
            File target = new File(Const.SWITCH_FILE);
            File tmp = new File(Const.SWITCH_FILE + ".tmp");
            out = new java.io.FileOutputStream(tmp);
            out.write(content.getBytes("UTF-8"));
            out.flush();
            out.close();
            out = null;
            if (!tmp.renameTo(target)) {
                java.io.FileOutputStream direct = new java.io.FileOutputStream(target);
                direct.write(content.getBytes("UTF-8"));
                direct.flush();
                direct.close();
                tmp.delete();
            }
            return null;
        } catch (Throwable t) {
            return String.valueOf(t);
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}