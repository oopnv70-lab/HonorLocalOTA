package io.github.honorlocalota;

/**
 * 常量的单一出处，避免界面上和 hook 里各写一份、改一处漏一处。
 *
 * 目录与门槛值全部来自对 com.hihonor.ouc 2.1.1.1（versionCode 205）的反编译结果，
 * 不是猜的：
 *
 *   kh.l 字段 d  = ".*update_full_.*\\.zip"   ← 文件名过滤器
 *   kh.l 字段 e  = "/data/local_ota/"         ← 扫描目录（硬编码）
 *   kh.l 字段 i  = 0x780000000                ← 空间门槛 30 GiB
 *   kh.l.L() 里 D(e) 扫目录、比空间、putExtra("is_start_install", true)
 *   然后 startActivity(LocalUpdateVabInstallingActivity)
 */
final class Const {

    /** 用户投放 OTA 包的目录。 */
    static final String SRC_DIR = "/storage/emulated/0/Download/OTA";

    /** 原版真正扫描的目录，受 system_ext_file_contexts:189 约束，不可改。 */
    static final String DST_DIR = "/data/local_ota";

    /** 原版过滤器要求文件名里含 update_full_，否则 String.matches 永远不成立。 */
    static final String NEEDLE = "update_full_";

    /** 0x780000000 = 32,212,254,720 字节 = 30 GiB。 */
    static final long MIN_FREE_BYTES = 0x780000000L;

    /** 原版包名与关键组件名。 */
    static final String PKG_OUC = "com.hihonor.ouc";
    static final String ACT_MAIN =
            "com.hihonor.android.hnouc.ui.activities.MainEntranceActivity";

    /**
     * 本模块自己的包名。
     *
     * 必须单独写一份：hook 代码跑在 com.hihonor.ouc 进程里，那里
     * ctx.getPackageName() 返回的是 OUC，不是我们；而我们要拉起的界面
     * 属于本 APK（它没注册在 OUC 的清单里，只能跨应用启动）。
     */
    static final String PKG_MODULE = "io.github.honorlocalota";

    /**
     * 我们注入项的菜单 id。刻意选一个与原版 0x7f0903a4..0x7f0903aa 完全无关的值，
     * 这样原版 j2() 的 packed-switch 必然落到 default 分支，不会误触原版逻辑。
     */
    static final int MENU_ID = 0x484C4F54; // 'HLOT'

    /** 注入项文案。不能复用原版「本地更新」(0x7f110299)，否则用户分不清哪个是哪个。 */
    static final String MENU_TITLE = "系统包投放…";

    /** 原版「本地更新」菜单项 id，用于在菜单里精确剔除它。 */
    static final int STOCK_MENU_ID = 0x7f0903a5;

    /** 传给 OUC 的标记：MainEntranceActivity 收到它就直接进扫描安装，不弹免责声明。 */
    static final String EXTRA_START = "honorlocalota_start";

    private Const() {
    }
}