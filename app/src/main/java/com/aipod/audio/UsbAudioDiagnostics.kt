package com.aipod.audio

import android.content.Context
import android.hardware.usb.UsbManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioMixerAttributes
import android.os.Build
import com.aipod.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * USB DAC / bit-perfect 能力诊断工具（只读，不改变播放链路）。
 *
 * 判定依据来自 Android 官方 androidx/media issue #415 中 Google 工程师给出的探测路径：
 *  1. 枚举所有输出设备，找出 USB 音频设备（TYPE_USB_DEVICE / TYPE_USB_HEADSET / TYPE_USB_ACCESSORY）；
 *  2. 用 USAGE_MEDIA + CONTENT_TYPE_MUSIC 取「音乐当前路由到哪个设备」；
 *  3. API 34+ 对该设备调用 getSupportedMixerAttributes()，看是否存在
 *     MIXER_BEHAVIOR_BIT_PERFECT —— 这是系统层原生 bit-perfect 的硬前提；
 *  4. 附带记录 DAC 的 VID/PID、厂商/产品名、Android 版本/机型，便于对照机型支持列表。
 *
 * 全程只查询系统状态，不请求权限、不占用音频设备、不写任何设置。
 */
object UsbAudioDiagnostics {

    data class DeviceInfo(
        val typeName: String,
        val productName: String,
        val manufacturer: String,
        val isUsb: Boolean,
        val bitPerfectFormats: List<String>,   // 该设备支持的 bit-perfect AudioFormat 列表（API34+）
        val mixerApiSupported: Boolean          // 系统是否提供 getSupportedMixerAttributes
    )

    data class Report(
        val androidVersion: String,
        val sdk: Int,
        val deviceModel: String,
        val devices: List<DeviceInfo>,
        val mediaRoute: String,
        val usbHostAvailable: Boolean,
        val usbDevices: List<String>,           // UsbManager 层面的 VID:PID 列表
        val summary: String,
        val error: String? = null
    ) {
        fun toLog(): String = buildString {
            appendLine("机型=$deviceModel | Android=$androidVersion (SDK $sdk)")
            appendLine("USB Host=${if (usbHostAvailable) "支持" else "不支持"} | 音乐路由=$mediaRoute")
            if (usbDevices.isEmpty()) appendLine("USB 设备列表=（无）")
            else usbDevices.forEachIndexed { i, s -> appendLine("USB 设备[$i]=$s") }
            devices.forEachIndexed { i, d ->
                appendLine("输出设备[$i]=${d.typeName} | ${d.manufacturer}/${d.productName} | USB=${d.isUsb}")
                if (d.isUsb) {
                    appendLine("    mixer API=${if (d.mixerApiSupported) "可用" else "不可用(需 Android14+)"} | " +
                        "bit-perfect=${if (d.bitPerfectFormats.isEmpty()) "❌ 不支持" else "✅ 支持 ${d.bitPerfectFormats.size} 种格式"}")
                    d.bitPerfectFormats.forEach { f -> appendLine("      · $f") }
                }
            }
            append("结论：$summary")
            if (error != null) appendLine("\n错误：$error")
        }
    }

    /** 执行诊断，必须在 IO 线程调用（内部切 Dispatchers.IO）。 */
    suspend fun diagnose(context: Context): Report = withContext(Dispatchers.IO) {
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return@withContext Report("", -1, "", emptyList(), "", false, emptyList(), "", "无法获取 AudioManager")

            // 1) 所有输出设备
            @Suppress("UNCHECKED_CAST")
            val outputs: Array<AudioDeviceInfo> = runCatching { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
                .getOrDefault(emptyArray<AudioDeviceInfo>())

            // 2) 音乐用途的当前路由设备（API 31+，反射调用避免低版本链接问题）
            val mediaRouteDevices: Array<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
                runCatching {
                    val m = AudioManager::class.java.getMethod("getAudioDevicesForAttributes", AudioAttributes::class.java)
                    (m.invoke(am, attrs) as? Array<*>)?.filterIsInstance<AudioDeviceInfo>()?.toTypedArray()
                        ?: emptyArray()
                }.getOrDefault(emptyArray())
            } else emptyArray()

            val routeNames = mediaRouteDevices.joinToString(", ") { it.typeName() }

            // 3) 逐个输出设备取 mixer attributes（API 34+）
            val deviceInfos = outputs.map { dev ->
                val bpFormats = mutableListOf<String>()
                var apiOk = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && dev.isUsb()) {
                    apiOk = true
                    val attrs = getSupported(dev, am)
                    attrs.forEach { ma ->
                        if (ma.mixerBehavior == AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT) {
                            bpFormats += ma.format.toString()
                        }
                    }
                }
                DeviceInfo(
                    typeName = dev.typeName(),
                    productName = deviceStringField(dev, "getProductName"),
                    manufacturer = deviceStringField(dev, "getManufacturer"),
                    isUsb = dev.isUsb(),
                    bitPerfectFormats = bpFormats,
                    mixerApiSupported = apiOk
                )
            }

