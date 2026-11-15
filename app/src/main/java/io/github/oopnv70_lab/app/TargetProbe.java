package io.github.oopnv70_lab.app;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在本模块自己的进程里，对「软件更新」的 APK 做一次目标定位。
 *
 * ══════════════════════════════════════════════════════════════
 * 为什么这件事在本进程就能做（实测结论，不是推断）
 * ══════════════════════════════════════════════════════════════
 * 本机上「软件更新」是**系统应用**：
 *     pm list packages -s        →  含 com.hihonor.ouc
 *     pm path com.hihonor.ouc    →  /system/app/HnOUC/HnOUC.apk
 *     ls -lZ                     →  -rw-r--r-- root root u:object_r:system_file:s0
 *     /data/app 下没有它的更新版本
 *
 * system_file 这个标签本身就对 App 开放——每个 App 都必须能读
 * /system/framework/framework-res.apk（同一个标签）才能起界面。
 * 已用本 App 的身份实测：能打开 HnOUC.apk，能 ls /system/app。
 *
 * 所以定位需要的两样东西都到手了：
 *   · APK 文件      → 直接读，不需要 root
 *   · 解析引擎      → DexKit，so 就在本 APK 里（useLegacyPackaging true）
 * 定位是纯静态分析 dex，不加载目标类、不调用目标方法，
 * 和「跑在谁的进程里」无关。
 *
 * ── 这跟 QQ 模块（QAuxiliary）为什么不冲突 ──
 *   QQ 是用户安装的 App，APK 在 /data/app/~~xxx/base.apk，
 *   标签 apk_data_file 且带 MCS cat（u:...:s0:c512,c768），
 *   别的 App 连 stat 都过不去。所以 QAuxiliary 只能把代码注入 QQ 进程，
 *   在里面 applicationInfo.sourceDir 是白送的。
 *   荣耀「软件更新」是 system app，没有这条隔离，不需要注入那一步。
 *
 * ── 之前为什么绕了远路 ──
 *   我先假定「读不到别人的 APK」，于是做了一整套跨进程方案：
 *   root 拉起 OUC → 宿主机进程写报告文件 → 模块 App root 读文件 →
 *   再配一个系统浮窗显示进度。整套东西解决的是不存在的障碍。
 *   实测一句 ls -lZ 就能否掉那个前提。
 */
final class TargetProbe {

    private static final String TAG = "HonorLocalOTA";

    /** 实测路径。系统应用的位置随 ROM 固定，不会因为版本升级而变。 */
    private static final String OUC_APK = "/system/app/HnOUC/HnOUC.apk";

    /** 进度回调，用于界面显示当前做到哪一步。 */
    interface Progress {
        void onStep(String line);
    }

    /** 一次探测的结果。 */
    static final class Report {
        /** 按 HookTarget.ALL 顺序的定位结果。 */
        final Map<String, TargetResolver.Resolved> results = new LinkedHashMap<>();
        /** 实际解析的 APK 路径。null 表示没找到。 */
        String apkPath;
        /** 这个 APK 是怎么找到的，写给人看。 */
        String apkSource = "";
        /** APK 的文件指纹（字节数_修改时间），换版本必变。 */
        String apkTag;
        /** APK 里的 dex 数量。 */
        int dexCount;
        /** libdexkit.so 是否加载成功。 */
        boolean nativeReady;
        /** 出过的错。 */
        final List<String> errors = new ArrayList<>();

        /** 有几个目标真正定位到了。 */
        int resolvedCount() {
            int n = 0;
            for (TargetResolver.Resolved r : results.values()) {
                if (r.className != null) {
                    n++;
                }
            }
            return n;
        }

        /** 拿到某个目标的结果，没有则 null。 */
        TargetResolver.Resolved get(String id) {
            return results.get(id);
        }

        /** 一句话概括。 */
        String summary() {
            if (apkPath == null) {
                return "没找到「软件更新」的安装包（本机可能不是荣耀系统）";
            }
            if (!nativeReady) {
                return "libdexkit.so 加载失败，无法解析";
            }
            return "已定位 " + resolvedCount() + " / " + results.size()
                    + " 个目标（APK 内 dex " + dexCount + " 个）";
        }
    }

