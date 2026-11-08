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

    // ─────────────── 红点真正的来源 ───────────────

    /**
     * 系统设置页那个红点的数据源键名（Settings.Secure）。
     *
     * ── 链路（已在本机逐环读代码确认）──
     *   写入方：com.hihonor.ouc 的 r9.w1.V6(Context,int)
     *     Settings.Secure.putInt(cr, "hn_new_system_update", n)
     *   读取方：com.android.settings 的 dashboard.r0.b()
     *     Settings.Secure.getInt(cr, ..., 0) → SystemUpdatePreference.Q(n)
     *     → onBindViewHolder 里 n > 0 就 setVisibility(VISIBLE)
     *
     *   所以这个红点根本不在 OUC 进程里画，HnEventBadgeView 对它无效。
     *   在原版里 n 是"新版本条数"，0 表示无新版本，D3() 安装完成后
     *   也调 V6(ctx, 0) 清零，语义与官方用法一致。
     *
     * 实测本机 com.android.settings 14.0.0.210 对应
     *   id update_count (0x7f0a0fbc)，类 HwTextView，text 为 n。
     */
    static final String KEY_HN_NEW_SYSTEM_UPDATE = "hn_new_system_update";

    /**
     * 直接压掉红点，而不是去改其它数据。
     *
     * 参数是"新版本条数"，任何 > 0 的值都会让设置页显示红点，
     * 所以这里给 0。
     */
    static final int NO_NEW_UPDATE = 0;

    // ─────────────── 设置侧（红点的绘制方）───────────────

    /**
     * 系统设置包名。那个红点是它画的，不是 OUC 画的。
     *
     * ── 为什么必须把设置也纳入作用域 ──
     *   实测红点在 Settings$HwSystemDashboardActivity 里，
     *   控件 id update_count。查 dex 发现设置侧读 hn_new_system_update
     *   的地方一共 4 处，属于三个不同用途：
     *     dashboard.r0.b()   → 仪表盘上那个红点
     *     dashboard.r0.e()   → 注册 ContentObserver 后立刻读一次
     *     shortcuts.a.v()    → 桌面快捷方式是否标记「有新版本」
     *     SystemUpdateSettingsController.getUpdateState() → 系统更新页状态
     *   只在 OUC 侧把写入值压成 0，管不到这 4 个读点里已经存在的值，
     *   也管不到将来别的地方再写一次。所以要在读的这一端也堵住。
     */
    static final String PKG_SETTINGS = "com.android.settings";

    /** Settings.Secure 的类名，读/写都走它。 */
    static final String CLS_SECURE = "android.provider.Settings$Secure";

    /**
     * 读取字符串设置项的方法，签名 (ContentResolver, String, int)int。
     *
     * ── 为什么挂这一个而不是挂上面 4 个调用点 ──
     *   4 个调用点分布在 3 个类里，要各挂一次，且任何一个版本改了实现
     *   就漏一个。而它们全部走同一个重载 getInt(ContentResolver,String,int)，
     *   挂这里一次覆盖全部，按 key 过滤只影响这一个设置项。
     */
    static final String MTH_GET_INT = "getInt";

    // ─────────────── 桌面图标角标（红点的第二个出口）───────────────

    /**
     * 桌面角标的写入口 URI（桌面的 LauncherProvider）。
     *
     * ── 为什么设置页红点没了、桌面图标上还有 ──
     *   桌面「设置」图标的角标不读 Settings.Secure，走的是桌面自己的
     *   badge 表（launcher.db 里的 badge 表，对外经 com.android.badge
     *   这个 authority 暴露）。写入方是 com.hihonor.ouc：
     *     r9.w1.V6(Context,int) → t3(Context,int)
     *       → ContentResolver.call(本 URI, "change_badge", "", bundle)
     *       → bundle = { package = com.android.settings,
     *                    class   = com.android.settings.HWSettings,
     *                    badgenumber = n }
     *   package/class 取自 DeviceUtils.f()/b()，固定指向设置而不是 OUC
     *   自己，也就是「OUC 代设置写角标」。所以两头都要堵。
     */
    static final String URI_LAUNCHER_BADGE =
            "content://com.hihonor.android.launcher.settings/badge/";

    /**
     * 上面那个 URI 的写入方法名。
     *
     * 同一个 provider 还有一个 checkcancallbadge（只探测能否调用），
     * OUC 的 d0(Context) 就是先探它、成功再走 t3()，本模块不需要探测。
     */
    static final String MTH_CHANGE_BADGE = "change_badge";

    /**
     * 设置应用在桌面上的入口 Activity，角标表里的 class 列就是它。
     *
     * 取自 com.hihonor.hnouc.DeviceUtils.b()，是个写死的常量。不能换成
     * com.android.settings.Settings——provider 按 (package, class) 找行，
     * 对不上就写不进去，角标也不会变。
     */
    static final String CLS_SETTINGS_ENTRY = "com.android.settings.HWSettings";

    private Const() {
    }
}