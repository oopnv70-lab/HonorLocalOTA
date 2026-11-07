package io.github.honorlocalota;

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
     * 开关文件路径。两个进程共享，不需要 root。
     *
     * ── 为什么放这里而不是 SharedPreferences ──
     *   SharedPreferences 按 uid 隔离：本模块是自己的 uid，
     *   com.hihonor.ouc 是另一个 uid，读不到彼此的 prefs。
     *   而 libxposed 102 的 API 面里没有跨进程 prefs 通道
     *   （已核过 api-102.0.0 的全部 class，只有 hook 相关的东西）。
     *   所以只能落到双方都能访问的文件上。
     *
     * ── 为什么这个路径两个进程都能用（实测，不是推断）──
     *   /data/local_ota 权限 drwxrwxrwx system:cache，标签 local_ota_file。
     *   · 模块进程（普通 app uid）：实测 su 10357 -c touch → rc=0，可读写。
     *     所以 App 里的开关不需要 root 就能保存。
     *   · OUC 进程（platform_app 域）：system_ext_sepolicy.cil
     *     allow platform_app_all local_ota_file，本来就允许访问，
     *     而且这个目录就是它自己扫描安装包的目录，必然可读。
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

    private Const() {
    }
}