package io.github.oopnv70_lab.app;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 目标定位报告的落盘与解析。
 *
 * ══════════════════════════════════════════════════════════════
 * 为什么需要这个文件
 *   定位只能在「软件更新」进程里做——只有在那里，宿主的 APK 路径、
 *   ClassLoader、Class.forName 才是白送的。QQ 模块（QAuxiliary）也是
 *   这么做的，它拿 APK 路径就一行：
 *       DexKitBridge.create(hostInfo.application.applicationInfo.sourceDir)
 *   因为那行代码跑在被注入的 QQ 进程里，sourceDir 就是 QQ 自己的包。
 *
 *   模块自己的 App 跑在 untrusted_app 域，既没有宿主的 ClassLoader，
 *   也查不到别的应用的安装信息（Android 11+ 包可见性过滤）。
 *   但用户要看结果的地方偏偏就在模块 App 里。
 *
 *   两个进程之间就需要一个通道，这里选最笨也最稳的一种：纯文本文件。
 *
 * ── 为什么放 /data/local_ota ──
 *   这个目录的 SELinux 标签是 local_ota_file，OUC 进程
 *   （platform_app_all 域）有读写权限，模块 App 走 root 也能读。
 *   是本模块已经在用的通道（module_switch.conf / targets.conf 都在这），
 *   复用它不需要新增任何授权，也不需要改 init 或挂载。
 *
 * ── 和 targets.conf 的区别 ──
 *   targets.conf 是解析缓存，只存「类名/方法名/参数」，供下次启动复用，
 *   不关心这次是怎么找到的。
 *   本文件是「这一次的体检报告」，额外记录来源（dexkit/cache/hardcoded）
 *   和 APK 指纹，用来回答「换版本、换设备后还定位得到吗」这个问题。
 *   两者用途不同，故意分开：删掉本文件不影响功能，只会让界面
 *   少一份可读的报告。
 *
 * 文件格式（一行一条，故意做成能直接 cat 出来看的样子）
 *   # apk=<包名>:<APK字节数>_<修改时间>
 *   # at=<写入时刻毫秒>
 *   <目标id>|<类名>|<方法名>|<来源>|<参数类型逗号分隔>
 */
final class TargetReport {

    /** 报告文件名。 */
    static final String FILE_NAME = "target_probe.conf";

    /** 完整路径。 */
    static final String PATH = Const.DST_DIR + "/" + FILE_NAME;

    private TargetReport() {
    }

    /** 解析出来的报告。 */
    static final class Parsed {
        /** 按文件里的顺序保存 id → 结果。 */
        final Map<String, TargetResolver.Resolved> results = new LinkedHashMap<>();
        /** 写入时宿主 APK 的指纹；null 表示文件里没写。 */
        String apkTag;
        /** 写入时刻毫秒；0 表示文件里没写。 */
        long at;

        boolean hasResults() {
            return !results.isEmpty();
        }
    }

    /**
     * 编码。
     *
     * 按 HookTarget.ALL 的顺序输出，这样 cat 出来的顺序和界面上完全一致，
     * 排查时不需要在两处来回对照。
     */
    static String encode(String apkTag, Map<String, TargetResolver.Resolved> results) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 软件更新增强：目标定位报告（由模块在「软件更新」进程内写入）\n");
        sb.append("# 格式：<目标id>|<类名>|<方法名>|<来源>|<参数类型>\n");
        sb.append("# apk=").append(apkTag == null ? "unknown" : apkTag).append('\n');
        sb.append("# at=").append(System.currentTimeMillis()).append('\n');
        if (results != null) {
            for (HookTarget t : HookTarget.ALL) {
                TargetResolver.Resolved r = results.get(t.id);
                if (r == null) {
                    continue;
                }
                sb.append(t.id).append('|')
                        .append(nz(r.className)).append('|')
                        .append(nz(r.methodName)).append('|')
                        .append(nz(r.source)).append('|')
                        .append(join(r.paramTypes)).append('\n');
            }
        }
        return sb.toString();
    }

    /**
     * 解码。
     *
     * 任何一行坏了就跳过那一行，绝不因为格式问题让整份报告失效——
     * 这份文件是「给人看的证据」，不是程序状态的唯一来源。
     */
    static Parsed decode(String text) {
        Parsed p = new Parsed();
        if (text == null) {
            return p;
        }
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                if (line.startsWith("# apk=")) {
                    p.apkTag = line.substring("# apk=".length()).trim();
                } else if (line.startsWith("# at=")) {
                    try {
                        p.at = Long.parseLong(line.substring("# at=".length()).trim());
                    } catch (Throwable ignored) {
                        // 时间戳坏了不影响结论，忽略。
                    }
                }
                continue;
            }
            String[] f = line.split("\\|", 5);
            if (f.length < 4) {
                continue;
            }
            p.results.put(f[0], new TargetResolver.Resolved(
                    emptyToNull(f[1]),
                    emptyToNull(f[2]),
                    f.length > 4 ? split(f[4]) : new String[0],
                    f[3]));
        }
        return p;
    }

    // ──────────────────────────────────────────────────────────────

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String join(String[] a) {
        if (a == null || a.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(a[i] == null ? "" : a[i]);
        }
        return sb.toString();
    }

    private static String[] split(String s) {
        if (s == null || s.isEmpty()) {
            return new String[0];
        }
        return s.split(",", -1);
    }
}
