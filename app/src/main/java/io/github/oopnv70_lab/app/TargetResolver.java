package io.github.oopnv70_lab.app;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.FieldsMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.query.matchers.MethodsMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.ClassDataList;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.FieldDataList;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「语义特征」变成「真实的类名/方法名」，四级兜底。
 *
 * ══════════════════════════════════════════════════════════════
 * 四级顺序（任何一级命中就停，都不会往下走）
 *   1. 本地缓存     —— 上次在同一版本上查到的结果，秒出
 *   2. DexKit 查询  —— 按语义特征现查，跨版本的主力
 *   3. 硬编码兜底   —— 旧的 Const 常量，保证老机器还能跑
 *   4. 明确失败     —— 写清哪个目标没找到，绝不静默变成「没效果」
 * ══════════════════════════════════════════════════════════════
 *
 * ── 为什么缓存要按「包名+versionCode」隔离 ──
 *   不同版本的混淆映射不同，混用缓存会拿到错的类名去 hook，
 *   那时的失败比「找不到」更难查：hook 挂上了，但挂错方法，
 *   表面上一切正常，功能却完全不生效。
 *   所以 appTag 里带版本号，升级/降级自动切换命名空间。
 *
 * ── 为什么 DexKit 查询失败不抛异常 ──
 *   定位失败是**预期内**的情况（换了机型、换了版本都可能失败），
 *   不是程序错误。抛异常会让整个 onPackageReady 挂掉，
 *   连其它能挂上的 hook 都一起丢，那才是真正的灾难。
 *   所以这里全部返回 null 或空，让调用方按目标粒度决定降级。
 */
final class TargetResolver {

    private static final String TAG = "HonorLocalOTA";

    /** 定位结果。className/methodName 非空即有效。 */
    static final class Resolved {
        final String className;
        final String methodName;
        final String[] paramTypes;

        /** 这一条是怎么来的：cache / dexkit / hardcoded。写日志和排查用。 */
        final String source;

        Resolved(String className, String methodName, String[] paramTypes, String source) {
            this.className = className;
            this.methodName = methodName;
            this.paramTypes = paramTypes;
            this.source = source;
        }

        @Override
        public String toString() {
            return className + "#" + methodName + "(" + paramTypes.length + "参)"
                    + " [" + source + "]";
        }
    }

    /** 本进程内已解析的结果，避免同一个目标重复查 Dex。 */
    private final Map<String, Resolved> memo = new HashMap<>();

    /** DexKit bridge。首次真正需要查询时才创建，创建后复用。 */
    private DexKitBridge bridge;

    /** bridge 创建失败过就不再重试，避免每次调用都卡在 native 初始化上。 */
    private boolean bridgeFailed;

    /** 缓存读写。null 表示缓存不可用（例如宿主进程读不到文件），只影响性能不影响正确性。 */
    private final TargetCache cache;

    TargetResolver(TargetCache cache) {
        this.cache = cache;
    }

    /**
     * 直接塞一个已经建好的 bridge。
     *
     * 给「模块自己进程里的解析」用：那边由 TargetProbe 负责创建 bridge
     * （因为它要同时统计 dex 数量、捕获创建失败的具体异常），
     * 再交给引擎复用，避免同一个 APK 被解析两遍。
     *
     * 传进来的必须是能用的 bridge；传 null 等价于没调过。
     */
    void attachBridge(DexKitBridge b) {
        if (b != null) {
            this.bridge = b;
        }
    }