            // 4) UsbManager 层面的设备 VID:PID（只读列表，无需权限）
            val usbMgr = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            val usbHost = context.packageManager?.hasSystemFeature("android.hardware.usb.host") ?: false
            val usbList = usbMgr?.deviceList?.values?.map { d ->
                "VID=%04X PID=%04X".format(d.vendorId, d.productId) +
                    (d.deviceName?.let { " @$it" } ?: "")
            } ?: emptyList()

            val usbAudio = deviceInfos.filter { it.isUsb }
            val bitPerfect = deviceInfos.any { it.bitPerfectFormats.isNotEmpty() }
            val summary = when {
                Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                    "Android ${Build.VERSION.RELEASE} 低于 14，系统不支持原生 bit-perfect mixer API；" +
                        "只能用 UAPP/USB 独占类方案。"
                usbAudio.isEmpty() ->
                    "未检测到 USB 音频设备。请确认已用 OTG 线连接 UAC2 DAC，且手机开启 USB Host；" +
                        "当前音乐路由=$routeNames。"
                !bitPerfect ->
                    "已连 USB DAC，但该机型的 getSupportedMixerAttributes 未报告 BIT_PERFECT。" +
                        "说明系统层原生 bit-perfect 不可用（多数非 Pixel 机型如此）；" +
                        "可尝试第三方 USB 独占库。DAC 的 VID/PID 见上。"
                else ->
                    "✅ 该 USB DAC 在系统层报告了 BIT_PERFECT 支持！可走 Android 14 mixer attributes 方案，" +
                        "接上后由系统直出原始码流（音量由 DAC 硬件控制）。"
            }

            Report(
                androidVersion = Build.VERSION.RELEASE,
                sdk = Build.VERSION.SDK_INT,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                devices = deviceInfos,
                mediaRoute = routeNames,
                usbHostAvailable = usbHost,
                usbDevices = usbList,
                summary = summary
            )
        } catch (t: Throwable) {
            Report(
                androidVersion = Build.VERSION.RELEASE,
                sdk = Build.VERSION.SDK_INT,
                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
                devices = emptyList(),
                mediaRoute = "",
                usbHostAvailable = false,
                usbDevices = emptyList(),
                summary = "诊断异常终止",
                error = "${t::class.java.simpleName}: ${t.message}"
            )
        }
    }

    /** 便捷方法：诊断并把完整报告写入日志。 */
    suspend fun diagnoseAndLog(context: Context): Report {
        val r = diagnose(context)
        CrashLogger.info("UsbAudio", "USB DAC / bit-perfect 能力诊断：\n    ${r.toLog().replace("\n", "\n    ")}")
        return r
    }

    // ---- 工具函数 ----

    private fun AudioDeviceInfo.isUsb(): Boolean =
        type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    private fun AudioDeviceInfo.typeName(): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "内置扬声器"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "有线耳机(带麦)"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "有线耳机"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "蓝牙 A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙 SCO"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 音频设备"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 耳麦"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 配件"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_DOCK -> "扩展坞"
        AudioDeviceInfo.TYPE_TELEPHONY -> "电话"
        else -> "其他(type=$type)"
    }

    /** 反射读取 AudioDeviceInfo 的 CharSequence 字段（getProductName/getManufacturer 为 API31+）。 */
    private fun deviceStringField(dev: AudioDeviceInfo, method: String): String =
        runCatching {
            AudioDeviceInfo::class.java.getMethod(method).invoke(dev)?.toString()
        }.getOrDefault("") ?: ""

    @Suppress("UNCHECKED_CAST")
    private fun getSupported(dev: AudioDeviceInfo, am: AudioManager): List<AudioMixerAttributes> =
        runCatching {
            val m = AudioManager::class.java.getMethod("getSupportedMixerAttributes", AudioDeviceInfo::class.java)
            (m.invoke(am, dev) as? List<*>)?.filterIsInstance<AudioMixerAttributes>() ?: emptyList()
        }.getOrElse {
            CrashLogger.info("UsbAudio", "getSupportedMixerAttributes 反射失败：${it.message}")
            emptyList()
        }
}
