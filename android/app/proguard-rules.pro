# ProGuard rules for Meli

# Keep VPN service
-keep class com.doodoolyasin.meli.MeliVpnService { *; }

# Keep application class
-keep class com.doodoolyasin.meli.MeliApplication { *; }

# Keep main activity
-keep class com.doodoolyasin.meli.MainActivity { *; }

# Keep BootReceiver
-keep class com.doodoolyasin.meli.BootReceiver { *; }

# Keep Base32 utility
-keep class com.doodoolyasin.meli.Base32 { *; }

# Keep Kotlin coroutines
-keep class kotlinx.coroutines.** { *; }

# Keep AndroidX
-keep class androidx.** { *; }

# Keep Google Material
-keep class com.google.android.material.** { *; }