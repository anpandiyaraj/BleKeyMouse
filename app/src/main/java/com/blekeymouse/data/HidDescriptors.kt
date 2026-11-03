package com.blekeymouse.data

import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings

/**
 * HID Report Descriptors and SDP Settings for a combined Mouse + Keyboard device.
 *
 * Report ID 0x01 → Mouse (buttons, X, Y, wheel)
 * Report ID 0x02 → Keyboard (modifier, reserved, 6×keycodes — boot-compatible)
 *
 * Hand-crafted to USB HID v1.11 spec and Bluetooth HID Profile v1.1 spec.
 */
object HidDescriptors {

    // ─────────────────────────────────────────────────────────────────────────
    // Combined HID Report Descriptor
    // ─────────────────────────────────────────────────────────────────────────
    val COMBINED_DESCRIPTOR: ByteArray = byteArrayOf(
        // ── Mouse Collection (Report ID 1) ────────────────────────────────
        0x05.toByte(), 0x01,         // Usage Page (Generic Desktop)
        0x09.toByte(), 0x02,         // Usage (Mouse)
        0xA1.toByte(), 0x01,         // Collection (Application)
        0x09.toByte(), 0x01,         //   Usage (Pointer)
        0xA1.toByte(), 0x00,         //   Collection (Physical)
        0x85.toByte(), 0x01,         //     Report ID (1)

        // 3 Buttons (Left, Right, Middle)
        0x05.toByte(), 0x09,         //     Usage Page (Button)
        0x19.toByte(), 0x01,         //     Usage Minimum (Button 1)
        0x29.toByte(), 0x03,         //     Usage Maximum (Button 3)
        0x15.toByte(), 0x00,         //     Logical Minimum (0)
        0x25.toByte(), 0x01,         //     Logical Maximum (1)
        0x75.toByte(), 0x01,         //     Report Size (1)
        0x95.toByte(), 0x03,         //     Report Count (3)
        0x81.toByte(), 0x02,         //     Input (Data, Variable, Absolute)

        // Padding (5 bits)
        0x75.toByte(), 0x05,         //     Report Size (5)
        0x95.toByte(), 0x01,         //     Report Count (1)
        0x81.toByte(), 0x03,         //     Input (Constant)

        // X and Y (Relative 8-bit signed)
        0x05.toByte(), 0x01,         //     Usage Page (Generic Desktop)
        0x09.toByte(), 0x30,         //     Usage (X)
        0x09.toByte(), 0x31,         //     Usage (Y)
        0x15.toByte(), 0x81.toByte(),//     Logical Minimum (-127)
        0x25.toByte(), 0x7F,         //     Logical Maximum (127)
        0x75.toByte(), 0x08,         //     Report Size (8)
        0x95.toByte(), 0x02,         //     Report Count (2)
        0x81.toByte(), 0x06,         //     Input (Data, Variable, Relative)

        // Vertical Wheel (Relative 8-bit signed)
        0x05.toByte(), 0x01,         //     Usage Page (Generic Desktop)
        0x09.toByte(), 0x38,         //     Usage (Wheel)
        0x15.toByte(), 0x81.toByte(),//     Logical Minimum (-127)
        0x25.toByte(), 0x7F,         //     Logical Maximum (127)
        0x75.toByte(), 0x08,         //     Report Size (8)
        0x95.toByte(), 0x01,         //     Report Count (1)
        0x81.toByte(), 0x06,         //     Input (Data, Variable, Relative)

        0xC0.toByte(),               //   End Collection (Physical)
        0xC0.toByte(),               // End Collection (Application)

        // ── Keyboard Collection (Report ID 2) ────────────────────────────
        0x05.toByte(), 0x01,         // Usage Page (Generic Desktop)
        0x09.toByte(), 0x06,         // Usage (Keyboard)
        0xA1.toByte(), 0x01,         // Collection (Application)
        0x85.toByte(), 0x02,         //   Report ID (2)

        // Modifiers (8 bits: L-Ctrl, L-Shift, L-Alt, L-GUI, R-Ctrl, R-Shift, R-Alt, R-GUI)
        0x05.toByte(), 0x07,         //   Usage Page (Key Codes)
        0x19.toByte(), 0xE0.toByte(),//   Usage Minimum (Left Control)
        0x29.toByte(), 0xE7.toByte(),//   Usage Maximum (Right GUI)
        0x15.toByte(), 0x00,         //   Logical Minimum (0)
        0x25.toByte(), 0x01,         //   Logical Maximum (1)
        0x75.toByte(), 0x01,         //   Report Size (1)
        0x95.toByte(), 0x08,         //   Report Count (8)
        0x81.toByte(), 0x02,         //   Input (Data, Variable, Absolute)

        // Reserved byte
        0x75.toByte(), 0x08,         //   Report Size (8)
        0x95.toByte(), 0x01,         //   Report Count (1)
        0x81.toByte(), 0x03,         //   Input (Constant)

        // 6 simultaneous keycodes
        0x05.toByte(), 0x07,         //   Usage Page (Key Codes)
        0x19.toByte(), 0x00,         //   Usage Minimum (0)
        0x29.toByte(), 0xFF.toByte(),//   Usage Maximum (255)
        0x15.toByte(), 0x00,         //   Logical Minimum (0)
        0x26.toByte(), 0xFF.toByte(), 0x00, // Logical Maximum (255)
        0x75.toByte(), 0x08,         //   Report Size (8)
        0x95.toByte(), 0x06,         //   Report Count (6)
        0x81.toByte(), 0x00,         //   Input (Data, Array)

        0xC0.toByte()                // End Collection
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Report IDs and payload array sizes (excluding Bluetooth L2CAP Report ID byte)
    // ─────────────────────────────────────────────────────────────────────────
    const val MOUSE_REPORT_ID: Byte = 0x01
    const val KEYBOARD_REPORT_ID: Byte = 0x02

    /** Mouse report payload size (excluding Report ID parameter) = 4 bytes [buttons, dx, dy, wheel] */
    const val MOUSE_PAYLOAD_SIZE = 4

    /** Keyboard report payload size (excluding Report ID parameter) = 8 bytes [modifier, reserved, key1..key6] */
    const val KEYBOARD_PAYLOAD_SIZE = 8

    // ─────────────────────────────────────────────────────────────────────────
    // SDP Settings — presented to the host during SDP inquiry
    // ─────────────────────────────────────────────────────────────────────────

    fun buildSdpSettings(): BluetoothHidDeviceAppSdpSettings =
        BluetoothHidDeviceAppSdpSettings(
            /* name       = */ "BleKeyMouse",
            /* description= */ "Android Bluetooth HID Mouse & Keyboard",
            /* provider   = */ "BleKeyMouse App",
            /* subclass   = */ BluetoothHidDevice.SUBCLASS1_COMBO,
            /* descriptors= */ COMBINED_DESCRIPTOR
        )

    fun buildQosSettings(): BluetoothHidDeviceAppQosSettings =
        BluetoothHidDeviceAppQosSettings(
            /* serviceType     = */ BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            /* tokenRate       = */ 800,
            /* tokenBucketSize = */ 9,
            /* peakBandwidth   = */ 0,
            /* latency         = */ 10_000,   // microseconds
            /* delayVariation  = */ 0xFFFFFFFFL.toInt()
        )
}
