package io.github.oopnv70_lab.app;

import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 触发一次「在软件更新进程内完成的目标定位」，并把结果取回来。
 *
 * ══════════════════════════════════════════════════════════════
 * 为什么必须在「软件更新」的进程里解析
 * ══════════════════════════════════════════════════════════════
 * 定位要喂给 DexKit 的是**宿主 APK 的路径**，还要用宿主的 ClassLoader
 * 复核类名是否真的存在。这两样东西只有在被注入的宿主进程里才是白送的：
 *
 *   QQ 模块（QAuxiliary）的 DexKitDeobfs 就一行：
 *       DexKitBridge.create(hostInfo.application.applicationInfo.sourceDir)
 *   sourceDir 是 QQ 自己的包路径，因为那行代码跑在 QQ 进程里。
 *
 * 模块自己的 App 跑在 untrusted_app 域，拿不到宿主的 ClassLoader；
 * 查 com.hihonor.ouc 的安装信息又会被 Android 11+ 的包可见性过滤挡住
 * （没声明 queries 就是 NameNotFoundException）。
 * 想在自己进程里解析，就得额外声明可见性、再造一份解析代码——那份代码
 * 还拿不到宿主 ClassLoader，复核能力天然后天不足。
 *
 * 所以走这条：**借住宿主进程解析，结果写文件，模块 App 读文件。**
 * 代价是必须先把「软件更新」启动一次，这个代价可以忽略。
 *
 * ── 为什么是「重启」而不是「启动」──
 *   hook 只在 OUC 进程启动时挂载；已经跑着的进程里 onPackageReady 早就
 *   跑完了，不会因为我们的请求再跑一遍。所以必须 force-stop 再 am start，
 *   让新进程重新走一遍 onPackageReady，解析才会真的发生。
 *
 * ── 进度怎么显示 ──
 *   am start 之后 OUC 会盖住本 App，界面上的进度条用户看不到，
 *   因此进度走系统浮窗（见 OverlayProgress）。
 *
 * ── 本类不碰 DexKit ──
 *   DexKit、libdexkit.so、TargetResolver 全都在 OUC 进程里跑
 *   （那边 OtaHook.resolveTargets 本来就有），这里只负责
 *   「踢一脚」+「等结果」+「把文件读成人能看的东西」。
 */
final class TargetProbe {

    private static final String TAG = "HonorLocalOTA";

    /** 等报告的最长时间。首次解析一个 29MB 的 APK 要数秒，留足余量。 */
    private static final long WAIT_MS = 120_000L;

    /** 进度回调。 */
    interface Progress {
        void onStep(String line);
    }

    /** 一次探测的结果。 */
    static final class Report {
        /** 按 HookTarget.ALL 顺序的定位结果。 */
        final Map<String, TargetResolver.Resolved> results = new LinkedHashMap<>();
        /** root 是否可用。 */
        boolean rootOk;
        /** 是否成功把「软件更新」重新拉起来。 */
        boolean restartOk;
        /** 拉起的结果描述（已重启 / 已启动 / 失败原因）。 */
        String restartDetail = "";
        /** 是否真的拿到了「软件更新」写出来的新报告。 */
        boolean reportFound;
        /** 报告里的宿主 APK 指纹。 */
        String apkTag;
        /** 报告写入时刻（毫秒）。0 表示没拿到。 */
        long at;
        /** 出过的错，非空时说明这一轮不完整。 */
        final List<String> errors = new ArrayList<>();

        /** 有几个目标是真正定位到的。 */
        int resolvedCount() {
            int n = 0;
            for (TargetResolver.Resolved r : results.values()) {
                if (r.className != null) {
                    n++;
                }
            }
            return n;
        }

        /** 拿到某个目标的结果，没有则 null。 */
        TargetResolver.Resolved get(String id) {
            return results.get(id);
        }

        /** 一句话概括，给界面顶部的状态行用。 */
        String summary() {
            if (!rootOk) {
                return "没有 root 权限，无法启动「软件更新」的进程";
            }
            if (!restartOk) {
                return "启动「软件更新」失败：" + restartDetail;
            }
            if (!reportFound) {
                return "「软件更新」没有写出解析结果";
            }
            return "已定位 " + resolvedCount() + " / " + results.size()
                    + " 个目标" + (at > 0 ? "（" + ago(at) + "）" : "");
        }
    }

    private TargetProbe() {
    }

