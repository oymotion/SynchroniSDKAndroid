package com.oymotion.sensorcapiktdemo.automation.probes

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import com.oymotion.sensorcapiktdemo.cerelaxpro.CerelaxSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * 设备能力探针（对应 Python `probe_device_info.py`）。
 *
 * 流程：扫描选设备 -> connect/init -> getDeviceInfo（失败再 fetchDeviceInfo）-> dump 全部能力字段
 * -> getParam 聚合/逐 key/采样率档位 -> 起流 N 秒按 DataType 分桶统计 -> 推断 identity/namePrefix
 * -> 生成 spec 骨架，写 `files/automation/probes/probe_<identity>.json` 与 `latest_probe.json`。
 *
 * 运行参数（instrumentation arguments）：
 *   probeIdentity : 蓝牙地址后四位（如 850B），不传则按 probeIndex 或逐台扫描
 *   probeIndex    : 扫描结果下标（从 0 开始）
 *   streamSeconds : 起流采样时长（秒，默认 5）
 *
 * 宿主机入口：`gradlew probeDevice`（会跑本测试 -> adb pull -> 生成 device_specs/<model>.json）。
 */
@RunWith(AndroidJUnit4::class)
class ProbeDeviceTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    private val streamKeyOrder = listOf(
        "NTF_EMG", "NTF_GEST", "NTF_GFORCE_ACC", "NTF_GFORCE_GYRO", "NTF_GFORCE_EULER",
        "NTF_GFORCE_QUAT", "NTF_IMU", "NTF_MAG", "NTF_ANGLE_DATA", "NTF_IMPEDANCE",
        "NTF_EEG", "NTF_ECG", "NTF_BRTH", "NTF_PPG", "NTF_SPO2",
    )

    private val sampleRateCandidates = linkedMapOf(
        "EMG_SAMPLE_RATE" to "NTF_EMG",
        "EEG_SAMPLE_RATE" to "NTF_EEG",
        "ECG_SAMPLE_RATE" to "NTF_ECG",
        "BRTH_SAMPLE_RATE" to "NTF_BRTH",
        "IMU_SAMPLE_RATE" to "NTF_IMU",
        "PPG_SAMPLE_RATE" to "NTF_PPG",
        "SPO2_SAMPLE_RATE" to "NTF_SPO2",
    )

    @Test
    fun probe() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val controller = SensorController.getInstance()
        controller.registerBleBridge(context)
        TestConfig.bind(context)

        val args = InstrumentationRegistry.getArguments()
        val wantIdentity = args.getString("probeIdentity").orEmpty()
        val wantIndex = args.getString("probeIndex")?.toIntOrNull()
        val streamSeconds = args.getString("streamSeconds")?.toLongOrNull() ?: 5L

        val scanned = controller.scan(TestConfig.SCAN_TIMEOUT_MS)
        require(scanned.isNotEmpty()) { "扫描结果为空，请确认设备开机" }

        val targets = when {
            wantIdentity.isNotBlank() -> scanned.filter { matchesIdentity(it, wantIdentity) }
            wantIndex != null -> listOfNotNull(scanned.getOrNull(wantIndex))
            else -> scanned.filter { it.name != null }
        }
        require(targets.isNotEmpty()) {
            "未匹配到要探测的设备（identity=$wantIdentity index=$wantIndex）；扫描到 ${scanned.size} 台"
        }

        for (device in targets) {
            val result = runCatching { probeOne(controller, device, streamSeconds) }
            result.onFailure { println("探针失败 ${device.mac}: ${it.message}") }
            result.onSuccess { writeResult(context, it) }
        }
    }

    private suspend fun probeOne(
        controller: SensorController,
        device: BleDevice,
        streamSeconds: Long,
    ): JSONObject {
        val profile = controller.requireSensor(device.mac) ?: error("requireSensor 返回 null")
        check(profile.connect()) { "connect 失败" }
        val deadline = System.currentTimeMillis() + TestConfig.READY_TIMEOUT_MS
        while (!profile.isReady() && System.currentTimeMillis() < deadline) delay(100)
        check(profile.isReady()) { "未到达 Ready" }
        check(
            profile.init(
                TestConfig.PACKAGE_SAMPLE_COUNT,
                TestConfig.INIT_TIMEOUT_MS,
                TestConfig.POWER_REFRESH_INTERVAL_MS
            )
        ) { "init 失败" }

        val info = profile.getDeviceInfo() ?: fetchDeviceInfo(profile, 3000)
            ?: error("getDeviceInfo/fetchDeviceInfo 均未返回 DeviceInfo")

        val params = collectParams(profile)
        val measured = measureStreams(profile, streamSeconds)

        val name = device.name.orEmpty()
        val identity = Regex("\\(([0-9A-Fa-f]{4})\\)").find(name)?.groupValues?.get(1)
            ?: device.mac.replace(":", "").takeLast(4).uppercase()
        val namePrefix = name.substringBefore('(').trim().ifBlank { info.modelName.orEmpty() }
        val model = info.modelName?.takeIf { it.isNotBlank() } ?: namePrefix

        return JSONObject().apply {
            put("model", model)
            put("identity", identity)
            put("namePrefix", namePrefix)
            put("mac", device.mac)
            put("deviceName", name)
            put("deviceInfo", dumpDeviceInfo(info))
            put("params", params)
            put("measure", measured)
            put("spec", buildSpec(model, namePrefix, info, params, measured))
        }
    }

    private suspend fun fetchDeviceInfo(profile: SensorProfile, timeoutMs: Int): DeviceInfo? =
        suspendCancellableCoroutine { cont ->
            profile.fetchDeviceInfo(timeoutMs) { info, _ ->
                if (cont.isActive) cont.resume(info)
            }
        }

    /** getParam 聚合值（NTF/FILTER）+ 常见的逐 key 当前值/档位列表。 */
    private suspend fun collectParams(profile: SensorProfile): JSONObject {
        val ntf = safeParam(profile, "NTF")
        val filter = safeParam(profile, "FILTER")
        val keys = JSONObject()
        for (key in streamKeyOrder) {
            val current = safeParam(profile, CerelaxSpec.paramKey(key))
            if (current.isNotBlank()) keys.put(CerelaxSpec.paramKey(key), current)
        }
        val sampleRates = JSONObject()
        for ((key, stream) in sampleRateCandidates) {
            val current = safeParam(profile, key)
            val list = safeParam(profile, "${key}_LIST")
            if (current.isBlank() || responseIsError(current)) continue
            sampleRates.put(key, JSONObject().apply {
                put("current", current)
                put("list", list)
                put("stream", stream)
            })
        }
        return JSONObject().apply {
            put("NTF", ntf)
            put("FILTER", filter)
            put("streams", keys)
            put("sampleRates", sampleRates)
        }
    }

    private suspend fun safeParam(profile: SensorProfile, key: String): String =
        runCatching { profile.getParam(key) }.getOrDefault("")

    private fun responseIsError(value: String): Boolean =
        value.startsWith("Error") || value.contains("Not supported", ignoreCase = true)

    /** 起流 N 秒，按 DataType 分桶统计通道/采样率/样本/首末 index。 */
    private suspend fun measureStreams(profile: SensorProfile, seconds: Long): JSONObject {
        val buckets = ConcurrentHashMap<Int, MutableList<SensorData>>()
        profile.setOnDataListener { _, list ->
            for (data in list) {
                buckets.getOrPut(data.dataType) { ArrayList() }.add(data.clone())
            }
        }
        check(profile.startDataNotification()) { "startDataNotification 失败" }
        try {
            delay(seconds * 1000)
        } finally {
            profile.stopDataNotification()
            profile.setOnDataListener { _, _ -> }
        }

        val result = JSONObject()
        for ((type, list) in buckets) {
            val first = list.firstOrNull() ?: continue
            val name = DataType.nameOf(type)
            val last = list.lastOrNull() ?: first
            result.put(name, JSONObject().apply {
                put("dataType", type)
                put("channels", list.maxOf { it.channelCount })
                put("sampleRate", list.maxOf { it.sampleRate })
                put("packets", list.size)
                put("samples", list.sumOf { it.sampleCount })
                put("firstIndex", first.startSampleIndex)
                put("lastIndex", last.startSampleIndex)
                put("lostPackages", list.sumOf { it.lostPackageCount })
            })
        }
        return result
    }

    /** 把 DeviceInfo 全部字段 dump 成 JSON（反射，避免漏字段）。 */
    private fun dumpDeviceInfo(info: DeviceInfo): JSONObject {
        val json = JSONObject()
        for (field in info.javaClass.fields) {
            if (java.lang.reflect.Modifier.isStatic(field.modifiers)) continue
            runCatching { json.put(field.name, field.get(info)) }
        }
        return json
    }

    /** 生成 Python SPEC 结构的 spec 骨架（实测通道优先，DeviceInfo 次之）。 */
    private fun buildSpec(
        model: String,
        namePrefix: String,
        info: DeviceInfo,
        params: JSONObject,
        measured: JSONObject,
    ): JSONObject {
        val streams = JSONObject()
        for (key in streamKeyOrder) {
            val byInfo = CerelaxSpec.channelCount(info, key)
            val dataType = CerelaxSpec.KEY_TO_DATA_TYPE[key]
            val byMeasure = dataType?.let { measured.optJSONObject(DataType.nameOf(it))?.optInt("channels", 0) } ?: 0
            val channels = maxOf(byInfo, byMeasure)
            streams.put(key, JSONObject().apply {
                put("supported", channels > 0)
                put("channels", channels)
            })
        }

        val imuSub = JSONObject().apply {
            put("AccChannelCount", info.accChannelCount)
            put("GyroChannelCount", info.gyroChannelCount)
            put("EulerChannelCount", info.eulerChannelCount)
            put("QuatChannelCount", info.quatChannelCount)
        }

        val filters = JSONObject()
        val filterAgg = params.optString("FILTER")
        if (filterAgg.isNotBlank() && !responseIsError(filterAgg)) {
            for (token in filterAgg.split("|").filter { it.isNotBlank() }) {
                if (token.startsWith("FILTER_")) filters.put(token, true)
            }
        }

        val sampleRates = JSONObject()
        val probed = params.optJSONObject("sampleRates") ?: JSONObject()
        for ((key, value) in sampleRateCandidates) {
            val entry = probed.optJSONObject(key) ?: continue
            val list = entry.optString("list")
            val rates = if (list.isBlank() || responseIsError(list)) {
                listOf(entry.optString("current").trim())
            } else {
                list.split("|", ",", " ").map { it.trim() }.filter { it.isNotEmpty() }
            }
            sampleRates.put(key, JSONObject().apply {
                put("rates", JSONArray(rates))
                put("default", entry.optString("current").trim())
                put("stream", value)
                put("settable", true)
                put("coStream", JSONObject.NULL)
                put("TODO", "采样率档位来自设备实测，请复核")
            })
        }
        if (((streams.optJSONObject("NTF_IMPEDANCE")?.optInt("channels", 0)) ?: 0) > 0) {
            sampleRates.put("IMPEDANCE", JSONObject().apply {
                put("rates", JSONArray(listOf("1")))
                put("default", "1")
                put("stream", "NTF_IMPEDANCE")
                put("settable", false)
                put("coStream", "NTF_EEG")
            })
        }

        return JSONObject().apply {
            put("model", model)
            put("type", "TODO: 补充设备类型描述")
            put("namePrefix", namePrefix)
            put("streams", streams)
            put("imuSubChannels", imuSub)
            put("filters", filters)
            put("sampleRates", sampleRates)
        }
    }

    private fun writeResult(context: android.content.Context, result: JSONObject) {
        val dir = File(context.getExternalFilesDir(null), "automation/probes")
        dir.mkdirs()
        val identity = result.optString("identity")
        File(dir, "probe_$identity.json").writeText(result.toString(2))
        File(dir, "latest_probe.json").writeText(result.toString(2))
        println("探针结果已写入: ${File(dir, "probe_$identity.json").absolutePath}")
    }

    private fun matchesIdentity(device: BleDevice, identity: String): Boolean {
        if (device.mac.equals(identity, ignoreCase = true)) return true
        val name = device.name.orEmpty()
        return name.endsWith("($identity)", ignoreCase = true) ||
            name.endsWith(identity, ignoreCase = true) ||
            device.mac.replace(":", "").endsWith(identity, ignoreCase = true)
    }
}
