package com.blekeymouse.data

import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings

/**
 * HID Report Descriptors and SDP Settings for a combined Mouse + Keyboard + Consumer device.
 *
 * Report ID 0x01 → Mouse (5 buttons, 16-bit X/Y, vertical wheel, horizontal AC Pan)
 * Report ID 0x02 → Keyboard (modifier, reserved, 6×keycodes — boot-compatible, LED output)
 * Report ID 0x03 → Consumer Control (single 16-bit usage: Home, Back, Recents, media, volume)
 *
 * Why these choices:
 *  • 16-bit X/Y: 8-bit deltas were clamped to ±127 per report, so fast air-mouse flicks
 *    silently lost distance. 16-bit relative axes are supported by Windows, macOS, Linux
 *    and Android.
 *  • AC Pan (Consumer 0x0238): real horizontal scrolling. Android ignores Shift+Wheel, so
 *    horizontal scroll (launcher pages, carousels) previously did nothing on phone hosts.
 *  • Buttons 4/5: Back/Forward — Android maps mouse button 4 to BACK.
 *  • Consumer page: Android maps AC Home → HOME, AC Back → BACK, AL Task Switcher → RECENTS,
 *    plus Play/Pause & volume — needed to properly navigate a phone/TV host.
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

        // 5 Buttons (Left, Right, Middle, Back, Forward)
        0x05.toByte(), 0x09,         //     Usage Page (Button)
        0x19.toByte(), 0x01,         //     Usage Minimum (Button 1)
        0x29.toByte(), 0x05,         //     Usage Maximum (Button 5)
        0x15.toByte(), 0x00,         //     Logical Minimum (0)
        0x25.toByte(), 0x01,         //     Logical Maximum (1)
        0x75.toByte(), 0x01,         //     Report Size (1)
        0x95.toByte(), 0x05,         //     Report Count (5)
        0x81.toByte(), 0x02,         //     Input (Data, Variable, Absolute)

        // Padding (3 bits)
        0x75.toByte(), 0x03,         //     Report Size (3)
        0x95.toByte(), 0x01,         //     Report Count (1)
        0x81.toByte(), 0x03,         //     Input (Constant)

        // X and Y (Relative 16-bit signed)
        0x05.toByte(), 0x01,         //     Usage Page (Generic Desktop)
        0x09.toByte(), 0x30,         //     Usage (X)
        0x09.toByte(), 0x31,         //     Usage (Y)
        0x16.toByte(), 0x01, 0x80.toByte(),       // Logical Minimum (-32767)
        0x26.toByte(), 0xFF.toByte(), 0x7F,       // Logical Maximum (32767)
        0x75.toByte(), 0x10,         //     Report Size (16)
        0x95.toByte(), 0x02,         //     Report Count (2)
        0x81.toByte(), 0x06,         //     Input (Data, Variable, Relative)

        // Vertical Wheel (Relative 8-bit signed)
        0x09.toByte(), 0x38,         //     Usage (Wheel)
        0x15.toByte(), 0x81.toByte(),//     Logical Minimum (-127)
        0x25.toByte(), 0x7F,         //     Logical Maximum (127)
        0x75.toByte(), 0x08,         //     Report Size (8)
        0x95.toByte(), 0x01,         //     Report Count (1)
        0x81.toByte(), 0x06,         //     Input (Data, Variable, Relative)

        // Horizontal Wheel — AC Pan (Relative 8-bit signed)
        0x05.toByte(), 0x0C,         //     Usage Page (Consumer)
        0x0A.toByte(), 0x38, 0x02,   //     Usage (AC Pan)
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

        // LED output report (Num, Caps, Scroll, Compose, Kana) — standard boot keyboard.
        // Hosts send keyboard LED state here via SET_REPORT / interrupt OUT.
        0x05.toByte(), 0x08,         //   Usage Page (LEDs)
        0x19.toByte(), 0x01,         //   Usage Minimum (Num Lock)
        0x29.toByte(), 0x05,         //   Usage Maximum (Kana)
        0x15.toByte(), 0x00,         //   Logical Minimum (0)
        0x25.toByte(), 0x01,         //   Logical Maximum (1)
        0x75.toByte(), 0x01,         //   Report Size (1)
        0x95.toByte(), 0x05,         //   Report Count (5)
        0x91.toByte(), 0x02,         //   Output (Data, Variable, Absolute)
        0x75.toByte(), 0x03,         //   Report Size (3)
        0x95.toByte(), 0x01,         //   Report Count (1)
        0x91.toByte(), 0x03,         //   Output (Constant) — padding

        0xC0.toByte(),               // End Collection

        // ── Consumer Control Collection (Report ID 3) ────────────────────
        0x05.toByte(), 0x0C,         // Usage Page (Consumer)
        0x09.toByte(), 0x01,         // Usage (Consumer Control)
        0xA1.toByte(), 0x01,         // Collection (Application)
        0x85.toByte(), 0x03,         //   Report ID (3)
        0x15.toByte(), 0x00,         //   Logical Minimum (0)
        0x26.toByte(), 0xFF.toByte(), 0x03,       // Logical Maximum (0x03FF)
        0x19.toByte(), 0x00,         //   Usage Minimum (0)
        0x2A.toByte(), 0xFF.toByte(), 0x03,       // Usage Maximum (0x03FF)
        0x75.toByte(), 0x10,         //   Report Size (16)
        0x95.toByte(), 0x01,         //   Report Count (1)
        0x81.toByte(), 0x00,         //   Input (Data, Array, Absolute)
        0xC0.toByte()                // End Collection
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Report IDs and payload array sizes (excluding Bluetooth L2CAP Report ID byte)
    // ─────────────────────────────────────────────────────────────────────────
    const val MOUSE_REPORT_ID: Byte = 0x01
    const val KEYBOARD_REPORT_ID: Byte = 0x02
    const val CONSUMER_REPORT_ID: Byte = 0x03

    /** Mouse payload = 7 bytes [buttons, X lo, X hi, Y lo, Y hi, wheel, pan] */
    const val MOUSE_PAYLOAD_SIZE = 7

    /** Keyboard report payload size (excluding Report ID parameter) = 8 bytes [modifier, reserved, key1..key6] */
    const val KEYBOARD_PAYLOAD_SIZE = 8

    /** Consumer payload = 2 bytes [usage lo, usage hi] */
    const val CONSUMER_PAYLOAD_SIZE = 2

    // ─────────────────────────────────────────────────────────────────────────
    // Mouse buttons
    // ─────────────────────────────────────────────────────────────────────────
    const val BUTTON_LEFT = 0x01
    const val BUTTON_RIGHT = 0x02
    const val BUTTON_MIDDLE = 0x04
    const val BUTTON_BACK = 0x08
    const val BUTTON_FORWARD = 0x10
    const val BUTTON_MASK = 0x1F

    // ─────────────────────────────────────────────────────────────────────────
    // Consumer Control usages (HID Usage Tables, page 0x0C)
    // ─────────────────────────────────────────────────────────────────────────
    object Consumer {
        const val PLAY_PAUSE = 0x00CD
        const val NEXT_TRACK = 0x00B5
        const val PREV_TRACK = 0x00B6
        const val MUTE = 0x00E2
        const val VOLUME_UP = 0x00E9
        const val VOLUME_DOWN = 0x00EA
        /** AL Select Task/Application → Android RECENTS (KEY_APPSELECT / APP_SWITCH). */
        const val TASK_SWITCHER = 0x01A2
        /** AC Home → Android HOME, browser home on PC. */
        const val AC_HOME = 0x0223
        /** AC Back → Android BACK, browser back on PC. */
        const val AC_BACK = 0x0224
    }

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
