package io.github.oopnv70_lab.app;

/**
 * 重启「软件更新」进程，走 root。
 *
 * ── 为什么需要这个按钮 ──
 *   两个开关本身是「读时生效」的：SwitchStore.readKey() 每次都真的去读
 *   /data/local_ota/module_switch.conf，没有任何内存缓存。所以单纯切换开关
 *   不需要重启，原版「软件更新」下一次检查更新就会读到新值。
 *
 *   真正需要重启的是这几种情况：
 *     · 刚在 LSPosed 里启用或更新了本模块 —— hook 只在 OUC 进程启动时挂载，
 *       已经跑着的进程还带着旧代码（或压根没挂上），
 *       表现是「点三点菜单里没有本地更新入口」，重启进程才会重新 onPackageReady。
 *     · OUC 界面状态卡住（一直转圈、更新流程中断后残留状态）。
 *
 * ── 为什么用 force-stop 而不是 kill ──
 *   kill 掉进程后系统可能按原 Activity 栈把它立刻拉回来，
 *   这时新进程有机会在 LSPosed 注入之前就跑起来，结果不可预期。
 *   force-stop 会把整个包的组件状态一并停掉，之后主动 startActivity 起的是
 *   干净的新进程；LSPosed 注入发生在 zygote fork 阶段，一定先于我们的代码。
 *
 * ── 为什么要显式 am start ──
 *   force-stop 之后进程是死的，不拉起的话用户看到的就是「点完按钮什么都没发生」。
 *   必须由我们发起一次 Activity 启动，把界面直接带回「软件更新」。
 *   本 App 是 untrusted_app，没有后台启动 Activity 的特权，
 *   所以这一步同样交给 root 执行 am start。
 *
 * ── 路径写死 ──
 *   am / pidof 都在 /system/bin 下，root 的 PATH 不一定包含它，
 *   用绝对路径可以避免「找不到 am」这种与功能无关的失败。
 */
final class OucRestarter {

    private static final String PKG = Const.PKG_OUC;

    /** 原版「软件更新」入口 Activity，取自本机 cmd package resolve-activity 实测结果。 */
    private static final String MAIN_ACTIVITY =
            "com.hihonor.android.hnouc.ui.activities.MainEntranceActivity";

    private static final String AM = "/system/bin/am";
    private static final String PIDOF = "/system/bin/pidof";

    /** 重启分两步且 am start 可能要等几秒，用 RootShell 的长超时。 */
    private static final long STEP_TIMEOUT_MS = RootShell.LONG_TIMEOUT_MS;

    private OucRestarter() {
    }

    /**
     * 探测进程当前是否在跑。
     *
     * 单独探一次是为了在界面上把「重启」和「启动」说清楚：
     * 进程本来没跑时 force-stop 是空操作，那时说「已启动」才准确。
     *
     * @return true 表示在跑；探测失败按未运行处理
     */
    static boolean isRunning() {
        RootShell.Out o = RootShell.run(PIDOF + " " + PKG + " 2>/dev/null");
        return o.ok() && !o.text.trim().isEmpty();
    }

    /** 重启结果。detail 是给用户看的一句话，含每步的原始输出便于定位。 */
    static final class Result {
        final boolean ok;
        final String detail;
        /** 重启前进程是否在跑，用来区分「重启」和「启动」。 */
        final boolean wasRunning;

        Result(boolean ok, boolean wasRunning, String detail) {
            this.ok = ok;
            this.wasRunning = wasRunning;
            this.detail = detail;
        }
    }

    /**
     * 停掉「软件更新」并重新拉起界面。
     *
     * 两步合并进一次 su，而不是 fork 两次：
     * su 每次有几十到几百毫秒开销，两次会让按钮明显迟滞；
     * 合并后也能用一条脚本的退出码判断整体成败。
     * 这里不用 set -e，改为逐条检查，这样失败时能说明是停失败还是启失败。
     */
    static Result restart() {
        final boolean wasRunning = isRunning();

        String script = AM + " force-stop " + PKG + " 2>&1; "
                + "StopRc=$?; "
                + "if [ $StopRc -ne 0 ]; then echo \"FORCE_STOP_FAILED=$StopRc\"; exit 2; fi; "
                + AM + " start -n " + PKG + "/" + MAIN_ACTIVITY + " 2>&1; "
                + "StartRc=$?; "
                + "if [ $StartRc -ne 0 ]; then echo \"AM_START_FAILED=$StartRc\"; exit 3; fi; "
                + "echo RESTART_OK";

        RootShell.Out o = RootShell.run(script, STEP_TIMEOUT_MS);
        if (o.ok() && o.text.contains("RESTART_OK")) {
            return new Result(true, wasRunning, wasRunning ? "已重启" : "已启动");
        }

        String msg = o.text == null ? "" : o.text.trim();
        if (msg.isEmpty()) {
            msg = o.code == -1 ? "没能启动 su 或执行超时" : ("退出码 " + o.code);
        } else if (o.code == 2) {
            msg = "停进程失败：" + msg;
        } else if (o.code == 3) {
            msg = "启动界面失败：" + msg;
        }
        return new Result(false, wasRunning, msg);
    }
}