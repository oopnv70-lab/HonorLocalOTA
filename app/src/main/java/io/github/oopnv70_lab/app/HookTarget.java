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
         * 类内仍需进一步筛选时用的「被调用方法名」。null 表示不用这一步。
         *
         * 用途：定位到类之后，光靠方法签名可能还剩好几个候选
         * （例如 l5.b 里有 8 个 void 无参方法），这时要求
         * 「方法体里必须调用这个方法的类的方法」把它们分开。
         *
         * ── 为什么这里放方法名，而不是字段名 ──
         *   一开始写的是「必须碰某个字段」，填的是 this.o。
         *   但 o 是混淆后的字段名，和 l5.b、p 一样是这一次编译的产物，
         *   换版本就变——拿它当锚点，等于把不确定性从类名挪到了字段名，
         *   不配叫「跨版本可用」。
         *
         *   这里改填**未被混淆**的名字。原始类 DependCheckInfo 的包名和方法名
         *   都是完整的（com.hihonor.android.hnouc.check.model.checkinfo），
         *  getHasSelfNewVersion 这种取值方法带明确语义，ROM 改混淆时不会动
         *  它——动了就要改业务代码。全 dex 里它只出现两次：
         *  一次是它自己的定义，另一次就是 l5.b.p() 里那次调用。
         *  所以「调用了 getHasSelfNewVersion」= p() 的语义指纹，
         *  和 #R 用 PHASE03、#V6 用 hn_new_system_update 是同一级别的可靠度。
         */
        final String uniqueInvoke;

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
                    String superClassNotObject, boolean requireAbstract, String uniqueInvoke) {
            this.hasFieldOfType = hasFieldOfType;
            this.minBooleanFields = minBooleanFields;
            this.minFields = minFields;
            this.superClassNotObject = superClassNotObject;
            this.requireAbstract = requireAbstract;
            this.uniqueInvoke = uniqueInvoke;
        }

        static StructShape none() {
            return new StructShape(null, 0, 0, null);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 七个 OUC 侧目标
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
     *   所以再加一条：方法体里必须调用 DependCheckInfo.getHasSelfNewVersion()。
     *
     *   为什么挑这个方法：它是 DependCheckInfo 上的取值方法，
     *   而 DependCheckInfo 这个类**没被混淆**（包名方法名都完整）。
     *   全 dex 里 getHasSelfNewVersion 只出现两次：
     *   一次是它自己的定义，另一次就是 p() 里那次调用
     *   （this.o = this.a.getHasSelfNewVersion()）。
     *   所以「调用了 getHasSelfNewVersion」在 l5.b 内唯一指向 p()。
     */
    static final HookTarget CHECK_SUM = new HookTarget(
            "check_sum",
            "检查更新结果汇总",
            "void",
            new String[]{},   // 无参 void，太宽，全靠结构层
            new String[]{},
            new StructShape("DependCheckInfo", 9, 16, null, true, "getHasSelfNewVersion"),
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

    /**
     * 六、下载启动：startDownload
     *
     * ── 为什么这里才是「真正不让它下载」的收口 ──
     *   检查更新只决定「界面上说不说有新版本」，服务器那头照样会把包
     *   推过来、OUC 照样会把它落盘。要让设备真的不产生更新包，
     *   必须拦住下载这一步。
     *
     *   全 dex 里调这个方法的调用点有 20 多处：
     *     c6.o（LocalDownloadUtils）4 处、AutoCheckService、
     *     CotaService、CotaNewVersionDetailsActivity、n7.f、q7.a、
     *     g7.j、g7.k、s8.c …… 手动点「下载」、自动下载、
     *     开机后恢复下载、COTA 下载，全部最终落到这一个方法。
     *   也就是说拦这一个方法，等于同时封掉所有发起路径，
     *   不需要逐个去堵每个按钮和每个 Receiver。
     *
     * ── 特征为什么可靠 ──
     *   方法体里含 "startDownload init"、"Not eligible for unlocked downloads"
     *   两句日志原文（全 dex 各只出现 1 次），
     *   且宿主类里「static void + 仅一个 boolean 参数」的方法只有它一个。
     *   两个条件叠加，不依赖混淆名。
     *
     * ── 局限（必须写清楚）──
     *   它挡的是「OUC 自己发起下载」。若某个系统组件（update_engine /
     *   厂商服务）绕过 OUC 直接从服务器取包，不经过这里。
     *   那条路的兜底是下一个目标 DOWNLOAD_TASK。
     */
    static final HookTarget DOWNLOAD_START = new HookTarget(
            "download_start",
            "更新包下载启动",
            "void",
            new String[]{"boolean"},
            new String[]{"startDownload init", "Not eligible for unlocked downloads"},
            StructShape.none(),
            "c6.q#h");

    /**
     * 七、下载任务执行：Download.run()（兜底）
     *
     * ── 为什么需要第二层 ──
     *   DOWNLOAD_START 是「发起」层面的收口，但发起之后真正写盘的是
     *   ee.b（Download，abstract，implements Runnable）：
     *     run() → d() → 用 okhttp 拉分片、RandomAccessFile 写到
     *     /data/update/HnOUC/<任务号>/update_base.zip
     *   只要有任意一条路径绕过 startDownload 直接构造了下载任务，
     *   这一层就能兜住：run() 一进去就让它结束。
     *
     * ── 为什么不是拦 d() 或写文件 ──
     *   d() 是 protected、被两个子类 ee.g / ee.i 各自覆盖，拦它要拦两处，
     *   还会漏掉「以后新增子类」的情况。run() 只在 ee.b 定义一次，
     *   是全部子类的公共入口，也是线程真正被提交执行的那个方法。
     *   拦写文件则要碰 FileOutputStream/RandomAccessFile，那是全局类，
     *   会把别的 App 的写文件一起影响——这正是要避免的。
     *
     * ── 特征 ──
     *   方法体含 "The thread has been interrupted and is not allowed to run."
     *   （全 dex 唯一一处出现这句长串）。签名 ()V、无参。
     */
    static final HookTarget DOWNLOAD_TASK = new HookTarget(
            "download_task",
            "下载任务执行（兜底）",
            "void",
            new String[]{},
            new String[]{"The thread has been interrupted and is not allowed to run."},
            StructShape.none(),
            "ee.b#run");

    /** 需要动态定位的全部 OUC 侧目标。 */
    static final HookTarget[] ALL = {
            CHECK_SUM, PKG_TYPE, CHECK_GROUP, TRIAL_RENDER, BADGE_COUNT,
            DOWNLOAD_START, DOWNLOAD_TASK
    };
}