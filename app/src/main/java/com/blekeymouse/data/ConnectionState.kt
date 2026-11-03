package com.blekeymouse.data

/**
 * Sealed class representing every possible Bluetooth HID connection state.
 *
 * This is the single source of truth exposed through StateFlow to the UI layer.
 * The [message] field carries human-readable diagnostic text for display in the UI.
 */
sealed class ConnectionState(open val message: String) {

    /** No device is connected and the service is idle. */
    object Disconnected : ConnectionState("Disconnected")

    /**
     * A connection attempt is in progress.
     * @param deviceName The friendly name of the target device.
     */
    data class Connecting(val deviceName: String) : ConnectionState("Connecting to $deviceName…")

    /**
     * The HID profile is active and reports can be sent.
     * @param deviceName The friendly name of the connected device.
     * @param deviceAddress The MAC address of the connected device.
     */
    data class Connected(
        val deviceName: String,
        val deviceAddress: String
    ) : ConnectionState("Connected to $deviceName")

    /**
     * The HID app registration with the Bluetooth stack is in progress.
     * No connect() calls are safe until this transitions to a registered state.
     */
    object Registering : ConnectionState("Registering HID profile…")

    /**
     * A recoverable error occurred. The service will attempt recovery automatically.
     * @param reason A short description of the failure reason.
     */
    data class Error(val reason: String) : ConnectionState("Error: $reason")
}
