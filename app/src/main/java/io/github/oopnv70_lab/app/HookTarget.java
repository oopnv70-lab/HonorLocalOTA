package io.github.oopnv70_lab.app;

/**
 * 一个 hook 目标的「语义描述」。
 *
 * ── 为什么要单独抽一层 ──
 *   原先是直接把混淆名写进 OtaHook：
 *     Class<?> kl = Class.forName("kh.l", false, cl);
 *     Method k = kl.getDeclaredMethod("K", Context.class);
 *   一旦荣耀改版，这两行同时失效，而 OtaHook 里还有六处同样的写法，
 *   改起来要逐个对照 smali，正是「换个版本就直接失效」的根源。
 *
 *   现在把「找什么」和「怎么用」分开：
 *     · 这里只描述特征（语义），不含任何业务逻辑
 *     · 定位逻辑在 TargetResolver，只管按特征查
 *     · 业务逻辑在 OtaHook，只拿最终结果，不关心怎么找到的
 *   将来适配新版本，理想情况下一个字都不用改——特征本身是语义的，
 *   混淆换名不影响。实在要改，也只是改这个文件里的特征值。
 *
 * ── 特征为什么用这些字符串 ──
 *   全部是「功能语义」，不是混淆产物：
 *     PHASE03 / SERVICE   正式版本组的名字，服务器协议的一部分，不能随便改
 *     recovery / upgrade  包类型的取值，同样是协议约定
 *     hn_new_system_update  Settings.Secure 的键名，跨进程约定
 *     update_full_        本地更新文件名规则
 *   ROM 改混淆时这些通常会保留，因为改了就破坏与服务器的兼容性。
 *   这正是它们比 kh.l.K() 可靠的原因。
 */
final class HookTarget {

    /** 目标标识。仅用于日志和缓存 key，不参与查找。 */
    final String id;

    /** 人读的名字，写日志用。 */
    final String label;

    /**
     * 第一步：粗筛用的方法签名特征。
     * 为空表示不按签名筛（只用后面的语义特征）。
     */
    final String returnType;

    /** 参数类型的简写，用于粗筛。null 表示任意。 */
    final String[] paramTypes;

    /**
     * 第二步：语义字符串特征。方法体里必须**全部**出现。
     * 这是最可靠的一层：语义字符串基本不随混淆改变。
     */
    final String[] mustUseStrings;

    /**
     * 第三步：类结构特征。落在类上的约束。
     * 用于「方法签名太泛、单靠签名筛不干净」的情况。
     */
    final StructShape shape;

    /**
     * 兜底：已知的硬编码值（旧的 Const 常量）。
     * 动态查找全部失败时用它，保证老机器老版本还能跑。
     * 格式 "类名#方法名" 或 "类名#方法名(参数,参数)"。
     */
    final String hardcodedFallback;

    private HookTarget(String id, String label, String returnType, String[] paramTypes,
                       String[] mustUseStrings, StructShape shape, String hardcodedFallback) {
        this.id = id;
        this.label = label;
        this.returnType = returnType;
        this.paramTypes = paramTypes;
        this.mustUseStrings = mustUseStrings;
        this.shape = shape;
        this.hardcodedFallback = hardcodedFallback;
    }

    /**
     * 类结构特征。
     *
     * 存在的意义：像 l5.b.p() 这种「无参 void」的方法，
     * 全包有上万个候选，单靠签名根本筛不出来；
     * 而它的方法体里一个字符串都没有，字符串特征也失效。
     * 这时候只能描述「这个类长什么样」。
     *
     * ── 为什么光靠「boolean 字段够多」不够（实测踩过） ──
     *   第一版只写了「持有 DependCheckInfo 字段 + ≥8 个 boolean」，
     *   结果 l5.b（19 个 boolean）和 l5.d0（12 个 boolean）**都**满足：
     *   d0 是 b 的子类，继承走了 b 的全部 boolean，自己又加了几个。
     *   于是 findClass 返回 2 个类，过滤后仍是 2 个，判定「不唯一」直接放弃，
     *   最终回落到硬编码 l5.b#p —— 界面上就显示成「写死的名字」。
     *
     *   修法是描述得再具体一点，而具体的那一面恰恰是混淆不掉的：
     *     requireAbstract   基类 CheckMode 是 abstract，d0 是具体模式不是；
     *     minDeclaredFields 只看类**自己**声明的字段，不含继承，
     *                       b 自己写 16 个（9 个 boolean），d0 只写 4 个。
     *   两条一起用，就把「基类」和「某个具体模式」分开了。
     */
    static final class StructShape {
        /** 必须持有这个类型的字段（简单类名或全名，包含匹配）。null 表示不要求。 */
        final String hasFieldOfType;

        /** 必须至少有这么多 boolean 字段（只数自己声明的）。0 表示不要求。 */
        final int minBooleanFields;

        /** 必须至少有这么多字段总数（只数自己声明的）。0 表示不要求。 */
        final int minFields;

        /** 父类必须是这个（包含匹配）。null 表示不要求。 */
        final String superClassNotObject;

        /** 类必须是 abstract。用于「基类」与「某个具体子类」的区分。 */
        final boolean requireAbstract;

        /**
         * 类内仍需进一步筛选时用的字段名。null 表示不用这一步。
         *
         * 用途：定位到类之后，光靠方法签名可能还剩好几个候选
         * （例如 l5.b 里有 8 个 void 无参方法），这时要求
         * 「方法体里必须读或写这个字段」把它们分开。
         * 选字段的标准是实测出来的唯一性——同一个类里只有一个方法碰它。
         */
        final String uniqueField;

        StructShape(String hasFieldOfType, int minBooleanFields, int minFields,
                    String superClassNotObject) {
            this(hasFieldOfType, minBooleanFields, minFields, superClassNotObject, false, null);
        }

