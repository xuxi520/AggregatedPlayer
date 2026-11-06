# =============================================================================
# 聚合播放器 · Release 混淆 / 加固规则（R8）
#
# 目标：release 包开启 minify + shrink，代码名与成员名混淆、去掉无用资源，
#       同时保证「混淆后仍能正常工作」——下面每条 keep 都有具体的踩坑理由。
#
# 启用位置：androidApp/build.gradle.kts → buildTypes.release
# =============================================================================

# -----------------------------------------------------------------------------
# 0) 基础：保留注解与签名
#    @JavascriptInterface / @Serializable 都靠注解定位；注解被抹掉后下面
#    第 1、2 节的规则就再也匹配不到成员了。
# -----------------------------------------------------------------------------
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# 崩溃栈可读：保留行号（便于定位），但把源文件名混淆掉（不泄露源码结构）
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# -----------------------------------------------------------------------------
# 1) kotlinx.serialization —— 本地持久化（站点表 / 规则 / 设置 / 标签会话）
#
#    踩坑：R8 会重命名 Companion.serializer() 与插件生成的 $serializer 类，
#    放行不足时反序列化直接抛 SerializationException → 用户本地数据全部读不出来。
#    注意：官方文档里的 `-if ... class <1>$<3>` 写法依赖 R8 的通配符序号，
#    在 AGP 9.1 / R8 上会报 "Wildcard <3> is invalid"，故改用等价的「按包 + 按类名」放行。
# -----------------------------------------------------------------------------
# 保留可序列化类的 Companion（含 companion.serializer() 入口）
-keepclassmembers class org.aggregated.player.** {
    *** Companion;
}

# 保留 serializer(...) 方法本身
-keepclasseswithmembers class org.aggregated.player.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 保留序列化插件生成的 $serializer 类（反序列化入口，缺了必崩）
-keep class org.aggregated.player.**$$serializer { *; }

# 序列化类的字段名就是 JSON key：已存到本地的数据跨版本读取时 key 不能变，
# 因此保留模型字段名（类名与方法名照常混淆）。
-keepclassmembers class org.aggregated.player.domain.model.** {
    <fields>;
}

# 枚举常量名同样是序列化的 key（PlaybackMode 等）：R8 默认会重命名常量，
# 这条标准规则保留 values()/valueOf()，从而连带保留常量名。
-keepclassmembers enum org.aggregated.player.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# -----------------------------------------------------------------------------
# 2) WebView 的 JS 桥（@JavascriptInterface）
#
#    踩坑：页面 JS 直接按名字调这些方法（如 onMediaFound），R8 重命名后 JS 侧
#    调不到 → 嗅探回传 / 路由重注入静默失效。
#    另外**不能**只靠 keepclassmembers：R8 可能把这个仅内部使用的桥类
#    inline 进调用方或与其他类合并，那样方法就不在类上了，JS 一样调不到。
#    故用 -keep / -keepclasseswithmembers 禁止合并与内联。
# -----------------------------------------------------------------------------
-keepclasseswithmembers,includedescriptorclasses class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keep class org.aggregated.player.platform.web.AdBlockJsBridge { *; }

# -----------------------------------------------------------------------------
# 3) 依赖库
#    Compose / Media3 / kotlinx-coroutines 自带 consumer rules，随依赖带入；
#    这里只补上它们在可选依赖上的告警抑制，避免 R8 因缺类中断打包。
# -----------------------------------------------------------------------------
-dontwarn org.jetbrains.annotations.**
-dontwarn kotlinx.coroutines.**
-dontwarn org.jetbrains.compose.**

# -----------------------------------------------------------------------------
# 4) 加固：release 去掉 debug 级日志
#    保留 w/e —— 崩溃诊断与「设置页复制崩溃日志」功能依赖它们；
#    只抹掉 v/d：既减少 logcat 信息泄露，也让包体与运行开销略降。
# -----------------------------------------------------------------------------
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