    private TargetProbe() {
    }

    /**
     * 执行一次定位。
     *
     * 必须在后台线程调用：首次解析一个 27MB 的 APK 要几秒，主线程必 ANR。
     * 任何一步失败都不抛，原因写进 Report.errors，界面能逐条显示。
     */
    static Report probe(Context ctx, Progress p) {
        Report rep = new Report();

        // ── 一、找 APK ──
        step(p, "查找「软件更新」的安装包…");
        rep.apkPath = resolveApk(ctx, rep);
        if (rep.apkPath == null) {
            rep.errors.add("这些位置都没有可读的「软件更新」安装包："
                    + OUC_APK + "、/system/app/*/*.apk");
            return rep;
        }
        rep.apkTag = tag(rep.apkPath);
        Log.i(TAG, "解析目标 APK: " + rep.apkPath + "（" + rep.apkSource + "）"
                + " 指纹 " + rep.apkTag);

        // ── 二、加载 libdexkit.so ──
        step(p, "加载解析引擎…");
        rep.nativeReady = loadNative(ctx);
        if (!rep.nativeReady) {
            rep.errors.add("libdexkit.so 加载失败。检查 packaging.jniLibs.useLegacyPackaging "
                    + "是否为 true（AGP 9 默认 false 时 System.loadLibrary 找不到它）。");
            return rep;
        }

        // ── 三、解析 ──
        step(p, "解析 dex，按语义特征查找类和方法（首次需要几秒）…");
        TargetResolver resolver = new TargetResolver(null);
        try {
            DexKitBridge b = DexKitBridge.create(rep.apkPath);
            if (b == null) {
                rep.errors.add("DexKitBridge.create 返回 null");
                return rep;
            }
            rep.dexCount = b.getDexNum();
            resolver.attachBridge(b);
        } catch (Throwable t) {
            Log.e(TAG, "创建 DexKitBridge 失败: " + t);
            rep.errors.add("创建 DexKitBridge 失败: " + t);
            return rep;
        }

        // ClassLoader 传 null：这里不是宿主进程，类存在性验证走 DexKit 查 dex。
        // 注意这不是降级——传宿主的 ClassLoader 本来也拿不到，
        // 而 DexKit 查类是否在 APK 里，对静态定位来说等价且更直接。
        Map<String, TargetResolver.Resolved> out;
        try {
            out = resolver.resolveAll(HookTarget.ALL, null, rep.apkPath, rep.apkTag);
        } catch (Throwable t) {
            Log.e(TAG, "resolveAll 异常: " + t);
            rep.errors.add("解析过程异常: " + t);
            return rep;
        }
        for (HookTarget t : HookTarget.ALL) {
            TargetResolver.Resolved r = out.get(t.id);
            rep.results.put(t.id, r != null
                    ? r : new TargetResolver.Resolved(null, null, new String[0], "none"));
        }
        step(p, "解析完成：" + rep.resolvedCount() + " / " + rep.results.size());
        Log.i(TAG, "定位完成: " + rep.summary());
        return rep;
    }

