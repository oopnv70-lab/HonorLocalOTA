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

    // ───── 版本伪造（让服务器以为这台机还停在上一代 OS）─────
    //
    // 检查更新的请求体由 Request$ParamBuilder 构造：
    //   ParamBuilder.<init>(Request) 里逐个 invoke DependCheckInfo->getXxx()，
    //   再用 CommonRules->$(静态 setter) 写进 CommonRules 的字段。
    // 所以 hook 这个构造函数、在它跑完之后改 CommonRules，就是
    // 「所有上报版本字段的唯一收口」，比一个一个 hook getter 干净。
    //
    // 为什么不 hook SystemPropertiesEx.get：
    //   baseVersionInOemInfo / custVersionInOemInfo 走的是 OemInfoUtil（读 oeminfo），
    //   不经过属性接口，hook 属性只能改一半，两套值会不一致。

    /** 请求参数构建器，source: Request$ParamBuilder.java。 */
    static final String CLS_PARAM_BUILDER =
            "com.hihonor.android.hnouc.check.model.checkinfo.Request$ParamBuilder";

    /** ParamBuilder 构造函数唯一的参数类型，source: Request.java。 */
    static final String CLS_REQUEST =
            "com/hihonor/android/hnouc/check/model/checkinfo/Request";

    /** 真正进 JSON 的版本容器，source: Request$ParamBuilder$CommonRules.java。 */
    static final String CLS_COMMON_RULES =
            "com.hihonor.android.hnouc.check.model.checkinfo.Request$ParamBuilder$CommonRules";

    /** 读取系统属性的唯一入口（ROM 私有类），全 APK 共 167 处引用。 */
    static final String CLS_SYS_PROP_EX = "com.hihonor.android.os.SystemPropertiesEx";

    /**
     * 允许被伪造的属性键白名单。
     *
     * 为什么用白名单而不是「名字里含 version 就改」：
     *   get(String) 有 108 个调用点，误改一个非版本属性就可能让 App 崩掉。
     *   这里只列真正承载系统版本的四个键，其余一律原样透传。
     *
     * 本机实测的真实值（2026-11-07，AAK-AN00 / OS11）：
     *   ro.comp.hl.product_base_version    = AAK-LGRP1-CHN 11.0.0.110(SP7)
     *   ro.comp.hl.product_cust_version    = AAK-AN00-CUST 11.0.0.110(C00)
     *   ro.build.display.id                = AAK-AN00 11.0.0.110(SP7C00E110R102P2)
     *   ro.comp.hl.product_preload_version = AAK-AN00-PRELOAD 11.0.0.2(C00R102)
     *
     * 配置文件里直接用属性全名当 key，例如：
     *   ro.comp.hl.product_base_version=AAK-LGRP1-CHN 10.0.0.127(SP7)
     */
    static final String[] SPOOF_PROP_KEYS = {
            "ro.comp.hl.product_base_version",
            "ro.comp.hl.product_cust_version",
            "ro.comp.hl.product_preload_version",
            "ro.build.display.id",
            "ro.build.version.incremental",
            "ro.magic.cversion"
    };

    /**
     * CommonRules 里允许被覆盖的版本字段（= 配置文件里的 key）。
     *
     * 实测本机原值为：
     *   productBaseVersion = AAK-LGRP1-CHN 11.0.0.110(SP7)
     *   cVersion           = AAK-AN00-CUST 11.0.0.110(C00)
     *   dVersion           = D000
     * 这些只是「允许改的名单」，具体填什么由配置文件决定。
     */
    static final String[] SPOOF_FIELDS = {
            "productBaseVersion", "cVersion", "dVersion", "deviceDisplayVersion",
            "hotaVersion", "vabGroupVersion", "verGroup", "vendorCota",
            // ── 真正被组包层读取的字段 ──
            // 实测组包用的是 sf/a（BlRequest.java），它读的是
            //   getBaseVersionInOemInfo / getCustVersionInOemInfo
            // 而不是 baseVersion / custVersion。
            // 这两个值来自 r9/d3（OemInfoUtil，读 oeminfo 分区），
            // 必须一起改，否则两套值不一致，服务器仍按原版本判定。
            "baseVersion", "custVersion",
            "baseVersionInOemInfo", "custVersionInOemInfo",
            "preloadVersion", "preloadVersionInOemInfo"
    };

    /**
     * 配置文件按顺序查找，第一个能读到的生效。
     *
     * 路径选择依据（实测，不是推断）：
     *   本模块 io.github.honorlocalota 已在 KernelSU 授权列表里
     *   （/data/adb/ksu/.allowlist 第一行），进程内可直接 su。
     *   实测 su 10357 -c '/system/bin/su -c id' → uid=0(root) u:r:ksu:s0。
     *
     *   所以配置文件可以放在 OUC 自己的外部私有目录里，由本模块
     *   通过 root 写入、由 OUC 直接读取（同 uid 同目录，零权限问题）：
     *     /storage/emulated/0/Android/data/com.hihonor.ouc/files/
     *       → 实测 root 写入 OK，标签 fuse，属主 root:ext_data_rw
     *         （必须 root 写，普通 app uid 10357 会被 Android 11+
     *          的 Android/data 隔离挡住）
     *     /data/local_ota/
     *       → 实测 root 写入 OK，标签 local_ota_file，作为兜底
     *
     * 格式是 key=value，# 开头是注释，值为空的项表示「这个字段不改」。
     * 每次构造请求都重新读文件，所以改完立刻生效，不用重启 OUC。
     */
    static final String[] SPOOF_CONFIG_PATHS = {
            "/storage/emulated/0/Android/data/com.hihonor.ouc/files/version_spoof.conf",
            "/data/local_ota/version_spoof.conf"
    };

    /** su 可执行文件路径（KernelSU 在 /system/bin/su）。 */
    static final String SU_BIN = "/system/bin/su";

    /** 配置文件模板内容，面板/文档里可以直接引用。 */
    static final String SPOOF_TEMPLATE =
            "# 只写你想改的字段，没写或用 # 注释掉的保持原值。\n"
            + "# 每次点「检查更新」都会重新读本文件，改完即生效，不用重启。\n"
            + "# 值为空 = 不改该字段。\n"
            + "\n"
            + "# 系统基带版本串，服务器靠这个判断你停在哪个大版本\n"
            + "# productBaseVersion=AAK-LGRP1-CHN 10.0.0.150(SP2)\n"
            + "\n"
            + "# 定制版本串\n"
            + "# cVersion=AAK-AN00-CUST 10.0.0.150(C00)\n"
            + "\n"
            + "# 其余可改字段：dVersion / deviceDisplayVersion / hotaVersion\n"
            + "# vabGroupVersion / verGroup / vendorCota\n";

    private Const() {
    }
}