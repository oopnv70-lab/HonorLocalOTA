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

    // ─────────────── 服务器版本组 ───────────────

    /**
     * 检查更新请求体里决定「问哪个版本组」的类。
     *
     * 这个类没有被混淆，全名可以直接用：
     *   source: com/hihonor/android/hnouc/check/model/checkinfo/DependCheckInfo.java
     * 它在构造函数最后一行回调 bVar.R(this)，把版本组写进请求体：
     *   this.checkMode = bVar;
     *   commonParaInit();
     *   bVar.R(this);
     */
    static final String CLS_DEPEND_CHECK_INFO =
            "com.hihonor.android.hnouc.check.model.checkinfo.DependCheckInfo";

    /**
     * 写版本组的方法（在 l5.b 里定义），签名 (DependCheckInfo)V。
     *
     * ── 为什么必须挂这里 ──
     *   OUC 的检查更新请求，在构造请求体的那一刻就要决定问哪个版本组：
     *
     *     if (t() || !HnOucApplication.z().i0()) {
     *         setSubGroup(BetaUtil.e());   // 尝鲜组
     *         setVerGroup(BetaUtil.f());
     *     } else {
     *         setSubGroup("PHASE03");      // 正式发布组
     *         setVerGroup("SERVICE");
     *     }
     *
     *   t() 是本次检查的模式标志：手动检查走 o5.c.D()，那里写的是 M(false)，
     *   所以手动检查时 t() 恒为 false。
     *   i0() 读的是 x9.b 的 private boolean C，默认 false，且是纯内存字段
     *   （不落盘、无 putBoolean），只有进过一次「升级尝鲜」页
     *   （kg.q.f0() 里 t4(true)）或走过 YOYO 语音查版本才会变 true。
     *
     *   于是默认情况下 t()==false 且 i0()==false，请求落到尝鲜组；
     *   而本机 files/profile.xml 并不存在，BetaUtil.e()/f() 都返回空串，
     *   等于发一个空版本组出去，服务器不会回正式版全量包，
     *   界面就显示「当前已是最新版本」。
     *
     *   这就是「开了全量包却收不到更新、必须先进一次尝鲜」的真正原因。
     *   挂在这里强制写成正式组，才能让 check 更新直接拿到最新版本。
     */
    static final String MTH_CHECK_GROUP = "R";

    /** 正式发布组的 subGroup。 */
    static final String VER_GROUP_SUB = "PHASE03";

    /** 正式发布组的 verGroup。 */
    static final String VER_GROUP_VER = "SERVICE";

    // ─────────────── 升级尝鲜 ───────────────

    /**
     * 尝鲜页的 View 实现类，source: TrialVersionView.java。
     *
     * 「升级尝鲜」是一条完全独立的数据通路，不经过 l5.b.p()：
     *   TrialVersionActivity
     *     └ y() → new kg.q(this, new qg.g(this), new eg.b())
     *         └ eg.b.b() → eg.b.f() 直接打 checkBetaActivities 接口
     *             └ kg.q.A0(code, regStatus, str, osVer)
     *                 └ qg.g.l0(code, ...)   ← 渲染
     * 所以压 p() 的 8 个标志位对它毫无影响，这就是「屏蔽开着、
     * 尝鲜页里却还显示有新版本」的原因。
     */
    static final String CLS_TRIAL_VIEW = "qg.g";

    /** 尝鲜页的渲染方法，签名 (IILjava/lang/String;Ljava/lang/String;)V。 */
    static final String MTH_TRIAL_RENDER = "l0";

    /**
     * 尝鲜状态码：无尝鲜活动。
     *
     * qg.g.l0() 的状态码语义（从反编译代码逐个读出）：
     *   10 = 无尝鲜活动 → card_status_no_beta_test + card_btn_recheck
     *   11 = 可报名     → card_btn_to_register
     *   12 = 检查中     → card_btn_checking，按钮置灰
     *   13 = 已报名     → card_btn_details
     * 屏蔽生效时强制成 10，页面显示「暂无尝鲜活动」，
     * 入口保留但不会出现任何新版本条目。
     */
    static final int TRIAL_STATUS_NONE = 10;

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

    // ─────────────── 更新包下载 ───────────────

    /**
     * 启动下载的收口方法所在类。source: c6/q.java（NewDownloadEntry）
     *
     * 方法 startDownload(boolean) 是全 dex 里所有下载发起路径的唯一汇合点：
     * 手动点下载、自动下载（AutoCheckService）、COTA（CotaService）、
     * 开机恢复（LockedBootReceiver → g() → 这里）全部调它。
     *
     * 这三处都只是**兜底值**，实际类名/方法名由 TargetResolver 按
     * 「方法体含 startDownload init 等日志原文」动态定位。
     */
    static final String CLS_DOWNLOAD_START = "c6.q";

    /** 启动下载的方法名，签名 (Z)V，public static。 */
    static final String MTH_DOWNLOAD_START = "h";

    /**
     * 下载任务线程体所在类。source: ee/b.java（Download，abstract，Runnable）
     *
     * 它是所有下载子类（ee.g / ee.i）的公共父类，run() 只在这里定义一次，
     * 是「真正开始传输」的唯一入口，用作第二层兜底。
     *
     * 同样只是兜底值，实际由「方法体含 The thread has been interrupted
     * and is not allowed to run.」动态定位。
     */
    static final String CLS_DOWNLOAD_TASK = "ee.b";

    /** 下载线程的入口方法名，签名 ()V，public。 */
    static final String MTH_DOWNLOAD_TASK = "run";

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