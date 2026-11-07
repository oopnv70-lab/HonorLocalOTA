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
            "hotaVersion", "vabGroupVersion", "verGroup", "vendorCota"
    };

    /**
     * 配置文件按顺序查找，第一个能读到的生效。
     *
     * 放两个位置的原因：
     *   1) 第一条是 OUC 自己的外部私有目录，OUC 一定有权限，
     *      而且你从文件管理器就能改，不需要 root。
     *   2) 第二条是本地更新用的那个 0777 目录，作为兜底。
     *
     * 格式是 key=value，# 开头是注释，值为空的项表示「这个字段不改」。
     * 每次构造请求都重新读文件，所以改完立刻生效，不用重启 OUC。
     */
    static final String[] SPOOF_CONFIG_PATHS = {
            "/storage/emulated/0/Android/data/com.hihonor.ouc/files/version_spoof.conf",
            "/data/local_ota/version_spoof.conf"
    };

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