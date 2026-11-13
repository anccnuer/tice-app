# 保留 Room 生成类与 Kotlin 数据类，避免 release 混淆后反序列化失败
-keep class com.example.tice.data.db.** { *; }
-keep class com.example.tice.xlsx.** { *; }
-keep class com.example.tice.backup.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlinx.parcelize.**
