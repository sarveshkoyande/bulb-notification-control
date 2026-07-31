# Project rules
-keep class com.wipro.bulb.control.** { *; }
-keepclassmembers class com.wipro.bulb.control.** { *; }

# Thing (Tuya) Smart Life App SDK — required by the integration guide
-keep class com.thingclips.**{*;}
-dontwarn com.thingclips.**
-keep class com.alibaba.fastjson.**{*;}
-dontwarn com.alibaba.fastjson.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
