package io.github.honorlocalota;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

/**
 * 「屏蔽系统更新」开关的读写。
 *
 * 这个类被两个进程同时使用，必须零依赖、零 Context 依赖：
 *   ① 模块自己进程 —— 设置界面里切换开关时写
 *   ② com.hihonor.ouc 进程 —— hook l5.b.p() 时读，决定要不要压标志
 *
 * 存储就是 Const.SWITCH_FILE 那个纯文本文件，格式 `key=value`。
 * 双方都能直接读写，不需要 root（原因见 Const.SWITCH_FILE 的注释）。
 *
 * 读不到的时候一律回落到默认值（true = 屏蔽开启），
 * 这是刻意选择的：宁可多屏蔽一次，也不要在配置异常时把整台机器的
 * 自动更新重新放开——那正是用户装这个模块想避免的事。
 */
final class SwitchStore {

    private SwitchStore() {
    }

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

    /** 开关文件当前是否存在。界面上用来区分「从没设置过」和「用户设过」。 */
    static boolean exists() {
        try {
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
        FileInputStream in = null;
        try {
            File f = new File(Const.SWITCH_FILE);
            if (!f.exists() || !f.canRead()) {
                return null;
            }
            in = new FileInputStream(f);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
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
     * 先写临时文件再 rename，避免写一半断电留下残缺文件——
     * 那会让 OUC 进程读到空文件从而回落到默认值，
     * 表现为「用户明明关掉了屏蔽，更新提示还是被压掉」。
     */
    private static String writeKey(String key, String value) {
        FileOutputStream out = null;
        try {
            File dir = new File(Const.DST_DIR);
            if (!dir.exists() && !dir.mkdirs()) {
                return "目录不存在且创建失败：" + Const.DST_DIR;
            }
            File target = new File(Const.SWITCH_FILE);
            File tmp = new File(Const.SWITCH_FILE + ".tmp");

            StringBuilder sb = new StringBuilder();
            sb.append("# 系统包投放模块开关。改完立刻生效，不需要重启「软件更新」。\n");
            // 已有的其它键原样保留
            FileInputStream old = null;
            if (target.exists()) {
                try {
                    old = new FileInputStream(target);
                    BufferedReader br = new BufferedReader(new InputStreamReader(old, "UTF-8"));
                    String line;
                    while ((line = br.readLine()) != null) {
                        String t = line.trim();
                        if (t.isEmpty() || t.startsWith("#")) {
                            continue;
                        }
                        int eq = t.indexOf('=');
                        if (eq <= 0) {
                            continue;
                        }
                        if (key.equals(t.substring(0, eq).trim())) {
                            continue;  // 这个键由下面统一写
                        }
                        sb.append(t).append('\n');
                    }
                } catch (Throwable ignored) {
                } finally {
                    if (old != null) {
                        try {
                            old.close();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
            sb.append(key).append('=').append(value).append('\n');

            out = new FileOutputStream(tmp);
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
            out.close();
            out = null;

            if (!tmp.renameTo(target)) {
                // 某些 fuse 挂载上 rename 会失败，退回直接覆盖写
                FileOutputStream direct = new FileOutputStream(target);
                direct.write(sb.toString().getBytes("UTF-8"));
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