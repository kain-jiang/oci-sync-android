# R8 混淆规则
#
# kotlinx-serialization:1.11 的 aar 自带 consumer rules(自动应用),此处保留核心规则兜底
# 确保 core 模块 @Serializable 模型字段名保持不变(序列化兼容,见 docs/04-crypto-security.md §5)

## kotlinx.serialization ##
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keep,includedescriptorclasses class com.tiramission.ocisync.core.**$$serializer { *; }
-keepclassmembers class com.tiramission.ocisync.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.tiramission.ocisync.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}

## OkHttp 5 自带 consumer rules;以下为兜底 ##
-dontwarn okhttp3.**
-dontwarn okio.**
