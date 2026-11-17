-dontobfuscate
-dontoptimize
-keep class com.bb10.** { *; }
-keep class org.lsposed.hiddenapibypass.** { *; }

-keep class com.sun.mail.** { *; }
-keep class javax.mail.** { *; }
-keep class javax.activation.** { *; }
-keep class myjava.awt.datatransfer.** { *; }
-dontwarn java.awt.**
-dontwarn javax.security.**
-dontwarn org.apache.harmony.**
-dontwarn com.sun.mail.**
-dontwarn javax.activation.**

-dontwarn de.robv.android.xposed.**
-dontwarn android.**
-dontwarn com.android.**
