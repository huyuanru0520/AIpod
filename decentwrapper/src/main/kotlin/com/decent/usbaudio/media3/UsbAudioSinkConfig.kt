package com.decent.usbaudio.media3

/**
 * Configuration for [UsbAudioSink].
 *
 * @param bitPerfectEnabled When true, audio is sent directly to the USB DAC
 *        bypassing the Android audio stack entirely.
 * @param forceRouteToSpeaker When true, the delegate AudioTrack is routed to
 *        the built-in speaker to prevent AudioFlinger from opening the USB device.
 * @param highGain When true, output is full-scale bit-perfect (volume ignored).
 *        When false (default), volume is adjustable via integer software gain.
 */
data class UsbAudioSinkConfig(
    val bitPerfectEnabled: Boolean = true,
    val forceRouteToSpeaker: Boolean = true,
    val highGain: Boolean = false
)
