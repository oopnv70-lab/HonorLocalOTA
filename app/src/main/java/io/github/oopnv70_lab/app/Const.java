package io.github.oopnv70_lab.app;

/**
 * 常量单一出处。全部来自对 com.hihonor.ouc 2.1.1.1（versionCode 205）的反编译，
 * 以及本机实测，不是猜的。
 */
final class Const {

    /** 原版扫描目录，硬编码在 kh.l.L() 里（const-string "/data/local_ota/"）。 */
    static final String DST_DIR = "/data/local_ota";

    /**
     * kh.l.D() 用 String.matches(".*update_full_.*\\.zip") 全串匹配路径，
     * 文件名里没有 update_full_ 就永远扫不到。写入时按这个补。
     */
    static final String NEEDLE = "update_full_";

    /**
     * kh.l.L() 里的空间门槛：const-wide v3, 0x780000000L。
     * 0x780000000 = 32,212,254,720 字节 = 30 GiB。
     * 不够的话 L() 会走 x9/b.c6() + ag/n.j() 然后 return，界面上看不出原因。
     */
    static final long MIN_FREE_BYTES = 0x780000000L;

    /** 作用域包名。 */
    static final String PKG_OUC = "com.hihonor.ouc";

    // ─────────────── 开关文件 ───────────────

    /**
     * 开关文件路径。两个不同 SELinux 域的进程共享同一个文件。
     *
     * ── 为什么放这里而不是 SharedPreferences ──
     *   SharedPreferences 按 uid 隔离：本模块是自己的 uid，
     *   com.hihonor.ouc 是另一个 uid，读不到彼此的 prefs。
     *   而 libxposed 102 的 API 面里没有跨进程 prefs 通道
     *   （已核过 api-102.0.0 的全部 class，只有 hook 相关的东西）。
     *   所以只能落到双方都能访问的文件上。
     *
     * ── 两个进程的访问方式并不一样，这里必须写清楚 ──
     *   /data/local_ota 权限 drwxrwxrwx system:cache，标签 local_ota_file。
     *   从 /system_ext/etc/selinux/system_ext_sepolicy.cil 抽出的全部
     *   allow 规则只有四个域：adbd / platform_app_all / shell / update_engine。
     *
     *   · OUC 进程（platform_app_all 域）：在放行名单里，直接读写即可。
     *   · 本模块 App（untrusted_app 域）：不在名单里，连 getattr 都没有，
     *     所以设置页必须经 su 访问（见 RootShell.java），因此需要 root。
     *
     *   注意：不要用 `su <uid> -c ...` 去测本模块能不能写。那样的进程
     *   跑在 ksu/shell 域，测出来的结果是「能写」，但 App 真实运行域是
     *   untrusted_app，结论不成立。要测就必须看目标进程 ps -AZ 的实际域。
     */
    static final String SWITCH_FILE = DST_DIR + "/module_switch.conf";

    /**
     * 「屏蔽系统更新」的开关键名。
     *
     * 只写这一个键，值为 true / false。
     * 文件不存在、读不到、键缺失 → 一律按 true 处理（默认开启）。
     */
    static final String KEY_BLOCK_UPDATE = "block_update";

    /** 开关默认值：默认开启屏蔽。 */
    static final boolean DEFAULT_BLOCK_UPDATE = true;

    // ─────────────── 全量包 ───────────────

    /**
     * 「强制请求全量包」的开关键名。
     *
     * 默认关闭（false）。
     */
    static final String KEY_FORCE_FULL_PACKAGE = "force_full_package";

    static final boolean DEFAULT_FORCE_FULL_PACKAGE = false;

    /**
     * 决定「请求全量包还是增量包」的方法。
     *
     * source: r9/w1.java 的 getPackageTypeForChecking(...)
     * 该方法返回 "recovery"（全量）或 "upgrade"（增量），
     * 结果被写进 DependCheckInfo.hotaPackageType，再随请求发给服务器。
     * 日志原文直接印证了语义：
     *   "getPackageTypeForChecking readyToUpgradeIncrementPackage true,
     *    return INCREMENT_PACKAGE"
     *   "... false, return RECOVERY"
     */
    static final String CLS_PKG_TYPE = "r9.w1";

    /** 包类型判定方法，签名 ()Ljava/lang/String;，private static。 */
    static final String MTH_PKG_TYPE = "x2";

    /** 全量包。 */
    static final String PKG_TYPE_RECOVERY = "recovery";

    // ─────────────── 红点 ───────────────
    /**
     * 红点（角标）的绘制开关类。
     *
     * source: Lcom/hihonor/uikit/hneventbadge/widget/HnEventBadgeView;
     *
     * 该类是 OUC 自带的 UI 组件——整个 dex 里只有这一处引用，
     * 不是从系统 uikit 共享来的，所以挂它不会波及其他应用。
     * 红点最终通过 setVisibility 控制，那 7 个调用方全在本类和
     * 同包辅助类 widget/b 内部，是红点显示的唯一收口。
     *
     * 对 18.0.3.230（versionCode 180003230）的 classes2.dex 实测：
     *   method_id [32826] HnEventBadgeView->setBadgeVisible(Z)V
     */
    static final String CLS_BADGE = "com.hihonor.uikit.hneventbadge.widget.HnEventBadgeView";

    /**
     * 红点可见性设置方法，签名 (Z)V。
     *
     * 名字没有混淆（是框架/support 库里常见的公开 API 命名习惯），
     * 这点与 kh.l.K()、r9.w1.x2() 那种纯混淆名不同，
     * 相对更可能在版本升级后仍然存在。
     */
    static final String MTH_BADGE = "setBadgeVisible";

    private Const() {
    }
}