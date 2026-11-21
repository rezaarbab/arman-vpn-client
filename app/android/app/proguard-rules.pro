# hev-socks5-tunnel binds its native methods to this class by name.
-keep class com.arman.firefoxvpn.Tun2Socks { *; }

# The C gate binds its methods by name too (RegisterNatives in blobgate.c).
-keep class ir.v2rayng.compose.core.NativeSec { *; }
-keep class ir.v2rayng.compose.core.BlobGate { *; }

# gomobile's bridge and the core's classes are reached from native code by
# name; R8 cannot see those references.
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-dontwarn go.**
-dontwarn libv2ray.**