    /**
     * 解析一个目标。
     *
     * @param target    语义描述
     * @param cl        宿主的 ClassLoader，用来验证硬编码兜底是否真的存在
     * @param apkPath   宿主 APK 路径，DexKit 用
     * @param appTag    缓存命名空间（包名+版本号）
     * @return 永远非 null，但 className 可能是 null，表示彻底失败
     */
    Resolved resolve(HookTarget target, ClassLoader cl, String apkPath, String appTag) {
        // ── 进程内已解析过，直接给 ──
        Resolved hit = memo.get(target.id);
        if (hit != null) {
            return hit;
        }

        // ── 一级：本地缓存 ──
        if (cache != null) {
            String[] c = cache.get(appTag, target.id);
            if (c != null && c.length >= 2) {
                Resolved r = new Resolved(c[0], c[1], splitParams(c.length > 2 ? c[2] : ""), "cache");
                // 缓存里的类名必须能被宿主 ClassLoader 真的加载到，
                // 否则说明缓存过期（比如 APK 被替换但版本号没变），丢掉重查。
                if (classExists(r.className, cl)) {
                    Log.i(TAG, target.label + " 命中缓存: " + r);
                    memo.put(target.id, r);
                    return r;
                }
                Log.w(TAG, target.label + " 缓存已过期（" + r.className + " 加载不到），重新查询");
            }
        }

        // ── 二级：DexKit 语义查询 ──
        Resolved found = resolveByDexKit(target, apkPath, cl);
        if (found != null) {
            Log.i(TAG, target.label + " DexKit 定位成功: " + found);
            memo.put(target.id, found);
            if (cache != null) {
                cache.put(appTag, target.id, found.className, found.methodName,
                        joinParams(found.paramTypes));
            }
            return found;
        }

        // ── 三级：硬编码兜底 ──
        Resolved fb = resolveHardcoded(target, cl);
        if (fb != null) {
            Log.w(TAG, target.label + " DexKit 未命中，回落到硬编码: " + fb);
            memo.put(target.id, fb);
            // 硬编码成功**不写缓存**：它是兜底值，不是这次版本的可靠结论。
            // 写进去会让下次启动误以为已验证过，反而掩盖「DexKit 从来没成功」这件事。
            return fb;
        }

        // ── 四级：明确失败 ──
        Log.e(TAG, target.label + " 全部定位方式失败（" + target.id
                + "）。本机这个功能将不可用，但不影响其它功能。");
        Resolved dead = new Resolved(null, null, new String[0], "none");
        memo.put(target.id, dead);
        return dead;
    }

