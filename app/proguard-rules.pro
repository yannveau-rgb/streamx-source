# Règles R8 de StreamX.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
# Bibliothèques réseau : classes optionnelles absentes sur Android (Conscrypt, BouncyCastle, OpenJSSE…).
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn okhttp3.internal.platform.**
-dontwarn javax.annotation.**
# Le code de l'application reste lisible dans les traces d'incident (utile pour le dépannage à distance).
-keepnames class eu.streamx.app.** { *; }

# Onglet Tube (NewPipeExtractor) : bibliothèque utilisant la réflexion (protobuf, Rhino pour le déchiffrement des liens) ; on la garde intacte.
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class com.grack.nanojson.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn jdk.dynalink.**
-dontwarn com.google.protobuf.**
-dontwarn org.jsoup.**
-dontwarn com.google.re2j.**
-dontwarn com.google.errorprone.annotations.**
