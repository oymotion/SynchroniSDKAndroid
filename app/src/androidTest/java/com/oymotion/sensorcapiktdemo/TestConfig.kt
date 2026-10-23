package com.oymotion.sensorcapiktdemo

import com.oymotion.sensor.capi.BleDevice
import com.oymotion.sensorcapiktdemo.devicespecs.DeviceSpec
import com.oymotion.sensorcapiktdemo.devicespecs.SpecCerelaxPro
import com.oymotion.sensorcapiktdemo.devicespecs.SpecOpal

/**
 * 测试设备配置（对应 Python 侧 config.py 的 DEVICES + TARGET_IDENTITY）。
 *
 * 每台设备字段：
 *   model      : 型号标识（spec key），如 "Cerelax-Pro"/"Cerelax-SE"/"Opal"/"Cerelax"/"Cerelax-Ultra"
 *   namePrefix : 广播名前缀（仅描述/打印用，不参与匹配），如 "Cerelax"/"Opal"
 *   mac        : 精确 MAC（大写、含冒号）；非空时优先按 MAC 精确匹配
 *   identity   : 蓝牙地址后四位（如 "850B"，对应广播名 "Cerelax(850B)" 括号内部分）
 *
 * 匹配优先级：mac > identity。
 *
 * 型号区分：Cerelax 系列多型号（Cerelax-Pro / Cerelax-SE / Cerelax / Cerelax-Ultra）
 * 广播名均以 "Cerelax" 开头，Opal 广播名为 "Opal"，广播名前缀不足以区分型号，
 * 因此 spec 一律按 model 加载，不依赖 namePrefix。
 */
data class TestDevice(
    val model: String,
    val namePrefix: String,
    val mac: String,
    val identity: String,
)

object TestConfig {

    // ---- 本轮目标设备（identity，逗号分隔，可多个）----
    const val TARGET_IDENTITY = "850B"

    // ---- 设备清单 ----
    val DEVICES: List<TestDevice> = listOf(
        TestDevice(model = "Cerelax-Pro", namePrefix = "Cerelax", mac = "BC:93:2A:3F:85:0B", identity = "850B"),
        TestDevice(model = "Cerelax-Pro", namePrefix = "Cerelax", mac = "BC:93:2A:3F:85:1C", identity = "851C"),
        TestDevice(model = "Opal", namePrefix = "Opal", mac = "BC:93:2A:3F:81:03", identity = "8103"),
        // TODO: 补充其它型号设备（先建对应 spec 再登记）：Cerelax-SE / Cerelax / Cerelax-Ultra
    )

    // ---- 型号 -> spec 映射（对应 Python config.MODEL_SPEC）----
    val MODEL_SPEC: Map<String, DeviceSpec> = mapOf(
        "Cerelax-Pro" to SpecCerelaxPro.SPEC,
        "Opal" to SpecOpal.SPEC,
        // TODO: 其它型号待补对应 spec：Cerelax-SE / Cerelax / Cerelax-Ultra
    )

    /** 按型号读取设备规格（对应 Python common.load_spec）。 */
    fun loadSpec(model: String): DeviceSpec =
        MODEL_SPEC[model] ?: error("未注册的型号: $model")

    /** 按 identity 查设备型号再读 spec（对应 Python common.spec_for_identity）。 */
    fun specForIdentity(identity: String): DeviceSpec {
        val device = devicesByIdentity(identity).firstOrNull()
            ?: error("DEVICES 中未找到 identity: $identity")
        return loadSpec(device.model)
    }

    // ---- 通用测试参数 ----
    const val SCAN_TIMEOUT_MS = 5000
    const val SCAN_RETRY_INTERVAL_MS = 10000L   // 未匹配到目标时的重扫等待（对应 Python scan_and_match interval）
    const val READY_TIMEOUT_MS = 15000L
    const val DATA_TIMEOUT_SEC = 10L
    const val PACKAGE_SAMPLE_COUNT = 20
    const val INIT_TIMEOUT_MS = 30000            // init(packageSampleCount, timeoutMs, ...)
    const val POWER_REFRESH_INTERVAL_MS = 1000   // init(..., powerRefreshIntervalMs)
    const val COLLECT_SECONDS = 5                // 起流后采集时长（秒）
    const val MIN_SAMPLES = 1                    // 判定"收到数据"的最小样本数
    const val MANUAL_PAUSE_SECONDS = 30L         // semi-auto 用例人工操作等待窗口（秒）
    const val PERF_MEASURE_SECONDS = 10L         // DATA-PERF-001 采样率测量时长（秒）
    const val SAMPLE_RATE_TOLERANCE = 0.05       // 实测采样率相对标称的容差（5%）
    const val LONGEVITY_STREAM_SECONDS = 30L     // DATA-PERF-002 长稳起流时长（秒）；正式长稳可调为 1800

    /** 本轮要测的 identity 列表（逗号分隔拆开）。 */
    val targetIdentities: List<String>
        get() = TARGET_IDENTITY.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** 按 identity 在设备清单里查找。 */
    fun devicesByIdentity(identity: String): List<TestDevice> =
        DEVICES.filter { it.identity.equals(identity, ignoreCase = true) }

    /** 按 MAC 在设备清单里查找。 */
    fun deviceByMac(mac: String): TestDevice? =
        DEVICES.firstOrNull { it.mac.isNotEmpty() && it.mac.equals(mac, ignoreCase = true) }

    /** 扫描到的 BLE 设备是否命中本轮目标（mac 精确 > 广播名后四位 identity）。 */
    fun matchesTarget(device: BleDevice): Boolean {
        if (deviceByMac(device.mac) != null) return true
        val name = device.name.orEmpty()
        return targetIdentities.any { id ->
            name.endsWith("($id)", ignoreCase = true) || name.endsWith(id, ignoreCase = true)
        }
    }
}