    /**
     * 完整流程：起进程 → 等报告 → 读回来。
     *
     * 必须在后台线程调用：整个过程 10~60 秒，主线程必然 ANR。
     * 任何一步失败都不抛异常，把原因写进 Report.errors，
     * 让界面能一字一句告诉用户卡在哪。
     */
    static Report probe(Context ctx, Progress p) {
        Report rep = new Report();

        // ── 一、root ──
        step(p, "检查 root 权限…");
        rep.rootOk = RootShell.available();
        if (!rep.rootOk) {
            rep.errors.add("没有 root 权限。本模块要读 /data/local_ota/ 下的文件，"
                    + "而该目录只对 adbd / platform_app_all / shell / update_engine 开放，"
                    + "模块 App 是 untrusted_app，必须经 su 访问。");
            return rep;
        }

        // ── 二、记下旧报告的写入时刻 ──
        // 后面用它判断「这份报告是不是这次新写的」。只看文件存不存在不行：
        // 上次解析留下的旧文件会让人以为这次成功了。
        long beforeAt = 0;
        try {
            TargetReport.Parsed old = TargetReport.decode(RootShell.read(TargetReport.PATH));
            beforeAt = old.at;
            if (beforeAt > 0) {
                Log.i(TAG, "已有旧报告，写入时刻 " + beforeAt + "，本次要求更新的报告");
            }
        } catch (Throwable t) {
            Log.w(TAG, "读旧报告失败（忽略，按无旧报告处理）: " + t);
        }

        // ── 三、重启「软件更新」，让 onPackageReady 重新跑一遍 ──
        step(p, "启动「软件更新」…");
        OucRestarter.Result rr = OucRestarter.restart();
        rep.restartOk = rr.ok;
        rep.restartDetail = rr.detail == null ? "" : rr.detail;
        if (!rr.ok) {
            rep.errors.add("启动「软件更新」失败：" + rep.restartDetail);
            return rep;
        }
        step(p, rr.detail + "，等待进程内解析（首次要读 APK 里的 dex，请稍候）…");

        // ── 四、等新报告 ──
        // 等待放在 shell 里做，而不是在这边循环 fork su：
        // 每轮 fork 一个 su 有几十上百毫秒开销，两分钟下来要 fork 上百次，
        // 日志被刷满，还可能触发 su 管理器的高频调用提示。
        // 一条 su 脚本内部按秒轮询，只 fork 一次。
        String script = ""
                + "P=" + TargetReport.PATH + "\n"
                + "B=" + beforeAt + "\n"
                + "i=0\n"
                + "while [ $i -lt " + (WAIT_MS / 1000) + " ]; do\n"
                + "  if [ -r \"$P\" ]; then\n"
                + "    A=$(sed -n 's/^# at=//p' \"$P\" | head -n 1)\n"
                + "    if [ -n \"$A\" ] && [ \"$A\" -gt \"$B\" ]; then\n"
                + "      cat \"$P\"\n"
                + "      echo PROBE_FILE_READY\n"
                + "      exit 0\n"
                + "    fi\n"
                + "  fi\n"
                + "  i=$((i+1))\n"
                + "  sleep 1\n"
                + "done\n"
                + "echo PROBE_TIMEOUT\n"
                + "exit 9\n";

        long t0 = System.currentTimeMillis();
        RootShell.Out o = RootShell.run(script, WAIT_MS + 10_000L);
        String out = o.text == null ? "" : o.text;

        if (!out.contains("PROBE_FILE_READY")) {
            // 超时。给出可操作的排查方向，而不是笼统的「失败了」。
            long sec = (System.currentTimeMillis() - t0) / 1000;
            boolean alive = OucRestarter.isRunning();
            rep.errors.add("等了 " + sec + " 秒，没有等到新报告"
                    + "（「软件更新」进程" + (alive ? "在运行" : "没在运行") + "）。");
            rep.errors.add("常见原因：LSPosed 里没有勾选 com.hihonor.ouc；"
                    + "模块没启用；或本机不是荣耀系统（没有「软件更新」）。");
            if (!out.contains("PROBE_TIMEOUT")) {
                rep.errors.add("su 输出：" + out.trim());
            }
            return rep;
        }

        // ── 五、解析文件 ──
        int cut = out.indexOf("PROBE_FILE_READY");
        TargetReport.Parsed parsed = TargetReport.decode(out.substring(0, cut));
        rep.reportFound = parsed.hasResults();
        rep.apkTag = parsed.apkTag;
        rep.at = parsed.at;
        for (HookTarget t : HookTarget.ALL) {
            TargetResolver.Resolved r = parsed.results.get(t.id);
            rep.results.put(t.id, r != null
                    ? r : new TargetResolver.Resolved(null, null, new String[0], "none"));
        }
        step(p, "解析完成：" + rep.resolvedCount() + " / " + rep.results.size());
        Log.i(TAG, "探测完成，APK 指纹 " + rep.apkTag + "，结果 " + rep.summary());
        return rep;
    }

    private static void step(Progress p, String line) {
        if (p != null) {
            try {
                p.onStep(line);
            } catch (Throwable ignored) {
                // 进度显示出错绝不能影响解析本身。
            }
        }
    }

    /** 把毫秒时间戳说成「几秒前 / 几分钟前」。 */
    private static String ago(long at) {
        long d = System.currentTimeMillis() - at;
        if (d < 0) {
            return "刚刚";
        }
        long sec = d / 1000;
        if (sec < 60) {
            return sec + " 秒前写入";
        }
        long min = sec / 60;
        if (min < 60) {
            return min + " 分钟前写入";
        }
        return (min / 60) + " 小时前写入";
    }
}