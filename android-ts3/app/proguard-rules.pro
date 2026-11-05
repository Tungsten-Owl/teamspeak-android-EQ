# ts3j and Bouncy Castle use reflection in a few places; keep their names.
-keep class com.github.manevolent.ts3j.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class io.github.jaredmdobson.concentus.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn org.xbill.**
-dontwarn org.ini4j.**
-dontwarn org.apache.commons.lang.**