    /**
     * 找「软件更新」的 APK。
     *
     * ── 顺序 ──
     *   1. PackageManager：最权威，能拿到系统认定的 sourceDir。
     *      system app 通常对 App 可见；若被包可见性挡住会抛异常，正常跳过。
     *   2. 实测固定路径 /system/app/HnOUC/HnOUC.apk。
     *   3. 扫 /system/app/ 下每个子目录里的 apk：荣耀改目录名时还能兜住。
     *
     * ── 为什么后两步不需要 PackageManager ──
     *   /system 分区是全局可读的（system_file 标签），列目录、
     *   读文件都对 App 开放，已实测。所以包可见性在这里根本不是障碍。
     *
     * 返回的路径必须真的能打开，否则会一路带出一个难懂的 DexKit 报错。
     */
    private static String resolveApk(Context ctx, Report rep) {
        // 1. PackageManager
        try {
            ApplicationInfo ai = ctx.getPackageManager()
                    .getApplicationInfo(Const.PKG_OUC, 0);
            if (ai != null && ai.sourceDir != null && readable(ai.sourceDir)) {
                rep.apkSource = "PackageManager 报告的安装位置";
                return ai.sourceDir;
            }
        } catch (Throwable t) {
            // 包可见性过滤（Android 11+ 未声明 queries 时）会走到这里，
            // 不影响后面两步，因为 system_file 对 App 本来就可读。
            Log.i(TAG, "getApplicationInfo(" + Const.PKG_OUC + ") 不可用，改走文件系统: " + t);
        }

        // 2. 实测固定路径
        if (readable(OUC_APK)) {
            rep.apkSource = "系统应用固定位置";
            return OUC_APK;
        }

        // 3. 扫 /system/app/*
        File dir = new File("/system/app");
        File[] subs = dir.listFiles();
        if (subs != null) {
            // 目录名优先按 HnOUC 找，再退化为同名 apk
            for (File sub : subs) {
                if (!sub.isDirectory()) {
                    continue;
                }
                String n = sub.getName().toLowerCase();
                if (!n.contains("ouc") && !n.contains("honor") && !n.contains("hn")) {
                    continue;
                }
                File[] fs = sub.listFiles();
                if (fs == null) {
                    continue;
                }
                for (File f : fs) {
                    if (f.getName().endsWith(".apk") && readable(f.getAbsolutePath())) {
                        rep.apkSource = "扫描 /system/app 找到（目录 " + sub.getName() + "）";
                        return f.getAbsolutePath();
                    }
                }
            }
        }
        return null;
    }

    private static boolean readable(String path) {
        try {
            File f = new File(path);
            return f.isFile() && f.canRead() && f.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 加载 DexKit native 库。
     *
     * 这里是本 App 自己的进程，so 在自己的 nativeLibraryDir 里，
     * System.loadLibrary 一步到位（前提 useLegacyPackaging = true）。
     * 失败时按绝对路径再试一次，兜住 ROM 的非常规布局。
     */
    private static boolean loadNative(Context ctx) {
        try {
            System.loadLibrary("dexkit");
            Log.i(TAG, "libdexkit.so 已通过 System.loadLibrary 加载");
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "System.loadLibrary(dexkit) 失败，改按绝对路径试: " + t);
        }
        try {
            ApplicationInfo self = ctx.getApplicationInfo();
            String abi = android.os.Build.SUPPORTED_ABIS.length > 0
                    ? android.os.Build.SUPPORTED_ABIS[0] : "arm64-v8a";
            String apkDir = self.sourceDir == null ? null : new File(self.sourceDir).getParent();
            String[] cands = {
                    self.nativeLibraryDir == null ? null : self.nativeLibraryDir + "/libdexkit.so",
                    apkDir == null ? null : apkDir + "/lib/" + abi + "/libdexkit.so",
            };
            for (String p : cands) {
                if (p == null) {
                    continue;
                }
                File f = new File(p);
                if (!f.isFile() || !f.canRead()) {
                    continue;
                }
                try {
                    System.load(p);
                    Log.i(TAG, "libdexkit.so 已从 " + p + " 加载");
                    return true;
                } catch (Throwable t) {
                    Log.w(TAG, "从 " + p + " 加载失败: " + t);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "定位本 App 的 native 目录失败: " + t);
        }
        Log.e(TAG, "libdexkit.so 全部候选路径均失败");
        return false;
    }

    /**
     * 缓存命名空间 + 换版本检测用的指纹。
     *
     * 用「文件尺寸_修改时间」而不是 versionCode：compileSdk 37 的公开 SDK 里
     * ApplicationInfo.versionCode / longVersionCode 对第三方不可见（这正是
     * 构建 #44 失败的原因）。而且真正要防的是「APK 内容变了」，
     * 文件指纹比版本号数字更贴近这件事。
     */
    private static String tag(String apkPath) {
        try {
            File f = new File(apkPath);
            return Const.PKG_OUC + ":" + f.length() + "_" + f.lastModified();
        } catch (Throwable t) {
            return Const.PKG_OUC + ":0";
        }
    }

    private static void step(Progress p, String line) {
        if (p != null) {
            try {
                p.onStep(line);
            } catch (Throwable ignored) {
                // 进度显示出错不能影响解析。
            }
        }
    }
}