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
     * CommonRules 里要改的版本字段。
     *
     * 实测本机上报值为：
     *   productBaseVersion = AAK-LGRP1-CHN 11.0.0.110(SP7)
     *   cVersion           = AAK-AN00-CUST 11.0.0.110(C00)   ← 由 C00 拼出来的
     *   dVersion           = D000
     * 取不到的字段会跳过并打日志，不会整体失败——混淆名随版本变，宁可少改也要能挂上。
     */
    static final String[] SPOOF_FIELDS = {
            "productBaseVersion", "cVersion", "dVersion", "deviceDisplayVersion",
            "hotaVersion", "vabGroupVersion", "verGroup", "vendorCota"
    };

    /**
     * 把 " 11.0.0." 改成 " 10.0.0."，只动空格后面的版本段。
     *
     * 用 \s 而不是空串，是为了不误伤 AAK-LGRP1-CHN 里的数字；
     * 替换串用 ${1} 显式收尾，否则 "$110" 会被当成第 110 个捕获组。
     */
    static final String SPOOF_FROM = "(\\s)11\\.0\\.0\\.";
    static final String SPOOF_TO = "${1}10.0.0.";

    private Const() {
    }
}