    /** 批量解析，返回 id → 结果。任何一个失败都不会中断其余。 */
    Map<String, Resolved> resolveAll(HookTarget[] targets, ClassLoader cl,
                                     String apkPath, String appTag) {
        Map<String, Resolved> out = new HashMap<>();
        for (HookTarget t : targets) {
            try {
                out.put(t.id, resolve(t, cl, apkPath, appTag));
            } catch (Throwable e) {
                // 单个目标出任何意外都不许影响其它目标：
                // 用户的六个功能互相独立，一个坏了不该连坐。
                Log.e(TAG, "解析 " + t.id + " 时抛异常: " + e);
                out.put(t.id, new Resolved(null, null, new String[0], "none"));
            }
        }
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    // DexKit 查询
    // ══════════════════════════════════════════════════════════════

    /**
     * 用 DexKit 按语义特征查。
     *
     * 查询是「逐层收窄」的：
     *   先按方法签名找到一批候选 → 再用字符串/结构特征过滤 → 只有一个才算成功
     * 多于一个也当失败：宁可回落到硬编码，也不要随便挑一个挂上去——
     * 挂错方法往往比挂不上更糟（可能改坏原版逻辑）。
     */
    private Resolved resolveByDexKit(HookTarget target, String apkPath, ClassLoader cl) {
        DexKitBridge b = obtainBridge(apkPath);
        if (b == null) {
            return null;
        }
        try {
            // ── 有语义字符串：直接用 DexKit 的字符串匹配，交给 C++ 层高效筛 ──
            if (target.mustUseStrings.length > 0) {
                Resolved r = findByStrings(b, target);
                if (r != null) {
                    return r;
                }
            }

            // ── 无字符串（或字符串筛不唯一）：按签名 + 结构 ──
            return findBySignatureAndShape(b, target, cl);

        } catch (Throwable e) {
            // DexKit 的异常（native 崩溃、查询语法问题）全部吞掉，
            // 走兜底。注意这里必须 catch Throwable 而不是 Exception，
            // native 层的 UnsatisfiedLinkError 是 Error 的子类。
            Log.e(TAG, target.label + " DexKit 查询异常: " + e);
            return null;
        }
    }

    /**
     * 按「方法体里用到了这些字符串」查。
     *
     * 这是最强的一层：字符串是语义，混淆改不了。
     * 用 MethodMatcher.usingStrings 让 native 层去扫指令流，
     * 比我自己在 java 层遍历快几个数量级。
     */
    private Resolved findByStrings(DexKitBridge b, HookTarget target) {
        // 用链式 DSL，不用 setUsingStrings/setReturnType。
        // 原因（实测得出，不是猜）：DexKit 是 Kotlin 库，那批 setXxx 在
        // Kotlin 侧被标了 @Deprecated(level = HIDDEN)。javap 能看到它们，
        // 但 javac 会把 HIDDEN 级别的合成方法当作不存在，
        // 编译直接报 "cannot find symbol"。
        // 链式方法才是 Kotlin 生成的公开 API，Java 可以正常调用。
        MethodMatcher mm = MethodMatcher.create();
        if (target.mustUseStrings != null && target.mustUseStrings.length > 0) {
            mm.usingStrings(target.mustUseStrings);
        }

        // 顺带把签名约束加上，减少候选。
        // 注意：这里只加返回类型，不加参数类型——
        // 参数类型在混淆后仍然稳定（是类型不是名字），但
        // 不同版本可能有细微差异，加得太死会漏掉。
        if (target.returnType != null) {
            mm.returnType(target.returnType);
        }

        MethodDataList list = b.findMethod(FindMethod.create().matcher(mm));
        if (list == null || list.isEmpty()) {
            return null;
        }
        if (list.size() > 1) {
            // 多个候选时，用参数类型再收一次。
            List<MethodData> narrowed = narrowByParams(list, target.paramTypes);
            if (narrowed.size() == 1) {
                return toResolved(narrowed.get(0), "dexkit");
            }
            Log.w(TAG, target.label + " 字符串命中 " + list.size()
                    + " 个，收窄后 " + narrowed.size() + " 个，不唯一，放弃");
            return null;
        }
        return toResolved(list.get(0), "dexkit");
    }

    /**
     * 按「签名 + 类结构」查。用于字符串特征失效的情况
     * （CHECK_SUM 的无参 void、TRIAL_RENDER 的空串方法）。
     */
    private Resolved findBySignatureAndShape(DexKitBridge b, HookTarget target, ClassLoader cl) {
        // 结构特征落在类上，所以先用 findClass 找到结构匹配的类，
        // 再在该类里找方法。这样比全量 findMethod 快得多。
        if (target.shape != null
                && (target.shape.hasFieldOfType != null
                || target.shape.minBooleanFields > 0
                || target.shape.superClassNotObject != null)) {

            ClassMatcher cm = new ClassMatcher();

            if (target.shape.hasFieldOfType != null) {
                FieldsMatcher fm = FieldsMatcher.create();
                fm.add(FieldMatcher.create().type(target.shape.hasFieldOfType,
                        org.luckypray.dexkit.query.enums.StringMatchType.Contains, false));
                // 这个类型必须被用到（matchType Contains 表示「包含」这个字段即可）
                fm.matchType(org.luckypray.dexkit.query.enums.MatchType.Contains);
                cm.fields(fm);
            }

            // 方法签名约束挂在类上，避免拿到结构相似但方法不存在的类
            if (target.returnType != null && target.paramTypes != null) {
                MethodMatcher mm = MethodMatcher.create();
                mm.returnType(target.returnType);
                if (target.paramTypes.length > 0) {
                    mm.paramTypes(target.paramTypes);
                }
                MethodsMatcher mms = MethodsMatcher.create();
                mms.add(mm);
                mms.matchType(org.luckypray.dexkit.query.enums.MatchType.Contains);
                cm.methods(mms);
            }

            ClassDataList classes = b.findClass(FindClass.create().matcher(cm));
            if (classes != null && !classes.isEmpty()) {
                Resolved r = pickFromClasses(classes, target, b, cl);
                if (r != null) {
                    return r;
                }
            }
        }

        // 最后退化为「纯签名查找」。
        // 这一步只在前面都没命中时走，且要求结果唯一，
        // 否则容易挂错方法。
        if (target.returnType != null) {
            MethodMatcher mm = MethodMatcher.create();
            mm.returnType(target.returnType);
            if (target.paramTypes != null && target.paramTypes.length > 0) {
                mm.paramTypes(target.paramTypes);
            } else if (target.paramTypes != null && target.paramTypes.length == 0
                    && target.shape != null && target.shape.minBooleanFields > 0) {
                // 无参方法：显式要求 0 个参数，否则会把有参的也捞进来
                mm.paramCount(0);
            }
            MethodDataList list = b.findMethod(FindMethod.create().matcher(mm));
            if (list == null || list.isEmpty()) {
                return null;
            }
            if (list.size() > 1) {
                Log.w(TAG, target.label + " 纯签名命中 " + list.size() + " 个，不唯一，放弃");
                return null;
            }
            return toResolved(list.get(0), "dexkit");
        }
        return null;
    }

    /** 从候选类里挑出唯一满足结构条件的那个，并在其中找到目标方法。 */
    private Resolved pickFromClasses(ClassDataList classes, HookTarget target,
                                    DexKitBridge b, ClassLoader cl) {
        List<ClassData> ok = new ArrayList<>();
        for (ClassData cd : classes) {
            if (shapeMatches(cd, target.shape, cl)) {
                ok.add(cd);
            }
        }
        if (ok.size() != 1) {
            Log.w(TAG, target.label + " 结构匹配类 " + classes.size()
                    + " 个，过滤后 " + ok.size() + " 个，不唯一");
            return null;
        }
        ClassData cd = ok.get(0);
        // 在这个类里按签名找方法
        MethodMatcher mm = MethodMatcher.create();
        mm.declaredClass(cd.getName());
        if (target.returnType != null) {
            mm.returnType(target.returnType);
        }
        if (target.paramTypes != null && target.paramTypes.length > 0) {
            mm.paramTypes(target.paramTypes);
        } else if (target.paramTypes != null && target.paramTypes.length == 0) {
            mm.paramCount(0);
        }
        MethodDataList list = b.findMethod(FindMethod.create().matcher(mm));
        if (list == null || list.size() != 1) {
            Log.w(TAG, target.label + " 类 " + cd.getName() + " 内方法数 "
                    + (list == null ? 0 : list.size()) + "，不唯一");
            return null;
        }
        return toResolved(list.get(0), "dexkit");
    }

    /**
     * 用类结构复核特征。
     *
     * ── 两种运行环境，判定方式不同 ──
     *   · cl != null：宿主进程，反射拿到真实 Class，字段类型最准。
     *   · cl == null：模块自己的进程，只能看 DexKit 给的 ClassData。
     *     ClassData 里有 getFields()、getSuperClass()，足够判断
     *     「boolean 字段至少 N 个」「有某类型的字段」「父类不是 Object」，
     *     结论与反射一致。区别只是拿不到运行时的合成字段，但 dex 里
     *     写的字段就是编译产物本身，反而更贴近平铺特征的本意。
     */
    private boolean shapeMatches(ClassData cd, HookTarget.StructShape shape, ClassLoader cl) {
        if (shape == null) {
            return true;
        }
        if (cd == null) {
            return false;
        }
        if (cl != null) {
            return shapeMatchesByReflect(cd, shape, cl);
        }
        return shapeMatchesByDex(cd, shape);
    }

    /**
     * 反射版结构校验（宿主进程用）。
     */
    private boolean shapeMatchesByReflect(ClassData cd, HookTarget.StructShape shape,
                                          ClassLoader cl) {
        try {
            Class<?> c = Class.forName(cd.getName(), false, cl);
            if (c == null) {
                return false;
            }
            if (shape.superClassNotObject != null) {
                Class<?> sup = c.getSuperclass();
                if (sup == null || sup == Object.class) {
                    return false;
                }
            }

            if (shape.minFields > 0 || shape.minBooleanFields > 0
                    || shape.hasFieldOfType != null) {
                java.lang.reflect.Field[] fs = c.getDeclaredFields();
                if (fs.length < shape.minFields) {
                    return false;
                }
                int bools = 0;
                boolean hasType = shape.hasFieldOfType == null;
                for (java.lang.reflect.Field f : fs) {
                    if (f.getType() == boolean.class) {
                        bools++;
                    }
                    if (!hasType) {
                        String tn = f.getType().getName();
                        String sn = f.getType().getSimpleName();
                        if (tn.contains(shape.hasFieldOfType)
                                || sn.contains(shape.hasFieldOfType)) {
                            hasType = true;
                        }
                    }
                }
                if (bools < shape.minBooleanFields) {
                    return false;
                }
                return hasType;
            }
            return true;
        } catch (Throwable e) {
            return false;
        }
    }

    /**
     * DexKit 版结构校验（模块自己进程用）。
     *
     * 全部数据来自 ClassData，不碰 ClassLoader。
     */
    private boolean shapeMatchesByDex(ClassData cd, HookTarget.StructShape shape) {
        try {
            if (shape.superClassNotObject != null) {
                ClassData sup = cd.getSuperClass();
                if (sup == null || "java.lang.Object".equals(sup.getName())) {
                    return false;
                }
            }
            if (shape.minFields <= 0 && shape.minBooleanFields <= 0
                    && shape.hasFieldOfType == null) {
                return true;
            }
            FieldDataList fs = cd.getFields();
            int total = fs == null ? 0 : fs.size();
            if (total < shape.minFields) {
                return false;
            }
            int bools = 0;
            boolean hasType = shape.hasFieldOfType == null;
            if (fs != null) {
                for (FieldData f : fs) {
                    String tn = f.getTypeName();
                    if ("boolean".equals(tn)) {
                        bools++;
                    }
                    if (!hasType && tn != null && tn.contains(shape.hasFieldOfType)) {
                        hasType = true;
                    }
                }
            }
            if (bools < shape.minBooleanFields) {
                return false;
            }
            return hasType;
        } catch (Throwable e) {
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 硬编码兜底
    // ══════════════════════════════════════════════════════════════

    /**
     * 用写死的名字去碰。这是最后一道保险，不是主力。
     *
     * 必须是「类和方法都真的存在」才算成功——只判断字符串非空没有意义，
     * 类不存在的话挂上去照样失败，还掩盖了真正的问题。
     *
     * ── 两种运行环境 ──
     *   · cl != null：宿主进程，反射问一句就够了。
     *   · cl == null：模块自己的进程，用 DexKit 查 dex，
     *     确认这个类里有这个名的方法。语义与反射相同。
     */
    private Resolved resolveHardcoded(HookTarget target, ClassLoader cl) {
        String fb = target.hardcodedFallback;
        if (fb == null || !fb.contains("#")) {
            return null;
        }
        int i = fb.indexOf('#');
        String cn = fb.substring(0, i);
        String mn = fb.substring(i + 1);
        if (!classExists(cn, cl)) {
            Log.w(TAG, "硬编码兜底 " + cn + " 在宿主里不存在");
            return null;
        }
        // 方法存在性也验一下，避免类对了方法改名了还当成成功
        if (!methodExists(cn, mn, cl)) {
            Log.w(TAG, "硬编码兜底 " + cn + "#" + mn + " 方法不存在");
            return null;
        }
        return new Resolved(cn, mn, target.paramTypes == null ? new String[0] : target.paramTypes,
                "hardcoded");
    }

    /**
     * 判断类里有没有这个名的方法。
     *
     * cl == null 时走 DexKit：getClassData 拿到 ClassData，
     * 再遍历它自己的 getMethods()。注意不能用 bridge.getMethodData(
     * "类名#方法名") —— 那个字符串格式要带签名，名字对不上会直接返回
     * null，反而不如自己遍历稳。
     */
    private boolean methodExists(String cn, String mn, ClassLoader cl) {
        if (cl != null) {
            try {
                Class<?> c = Class.forName(cn, false, cl);
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(mn)) {
                        return true;
                    }
                }
            } catch (Throwable e) {
                return false;
            }
            return false;
        }
        DexKitBridge b = bridge;
        if (b == null) {
            return false;
        }
        try {
            ClassData cd = b.getClassData(cn);
            if (cd == null) {
                return false;
            }
            MethodDataList ms = cd.getMethods();
            if (ms == null) {
                return false;
            }
            for (MethodData md : ms) {
                if (mn.equals(md.getName())) {
                    return true;
                }
            }
            return false;
        } catch (Throwable e) {
            return false;
        }
    }

    // ══════════════════════════════════════════════════════════════
    // 工具
    // ══════════════════════════════════════════════════════════════

    /** 拿到（或首次创建）DexKit bridge。失败返回 null，不抛。 */
    private DexKitBridge obtainBridge(String apkPath) {
        if (bridge != null) {
            return bridge;
        }
        if (bridgeFailed) {
            return null;
        }
        if (apkPath == null || !new java.io.File(apkPath).isFile()) {
            Log.e(TAG, "宿主 APK 路径无效: " + apkPath);
            bridgeFailed = true;
            return null;
        }
        try {
            bridge = DexKitBridge.create(apkPath);
            Log.i(TAG, "DexKit bridge 已创建，dex 数=" + bridge.getDexNum());
            return bridge;
        } catch (Throwable e) {
            Log.e(TAG, "DexKit bridge 创建失败: " + e);
            bridgeFailed = true;
            return null;
        }
    }

    /** 在候选里按参数类型再筛一层。 */
    private List<MethodData> narrowByParams(MethodDataList list, String[] want) {
        List<MethodData> out = new ArrayList<>();
        if (want == null) {
            return out;
        }
        for (MethodData md : list) {
            List<String> ps = md.getParamTypeNames();
            if (ps == null || ps.size() != want.length) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < want.length; i++) {
                if (!ps.get(i).contains(want[i])) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                out.add(md);
            }
        }
        return out;
    }

