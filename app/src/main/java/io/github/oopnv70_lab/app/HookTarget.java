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
     * 这时候只能描述「这个类长什么样」：
     *   持有 DependCheckInfo 类型的字段 + 至少 8 个 boolean 字段。
     * 这种结构是「检查结果汇总类」的语义指纹，
     * 混淆改不了它——改了字段类型，业务逻辑就崩了。
     */
    static final class StructShape {
        /** 必须持有这个类型的字段（简单类名或全名，包含匹配）。null 表示不要求。 */
        final String hasFieldOfType;

        /** 必须至少有这么多 boolean 字段。0 表示不要求。 */
        final int minBooleanFields;

        /** 必须至少有这么多字段总数。0 表示不要求。 */
        final int minFields;

        /** 父类必须是这个（包含匹配）。null 表示不要求。 */
        final String superClassNotObject;

        StructShape(String hasFieldOfType, int minBooleanFields, int minFields,
                    String superClassNotObject) {
            this.hasFieldOfType = hasFieldOfType;
            this.minBooleanFields = minBooleanFields;
            this.minFields = minFields;
            this.superClassNotObject = superClassNotObject;
        }

        static StructShape none() {
            return new StructShape(null, 0, 0, null);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 五个 OUC 侧目标
    // ══════════════════════════════════════════════════════════════

    /**
     * 〇、本地更新面板入口：kh.l.K(Context)V
     *
     * 这个最难描述，因为 K() 里面调的是 R()，而 R() 的日志字符串
     * 是 showLocalUpdateWarrantDialog。字符串在**另一个方法**里，
     * 所以这里用调用指纹：K() 必须调用一个含 showLocalUpdate 的方法。
     *
     * 兜底值 kh.l#K 保持不变。
     */
    static final HookTarget LOCAL_ENTRY = new HookTarget(
            "local_entry",
            "本地更新面板入口",
            "void",
            new String[]{"Context"},
            new String[]{Const.NEEDLE},   // update_full_ 是本地更新语义
            StructShape.none(),
            "kh.l#K");

    /**
     * 一、检查更新汇总：l5.b.p()V → 压掉「有新版本」标志位
     *
     * 字符串特征完全失效（方法体无 const-string），
     * 所以靠结构：持有 DependCheckInfo 字段 + 9 个 boolean 字段。
     */
    static final HookTarget CHECK_SUM = new HookTarget(
            "check_sum",
            "检查更新结果汇总",
            "void",
            new String[]{},   // 无参 void，太宽，全靠结构层
            new String[]{},
            new StructShape("DependCheckInfo", 8, 9, null),
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
     * 方法体含 PHASE03 / SERVICE，是正式版本组的名字。
     */
    static final HookTarget CHECK_GROUP = new HookTarget(
            "check_group",
            "请求版本组写入",
            "void",
            new String[]{"DependCheckInfo"},
            new String[]{"PHASE03"},
            StructShape.none(),
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
            LOCAL_ENTRY, CHECK_SUM, PKG_TYPE, CHECK_GROUP, TRIAL_RENDER, BADGE_COUNT
    };
}