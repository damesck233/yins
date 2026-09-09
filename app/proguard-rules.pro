# Xposed entry point and everything it reaches by reflection / class name.
-keep class moe.damesck.yins.hook.** { *; }
-keep class moe.damesck.yins.ModuleStatus { *; }
-keep class moe.damesck.yins.data.PolicyContract { *; }
-keep class moe.damesck.yins.data.Mode { *; }
-keep class moe.damesck.yins.data.PolicySnapshot { *; }
-keep class moe.damesck.yins.YLog { *; }
# Resource ids referenced from the MediaProvider hook must survive (used via PackageManager resources).
-keepclassmembers class moe.damesck.yins.R$drawable { *; }
-keepclassmembers class moe.damesck.yins.R$string { *; }

# Xposed API is provided by the framework at runtime.
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }

# libsu
-keep class com.topjohnwu.superuser.** { *; }
-dontwarn com.topjohnwu.superuser.**
