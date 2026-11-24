# 默认保留 AccessibilityService 与数据模型（供反射/JSON）
-keep class * extends android.accessibilityservice.AccessibilityService { *; }
-keep class com.ayu.authentic.model.** { *; }
-keep class com.ayu.authentic.core.** { *; }
-keepattributes *Annotation*