    private Resolved toResolved(MethodData md, String source) {
        List<String> ps = md.getParamTypeNames();
        String[] pt = ps == null ? new String[0] : ps.toArray(new String[0]);
        return new Resolved(md.getDeclaredClassName(), md.getName(), pt, source);
    }

    /**
     * 判断一个类是否真的存在。
     *
     * ── 两种运行环境，判定方式不同 ──
     *   · cl != null：跑在宿主进程里，让宿主的 ClassLoader 真加载一次。
     *     这是最强证据，加载得到才敢往上挂。
     *   · cl == null：跑在模块自己的进程里。那里根本加载不到
     *     com.hihonor.ouc 的类——不同 APK、不同 ClassLoader 链，也没
     *     那套依赖。但解析本身并不需要加载类，只需要确认它在 dex 里
     *     存在。这时改用 DexKit 直接查 dex，结论同样可靠。
     *
     * 这个分支是「解析引擎脱离宿主进程独立运行」的关键：少了它，
     * 设置页的解析按钮就只能靠一个假的成功来自我安慰。
     */
    private boolean classExists(String name, ClassLoader cl) {
        if (name == null) {
            return false;
        }
        if (cl != null) {
            try {
                Class.forName(name, false, cl);
                return true;
            } catch (Throwable e) {
                return false;
            }
        }
        DexKitBridge b = bridge;
        if (b == null) {
            return false;
        }
        try {
            return b.getClassData(name) != null;
        } catch (Throwable e) {
            return false;
        }
    }

    private static String[] splitParams(String s) {
        if (s == null || s.isEmpty()) {
            return new String[0];
        }
        return s.split(",");
    }

    private static String joinParams(String[] a) {
        if (a == null || a.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String x : a) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(x);
        }
        return sb.toString();
    }

    /** 释放桥接。宿主进程退出前调用，避免 native 内存泄漏。 */
    void close() {
        if (bridge != null) {
            try {
                bridge.close();
            } catch (Throwable ignored) {
            }
            bridge = null;
        }
    }
}