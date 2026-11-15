-dontwarn io.github.libxposed.annotation.**

# java_init.list 里写的是全限定类名，开混淆后必须同步改写文件内容，
# 否则 LSPosed 按旧名字找不到入口类。
-adaptresourcefilecontents META-INF/xposed/java_init.list

# 模块入口：由 LSPosed 按 java_init.list 的类名反射实例化，
# 类名和无参构造必须保留（允许混淆，上面的 adapt 会同步改 list）。
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# PanelView 是纯内部类，没有任何外部按名字引用它
# （hook 里也不写它的类名字符串），
# 所以不 keep，让 R8 正常裁剪混淆。

# SettingsActivity 由 AndroidManifest 的 android:name=".SettingsActivity" 引用。
# AGP 会自动为 manifest 里声明的四大组件生成 keep 规则，这里显式写一遍是
# 为了防「agp 版本变化导致规则缺失」——类名一旦被混淆，桌面图标点开就崩。
-keep class io.github.oopnv70_lab.app.SettingsActivity { *; }

# Theme / SwitchButton / SwitchStore 是纯内部实现，由上面的类直接 new 出来，
# 不按名字反射，因此不 keep。

# ══════════════════════════════════════════════════════════════
# DexKit 保留规则
# ══════════════════════════════════════════════════════════════
# DexKit 的 Kotlin 层会按名字反射到自己的 native 桥接类
# （DexKitBridge 及其 Companion），这些名字一旦被混淆，
# native 侧按 JNI 注册的方法名就对不上，表现为
# findClass 返回空表或直接 JNI abort。
# 这里整体 keep 整个包，代价是几百个类不进混淆，
# 但不影响宿主 dex 的定位结果（那是运行时扫宿主 APK 得到的）。
-keep class org.luckypray.dexkit.** { *; }
-dontwarn org.luckypray.dexkit.**

# DexKit 依赖 Kotlin 标准库；本项目是纯 Java 没有 Kotlin 插件，
# 因此这些类不会被 R8 的 Kotlin 元数据规则处理，
# 需要显式忽略缺失的注解与协程类，否则 release 构建会因
# 「R8 找不到 kotlin.Metadata」报错。
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn com.google.flatbuffers.**
-keep class com.google.flatbuffers.** { *; }
-dontwarn dev.rikka.ndk.**