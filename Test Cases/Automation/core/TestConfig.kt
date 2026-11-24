package com.oymotion.sensorcapiktdemo

import android.content.Context
import com.oymotion.sensor.capi.BleDevice
import com.oymotion.sensorcapiktdemo.devicespecs.DeviceSpec
import com.oymotion.sensorcapiktdemo.devicespecs.SpecStore

/**
 * 测试设备配置（对应 Python 侧 `config.py` 的 `DEVICES` + `TARGET_IDENTITY` + 通用参数）。
 *
 * 数据来源：[ConfigStore]（外部 `automation/config.json` > assets > 内置默认）。
 * 运行前调用 [bind] 绑定 Context；未绑定时使用内置默认（与 assets 默认一致）。
 *
 * 匹配优先级：mac 精确 > 广播名后四位 identity。
 * spec 一律按 model 加载（[SpecStore]），不依赖 namePrefix（Cerelax 系列广播名前缀相同）。
 */
object TestConfig {

    /** 绑定应用 Context，并让配置/spec 重新从外部 JSON 读取。可重复调用。 */
    fun bind(context: Context) {
        ConfigStore.bind(context)
        SpecStore.bind(context)
        SpecStore.reload()
    }

    private val config: AppConfig get() = ConfigStore.current()

    // ---- 目标设备 ----

    /** 本轮目标设备 identity（逗号分隔，可多个）。 */
    val TARGET_IDENTITY: String get() = config.targetIdentity

    /** demo 页运行时切换目标设备（逗号分隔多 identity，如 "8517,A2A2"）。 */
    fun setTargetIdentity(value: String) = ConfigStore.setTargetIdentity(value)

    /** 目标设备写回设备端外部 config.json；返回是否成功。 */
    fun persistTargetIdentity(value: String): Boolean = ConfigStore.persistTargetIdentity(value)

    /** 本轮要测的 identity 列表（逗号分隔拆开）。 */
    val targetIdentities: List<String>
        get() = TARGET_IDENTITY.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** 设备清单。 */
    val DEVICES: List<TestDevice> get() = config.devices

    // ---- 型号 -> spec ----

    /** 按型号读取设备规格（对应 Python `common.load_spec`）。 */
    fun loadSpec(model: String): DeviceSpec = SpecStore.load(model)

    /** 按 identity 查设备型号再读 spec（对应 Python `common.spec_for_identity`）。 */
    fun specForIdentity(identity: String): DeviceSpec {
        val device = devicesByIdentity(identity).firstOrNull()
            ?: error("DEVICES 中未找到 identity: $identity")
        return loadSpec(device.model)
    }

    /** 本轮目标设备的型号（多 identity 时取第一个）。 */
    fun targetModel(): String =
        targetIdentities.firstNotNullOfOrNull { id -> devicesByIdentity(id).firstOrNull()?.model }
            ?: error("targetIdentity=$TARGET_IDENTITY 未在 DEVICES 中登记，请检查 config.json")

    /** 本轮目标设备的 spec。 */
    fun targetSpec(): DeviceSpec = loadSpec(targetModel())

    // ---- 通用测试参数（外部 config.json 可覆盖）----

    val SCAN_TIMEOUT_MS: Int get() = config.params.scanTimeoutMs
    val SCAN_RETRY_INTERVAL_MS: Long get() = config.params.scanRetryIntervalMs
    val MULTI_BIN_PATHS: List<String> get() = config.params.multiBinPaths
    val MULTI_REPLAY_ENABLED: Boolean get() = config.params.multiReplayEnabled
    val CONNECT_TIMEOUT_MS: Long get() = config.params.connectTimeoutMs
    val READY_TIMEOUT_MS: Long get() = config.params.readyTimeoutMs
    val DATA_TIMEOUT_MS: Long get() = config.params.dataTimeoutMs

    /** 兼容 androidTest 旧命名（秒）。 */
    val DATA_TIMEOUT_SEC: Long get() = DATA_TIMEOUT_MS / 1000

    val PACKAGE_SAMPLE_COUNT: Int get() = config.params.packageSampleCount
    val INIT_TIMEOUT_MS: Int get() = config.params.initTimeoutMs
    val POWER_REFRESH_INTERVAL_MS: Int get() = config.params.powerRefreshMs

    /** 兼容 demo 侧旧命名。 */
    val POWER_REFRESH_MS: Int get() = POWER_REFRESH_INTERVAL_MS

    val CASE_SETTLE_MS: Long get() = config.params.caseSettleMs
    val COLLECT_SECONDS: Int get() = config.params.collectSeconds
    val MIN_SAMPLES: Int get() = config.params.minSamples
    val MANUAL_PAUSE_SECONDS: Long get() = config.params.manualPauseSeconds
    val PERF_MEASURE_SECONDS: Long get() = config.params.perfMeasureSeconds
    val SAMPLE_RATE_TOLERANCE: Double get() = config.params.sampleRateTolerance
    val LONGEVITY_STREAM_SECONDS: Long get() = config.params.longevityStreamSeconds
    val BIN_PATH: String get() = config.params.binPath

    // ---- 查询 ----

    /** 按 identity 在设备清单里查找。 */
    fun devicesByIdentity(identity: String): List<TestDevice> =
        DEVICES.filter { it.identity.equals(identity, ignoreCase = true) }

    /** 按 MAC 在设备清单里查找。 */
    fun deviceByMac(mac: String): TestDevice? =
        DEVICES.firstOrNull { it.mac.isNotEmpty() && it.mac.equals(mac, ignoreCase = true) }

    /**
     * 扫描到的 BLE 设备是否命中本轮目标（mac 精确 > 广播名后四位 identity）。
     *
     * 注意：devices 清单可同时登记多台设备（多设备用例），所以 mac 命中只能说明
     * 它是「已登记设备」，还必须其 identity 属于本轮 [targetIdentities] 才算是目标，
     * 否则登记了 gForceMax / OB6000C 后，测 Cerelax 时会把它们也误判为目标。
     */
    fun matchesTarget(device: BleDevice): Boolean {
        val entry = deviceByMac(device.mac)
        if (entry != null) {
            return targetIdentities.any { it.equals(entry.identity, ignoreCase = true) }
        }
        val name = device.name.orEmpty()
        return targetIdentities.any { id ->
            name.endsWith("($id)", ignoreCase = true) || name.endsWith(id, ignoreCase = true)
        }
    }

    /** 扫描到的 BLE 设备是否在已登记设备清单内（多设备用例用）。 */
    fun matchesKnown(device: BleDevice): Boolean =
        deviceByMac(device.mac) != null || DEVICES.any { matches(device, it) }

    private fun matches(device: BleDevice, entry: TestDevice): Boolean {
        if (entry.mac.isNotEmpty() && device.mac.equals(entry.mac, ignoreCase = true)) return true
        val name = device.name.orEmpty()
        return name.endsWith("(${entry.identity})", ignoreCase = true) ||
            name.endsWith(entry.identity, ignoreCase = true)
    }
}
