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
# （manifest 不声明组件，hook 里也不写它的类名字符串），
# 所以不 keep，让 R8 正常裁剪混淆。

# 注意：这里不需要 keep 任何 Activity —— 本模块一个 Activity 都没有。
