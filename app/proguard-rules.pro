-dontwarn io.github.libxposed.annotation.**

# java_init.list 里写的是全限定类名，混淆后必须同步改写内容。
# 打开 -obfuscation 时这一行让 R8 把 list 里的名字一起替换。
-adaptresourcefilecontents META-INF/xposed/java_init.list

# 模块入口：由 LSPosed 按 java_init.list 的类名反射实例化，类名和无参构造必须保留。
-keep,allowoptimization,allowobfuscation public class * extends io.github.libxposed.api.XposedModule {
    public <init>();
}

# 两个 Activity 都靠 AndroidManifest 的类名引用（Manifest 里的字符串不会随混淆改写），
# 而且 OtaActivity 还会被 OUC 进程里的 hook 用 "io.github.honorlocalota.OtaActivity"
# 这个写死的字符串跨应用拉起，所以类名绝对不能混淆。
-keep class io.github.honorlocalota.MainActivity {
    public <init>();
}
-keep class io.github.honorlocalota.OtaActivity {
    public <init>();
}
