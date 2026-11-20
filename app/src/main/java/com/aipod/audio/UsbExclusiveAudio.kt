package com.aipod.audio

import android.content.Context
import android.hardware.usb.UsbManager
import com.aipod.util.CrashLogger
import com.decent.usbaudio.UsbAudioDevice

/**
 * USB 独占输出（实验性）的门禁与状态报告。
 *
 * 职责边界：
 *  - 只负责「有没有 USB DAC / 有没有权限 / 请求权限 / 报告状态」；
 *  - 真正的音频链路切换发生在 PlayerHolder.buildPlayer()：
 *    开关开启且本类报告 READY 时，用 UsbAudioSink 包装 DefaultAudioSink，
 *    否则一律走标准 ExoPlayer（失败也会回退到标准链路）。
 *
 * 任何一个环节抛错都只记录日志并返回 NOT_READY，绝不向上抛，
 * 保证「开了独占但 DAC 不支持 / 没插 / 没权限」时播放依旧正常。
 */
object UsbExclusiveAudio {

    /** 供 UI 展示的状态。 */
    enum class Status(val label: String) {
        DISABLED("未开启"),          // 设置开关关闭
        NO_DEVICE("未检测到 USB DAC"), // 没有 UAC2 音频设备
        NO_PERMISSION("DAC 需要授权"), // 有设备但没有 USB 权限
        READY("USB 独占就绪"),        // 有设备 + 有权限，可以使用独占输出
        ERROR("检测异常")
    }

    @Volatile
    private var lastStatus: Status = Status.DISABLED

    fun currentStatus(): Status = lastStatus

    /** USB 设备插入 / 权限变化后调用，刷新缓存状态。 */
    fun refreshStatus(context: Context, enabled: Boolean): Status {
        if (!enabled) {
            lastStatus = Status.DISABLED
            return lastStatus
        }
        lastStatus = detect(context)
        return lastStatus
    }

    /**
     * 检测 USB DAC 是否可用（只读，不改链路）。
     * @param requestPermissionIfNeeded 为 true 时，若设备存在但无权限则弹出系统授权框。
     */
    fun detect(context: Context, requestPermissionIfNeeded: Boolean = false): Status = runCatching {
        val usbMgr = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return Status.NO_DEVICE
        val dev = UsbAudioDevice.getInstance(context).findUsbAudioDevice()
            ?: return Status.NO_DEVICE.also {
                CrashLogger.info("UsbExclusive", "未检测到 UAC2 音频设备（USB 设备数=${usbMgr.deviceList.size}）")
            }
        if (!usbMgr.hasPermission(dev)) {
            CrashLogger.info("UsbExclusive", "USB DAC 未授权：${dev.productName} VID=${dev.vendorId} PID=${dev.productId}")
            if (requestPermissionIfNeeded) {
                UsbAudioDevice.getInstance(context).requestPermission(dev) { granted ->
                    CrashLogger.info("UsbExclusive", "USB 授权结果=$granted")
                    lastStatus = if (granted) Status.READY else Status.NO_PERMISSION
                }
            }
            Status.NO_PERMISSION
        } else {
            CrashLogger.info("UsbExclusive", "USB DAC 就绪：${dev.productName} VID=${dev.vendorId} PID=${dev.productId}")
            Status.READY
        }
    }.getOrElse { t ->
        CrashLogger.error("UsbExclusive.detect", t)
        Status.ERROR
    }.also { lastStatus = it }

    /** 当前是否具备启用独占输出的条件（供 PlayerHolder 判定）。 */
    fun isReady(): Boolean = lastStatus == Status.READY
}
