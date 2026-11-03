# ──────────────────────────────────────────────────────────────────────────────
# BleKeyMouse ProGuard rules
# ──────────────────────────────────────────────────────────────────────────────

# Keep Bluetooth HID classes (referenced via reflection by Android BT stack)
-keep class android.bluetooth.** { *; }
-keep class android.bluetooth.BluetoothHidDevice { *; }
-keep class android.bluetooth.BluetoothHidDevice$Callback { *; }
-keep class android.bluetooth.BluetoothHidDeviceAppSdpSettings { *; }
-keep class android.bluetooth.BluetoothHidDeviceAppQosSettings { *; }

# Keep data classes used in StateFlow (Kotlin serializes field names)
-keep class com.blekeymouse.data.ConnectionState { *; }
-keep class com.blekeymouse.data.ConnectionState$* { *; }

# Keep service binder classes
-keep class com.blekeymouse.service.BluetoothHidService { *; }
-keep class com.blekeymouse.service.BluetoothHidService$LocalBinder { *; }

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class kotlinx.coroutines.android.** { *; }

# Compose
-keep class androidx.compose.** { *; }

# General Android
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-dontwarn kotlin.**
