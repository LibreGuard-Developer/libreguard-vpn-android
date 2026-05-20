# LibreGuardVPN release hardening rules.
# Keep this file narrowly scoped: preserve only runtime contracts that R8 cannot infer
# (reflection, Gson field names, JNI/native entry points, AIDL/Binder surfaces).

# Preserve runtime metadata used by Retrofit/Gson/Kotlin generics.
-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Improve obfuscation for app-owned code that is safe to rename.
-adaptclassstrings
-allowaccessmodification

# Ensure AGP's expected release mapping artifacts are materialized on fresh builds.
-printseeds build/outputs/mapping/release/seeds.txt

# --- Retrofit / Gson ---------------------------------------------------------

# Retrofit inspects this interface and its annotations at runtime.
-keep,allowobfuscation interface net.libreguard.vpn.network.ApiService

# Preserve Gson TypeToken metadata used for generic deserialization.
-keep class com.google.gson.reflect.TypeToken { *; }
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Preserve JSON field names for network DTOs.
# Several models rely on raw Kotlin property names instead of explicit @SerializedName.
-keepclassmembers class net.libreguard.vpn.network.** {
	<fields>;
}

# Gson instantiates these DTOs reflectively. In release builds R8 can otherwise
# abstractify/merge classes in ways that break reflective construction.
# Keep the concrete network model classes intact while still allowing the rest of
# the app to be optimized normally.
-keep class net.libreguard.vpn.network.** {
	<fields>;
	<init>(...);
}

# Preserve persisted JSON schema for locally stored connection history.
-keepclassmembers class net.libreguard.vpn.data.ConnectionRecord {
	<fields>;
}

# --- strongSwan reflection / JNI ---------------------------------------------

# Native code in libandroidbridge resolves these classes and members by name.
-keep class org.strongswan.android.logic.** { *; }
-keep class org.strongswan.android.utils.Utils { *; }

# App code reflectively invokes strongSwan profile data source methods.
-keep class org.strongswan.android.data.VpnProfileSource { public *; }
-keep class org.strongswan.android.data.VpnProfileDataSource { public *; }

# --- OpenVPN JNI / Binder ----------------------------------------------------

# JNI symbol names are tied to this class and its native method names.
-keep class de.blinkt.openvpn.core.NativeUtils { *; }

# Binder/AIDL interfaces must retain their transaction surface.
-keep class de.blinkt.openvpn.core.IServiceStatus { *; }
-keep class de.blinkt.openvpn.core.IStatusCallbacks { *; }
-keep class de.blinkt.openvpn.core.IOpenVPNServiceInternal { *; }
-keep class de.blinkt.openvpn.api.** { *; }

# --- Third-party runtime lookups ---------------------------------------------

# Resolved via Class.forName() in BouncyCastleBootstrap.
-keep class org.bouncycastle.jce.provider.BouncyCastleProvider { *; }

# Keep the bundled BouncyCastle implementation intact in release builds.
# The provider is loaded reflectively and its engine classes are wired internally,
# so R8 must not strip package-private/provider-internal code used for PKCS#12,
# TLS, and certificate parsing.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

