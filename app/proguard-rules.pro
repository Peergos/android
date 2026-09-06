# What R8 may not rename or remove.
#
# Almost everything can be: peergos' own code calls neither Class.forName nor
# ServiceLoader, so nothing of ours is reached by name at runtime, and the fat
# server libraries in Peergos.jar - jetty, netty, h2, postgres, redis, jline,
# bouncycastle, jackson, jnr - are referenced by no peergos class, so R8 drops
# them whole rather than renaming them. What is left below is the handful of
# places where a name written somewhere outside the dex has to keep matching.

# --- native code ----------------------------------------------------------
# src/main/cpp exports Java_peergos_android_ScryptAndroid_crypto_1scrypt and
# Java_peergos_server_crypto_JniTweetNacl_*. A JNI symbol spells out the class
# and method it binds to, so renaming either end silently unbinds them and the
# call fails when it is first made, which for these is during login.
-keep class peergos.android.ScryptAndroid {
    native <methods>;
}
-keep class peergos.server.crypto.JniTweetNacl {
    *;
}

# --- sqlite ---------------------------------------------------------------
# Peergos.jar carries sqlite-jdbc, natives and all, including a Linux-Android
# build, so this one really runs here. Its .so reaches back into java by name:
# loading it runs NativeDB's static initialiser, which immediately looks up the
# long field "pointer" on that class, and the whole class is littered with
# further lookups and callbacks. Renaming any of it aborts the runtime - not an
# exception one can catch, the process goes - the first time a database is
# opened, which is during startup.
-keep class org.sqlite.** { *; }

# --- types recovered from a generic superclass ----------------------------
# A handful of libraries here read their own type argument back at runtime, with
# getClass().getGenericSuperclass() cast to ParameterizedType. That only works
# while the subclass keeps its Signature attribute and stays a distinct class, so
# R8 merging it into its parent turns the cast into a ClassCastException the
# first time one is constructed. webauthn4j does this in the constructor of every
# attestation verifier, which JdbcAccount builds during startup; the others are
# the usual TypeReference and TypeToken shapes, kept in case they are reached.
-keep class com.webauthn4j.** { *; }
-keep class * extends com.fasterxml.jackson.core.type.TypeReference
-keep class * extends com.google.gson.reflect.TypeToken
-keep class * extends com.google.common.reflect.TypeToken

# --- the web UI bridge ----------------------------------------------------
# MainActivity is handed to the WebView as "Android" and the page calls these
# methods by name. (The default rules carry this too; it is here because it is
# not obvious from the java side that these names are load bearing.)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- work manager ---------------------------------------------------------
# WorkManager stores a worker's class name in its database and reflects on it
# when the work runs. Renaming SyncWorker would strand work enqueued by an
# earlier build, which is the update case, not the fresh install case, so it
# would not show up in testing.
-keepnames class * extends androidx.work.ListenableWorker

# --- crash reports --------------------------------------------------------
# Keep the line numbers so a stack trace still names a line, and rewrite the
# source file attribute, which otherwise hands back the class name the mapping
# is there to hide. Keep build/outputs/mapping/release/mapping.txt for each
# release: without it a crash from the field cannot be read.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# --- generic types and annotations ----------------------------------------
# Read at runtime in places that recover an element type from a signature.
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# --- the server jar on Android --------------------------------------------
# Peergos.jar is built to run on a JDK, so it refers to classes Android has no
# copy of - java.lang.foreign, the servlet API, the sun internals, JDBC. None of
# it is reachable from the app, and R8 removes it; these only stop it reporting
# the dangling references as errors on the way out. If a build fails naming a
# class that is missing, R8 writes the rule for it to
# build/outputs/mapping/release/missing_rules.txt - add it here rather than
# widening one of these.
-dontwarn java.**
-dontwarn javax.**
-dontwarn jakarta.**
-dontwarn sun.**
-dontwarn com.sun.**
-dontwarn jdk.**
-dontwarn org.slf4j.**
-dontwarn org.apache.**
-dontwarn org.eclipse.**
-dontwarn io.netty.**
-dontwarn io.libp2p.**
-dontwarn org.h2.**
-dontwarn org.postgresql.**
-dontwarn redis.clients.**
-dontwarn org.jline.**
-dontwarn jnr.**
-dontwarn com.kenai.**
-dontwarn reactor.**
-dontwarn org.bouncycastle.**
-dontwarn com.fasterxml.jackson.**
-dontwarn com.webauthn4j.**
-dontwarn org.checkerframework.**
-dontwarn org.xbill.**
-dontwarn com.google.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn io.opentelemetry.**
-dontwarn io.prometheus.**

# peergos.shared is compiled for the browser as well, where these annotations mark
# up what is exposed to javascript. They are source level and never on the device.
-dontwarn jsinterop.annotations.**