        StructShape(String hasFieldOfType, int minBooleanFields, int minFields,
                    String superClassNotObject, boolean requireAbstract) {
            this(hasFieldOfType, minBooleanFields, minFields, superClassNotObject,
                    requireAbstract, null);
        }

        StructShape(String hasFieldOfType, int minBooleanFields, int minFields,
                    String superClassNotObject, boolean requireAbstract, String uniqueField) {
            this.hasFieldOfType = hasFieldOfType;
            this.minBooleanFields = minBooleanFields;
            this.minFields = minFields;
            this.superClassNotObject = superClassNotObject;
            this.requireAbstract = requireAbstract;
            this.uniqueField = uniqueField;
        }

        static StructShape none() {
            return new StructShape(null, 0, 0, null);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 五个 OUC 侧目标
    // ══════════════════════════════════════════════════════════════
    /**
     * 一、检查更新汇总：l5.b.p()V → 压掉「有新版本」标志位
     *
     * ── 字符串特征完全失效 ──
     *   p() 的方法体里一个 const-string 都没有（全是 boolean 赋值和字段搬运），
     *   所以只能描述「这个类长什么样」。
     *
     * ── 特征怎么定的（都是实测值，不是估的） ──
     *   l5.b 自称 16 个字段、9 个 boolean；是 abstract（CheckMode 基类）。
     *   l5.d0 是它的子类，自称只有 4 个字段，且不是 abstract。
     *   只写「≥8 boolean」时两者都过，所以必须加上 abstract 这一条。
     *
     * ── p() 在类内怎么唯一确定 ──
     *   l5.b 里有 8 个 void 无参方法（q/D/E/J/K/m/n/p），
     *   光按「void + 0 参」在这个类里找会命中 8 个。
     *   所以额外要求：方法必须碰 this.o 字段。
     *   实测 this.o 只在一处被赋值，就在 p() 里
     *   （this.o = this.a.getHasSelfNewVersion()），
     *   所以「用到了 o 字段的 void 无参方法」在这个类里唯一。
     */
    static final HookTarget CHECK_SUM = new HookTarget(
            "check_sum",
            "检查更新结果汇总",
            "void",
            new String[]{},   // 无参 void，太宽，全靠结构层
            new String[]{},
            new StructShape("DependCheckInfo", 9, 16, null, true, "o"),
            "l5.b#p");

    /**
     * 二、包类型判定：r9.w1.x2()Ljava/lang/String;
     *
     * 方法体含 "recovery" 与 "upgrade"，这是协议取值，混淆不能改。
     */
    static final HookTarget PKG_TYPE = new HookTarget(
            "pkg_type",
            "包类型判定（全量/增量）",
            "java.lang.String",
            new String[]{},
            new String[]{"recovery", "upgrade"},
            StructShape.none(),
            "r9.w1#x2");

    /**
     * 三、版本组写入：l5.b.R(DependCheckInfo)V
     *
     * ── 方法体含 PHASE03 / SERVICE，是正式版本组的名字 ──
     *   这两个值是和服务器通信的协议取值，改了就对接不上服务器，
     *   所以 ROM 改混淆时也留着了，实测仍能命中。
     *
     * ── 为什么参数写成全名 ──
     *   PHASE03 在 l5.b 和 l5.i（HiCareMode）里都出现，两个方法签名完全相同，
     *   必须靠参数类型把候选收窄。而 DexKit 的 paramTypes 是拿字符串
     *   去比对 dex 里的**完整类型描述符**，写简写 "DependCheckInfo" 比不过
     *   "com.hihonor.android.hnouc.check.model.checkinfo.DependCheckInfo"。
     *   第一版就是简写，导致 2 个候选一个都筛不掉，判「不唯一」后回落硬编码。
     *
     * ── 唯一性还要靠 abstract ──
     *   即便参数筛成 l5.b.R 和 l5.i.R 两个，仍然不唯一。
     *   l5.i 是具体模式（HiCareMode），l5.b 是 abstract 基类；
     *   这里要的是基类里那份「通用写法」，所以再加 requireAbstract。
     */
    static final HookTarget CHECK_GROUP = new HookTarget(
            "check_group",
            "请求版本组写入",
            "void",
            new String[]{"com.hihonor.android.hnouc.check.model.checkinfo.DependCheckInfo"},
            new String[]{"PHASE03"},
            new StructShape(null, 0, 0, null, true),
            "l5.b#R");

    /**
     * 四、尝鲜页渲染：qg.g.l0(IIStringString)V
     *
     * 这个方法体里只有空串，字符串特征失效。
     * 靠结构：父类非 Object（super = Lg/b;）。
     */
    static final HookTarget TRIAL_RENDER = new HookTarget(
            "trial_render",
            "尝鲜页状态渲染",
            "void",
            new String[]{"int", "int", "java.lang.String", "java.lang.String"},
            new String[]{},
            new StructShape(null, 0, 0, "non-object"),
            "qg.g#l0");

    /**
     * 五、红点计数写入：r9.w1.V6(Context,I)V
     *
     * 方法体含 hn_new_system_update，是跨进程约定键名。
     */
    static final HookTarget BADGE_COUNT = new HookTarget(
            "badge_count",
            "红点计数写入",
            "void",
            new String[]{"Context", "int"},
            new String[]{"hn_new_system_update"},
            StructShape.none(),
            "r9.w1#V6");

    /** 需要动态定位的全部 OUC 侧目标。 */
    static final HookTarget[] ALL = {
            CHECK_SUM, PKG_TYPE, CHECK_GROUP, TRIAL_RENDER, BADGE_COUNT
    };
}