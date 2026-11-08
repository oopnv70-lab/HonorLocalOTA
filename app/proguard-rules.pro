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