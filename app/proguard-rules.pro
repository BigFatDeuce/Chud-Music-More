# NewPipeExtractor uses reflection-free code but Rhino (JavaScript for signature deciphering) needs its classes.
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.tools.**
-keep class org.schabi.newpipe.extractor.** { *; }
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**
# smbj + its crypto and logging
-keep class com.hierynomus.** { *; }
-keep class net.engio.mbassy.** { *; }
-keep class com.rapid7.client.dcerpc.** { *; }
# smbj-rpc throws java.rmi exceptions (absent on Android) only when an answer is malformed; LanDiscovery.shares() handles it.
-dontwarn java.rmi.**
-dontwarn org.slf4j.**
-dontwarn javax.el.**
-dontwarn org.ietf.jgss.**
-dontwarn com.sun.**
# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; *; }
