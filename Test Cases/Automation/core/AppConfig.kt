package com.oymotion.sensorcapiktdemo

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 测试设备登记项（对应 Python `config.py` 的 `DEVICES` 元素）。
 *
 * 匹配优先级：mac 精确 > 广播名后四位 identity。
 * model 是 spec 加载键（与 `device_specs/<model>.json` 文件名一致）。
 */
data class TestDevice(
    val model: String,
    val namePrefix: String,
    val mac: String,
    val identity: String,
)

/** 通用测试参数（对应 Python `config.py` 的通用常量）。 */
data class AutomationParams(
    val scanTimeoutMs: Int = 5000,
    val scanRetryIntervalMs: Long = 10_000,
    val connectTimeoutMs: Long = 15_000,
    val readyTimeoutMs: Long = 15_000,
    val dataTimeoutMs: Long = 12_000,
    val initTimeoutMs: Int = 30_000,
    val packageSampleCount: Int = 20,
    val powerRefreshMs: Int = 1_000,
    val caseSettleMs: Long = 1_000,
    val collectSeconds: Int = 5,
    val minSamples: Int = 1,
    val manualPauseSeconds: Long = 30,
    val perfMeasureSeconds: Long = 10,
    val sampleRateTolerance: Double = 0.05,
    val longevityStreamSeconds: Long = 30,
    val binPath: String = "",
    /** 多文件同步回放用的预录 bin（显式配对，对应 RN 的 MULTI_BIN_PATHS）；不足 2 个时相关用例 SKIP。 */
    val multiBinPaths: List<String> = emptyList(),
    /** 多文件同时回放开关。已知 Android 原生层会杀进程，默认关闭；修复后改外部 config.json 开启。 */
    val multiReplayEnabled: Boolean = false,
)

/** 自动化运行配置（对应 Python `config.py`）。 */
data class AppConfig(
    val targetIdentity: String,
    val devices: List<TestDevice>,
    val params: AutomationParams,
) {
    /** 序列化为外部 config.json 结构（保持与 assets 默认同构，供 demo 页选择设备后写回）。 */
    fun toJson(): JSONObject = JSONObject().apply {
        put("targetIdentity", targetIdentity)
        put("devices", JSONArray().apply {
            devices.forEach { d ->
                put(JSONObject().apply {
                    put("model", d.model)
                    put("namePrefix", d.namePrefix)
                    put("mac", d.mac)
                    put("identity", d.identity)
                })
            }
        })
        put("params", JSONObject().apply {
            put("scanTimeoutMs", params.scanTimeoutMs)
            put("scanRetryIntervalMs", params.scanRetryIntervalMs)
            put("connectTimeoutMs", params.connectTimeoutMs)
            put("readyTimeoutMs", params.readyTimeoutMs)
            put("dataTimeoutMs", params.dataTimeoutMs)
            put("initTimeoutMs", params.initTimeoutMs)
            put("packageSampleCount", params.packageSampleCount)
            put("powerRefreshMs", params.powerRefreshMs)
            put("caseSettleMs", params.caseSettleMs)
            put("collectSeconds", params.collectSeconds)
            put("minSamples", params.minSamples)
            put("manualPauseSeconds", params.manualPauseSeconds)
            put("perfMeasureSeconds", params.perfMeasureSeconds)
            put("sampleRateTolerance", params.sampleRateTolerance)
            put("longevityStreamSeconds", params.longevityStreamSeconds)
            put("binPath", params.binPath)
            put("multiBinPaths", JSONArray().apply { params.multiBinPaths.forEach { put(it) } })
            put("multiReplayEnabled", params.multiReplayEnabled)
        })
    }

    companion object {
        /** 内置默认值：与 `Test Cases/Automation/config/config.json` 保持一致。 */
        val DEFAULT = AppConfig(
            targetIdentity = "8517",
            devices = listOf(
                TestDevice("Cerelax-Pro", "Cerelax", "BC:93:2A:3F:85:17", "8517"),
                TestDevice("gForceMax", "gForceMax", "BC:93:2A:3F:A2:A2", "A2A2"),
                TestDevice("OB6000C", "OB6000C", "78:1C:9D:E4:6C:6B", "6C6B"),
            ),
            params = AutomationParams(
                binPath = "/storage/emulated/0/Documents/sensorsdklog/multi_1794826218381_0.bin",
            ),
        )

        fun fromJson(json: JSONObject, fallback: AppConfig = DEFAULT): AppConfig {
            val devices = ArrayList<TestDevice>()
            json.optJSONArray("devices")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    val identity = item.optString("identity")
                    if (identity.isBlank()) continue
                    devices += TestDevice(
                        model = item.optString("model"),
                        namePrefix = item.optString("namePrefix"),
                        mac = item.optString("mac"),
                        identity = identity,
                    )
                }
            }

            val p = json.optJSONObject("params")
            val d = fallback.params
            val params = AutomationParams(
                scanTimeoutMs = p?.optInt("scanTimeoutMs", d.scanTimeoutMs) ?: d.scanTimeoutMs,
                scanRetryIntervalMs = p?.optLong("scanRetryIntervalMs", d.scanRetryIntervalMs) ?: d.scanRetryIntervalMs,
                connectTimeoutMs = p?.optLong("connectTimeoutMs", d.connectTimeoutMs) ?: d.connectTimeoutMs,
                readyTimeoutMs = p?.optLong("readyTimeoutMs", d.readyTimeoutMs) ?: d.readyTimeoutMs,
                dataTimeoutMs = p?.optLong("dataTimeoutMs", d.dataTimeoutMs) ?: d.dataTimeoutMs,
                initTimeoutMs = p?.optInt("initTimeoutMs", d.initTimeoutMs) ?: d.initTimeoutMs,
                packageSampleCount = p?.optInt("packageSampleCount", d.packageSampleCount) ?: d.packageSampleCount,
                powerRefreshMs = p?.optInt("powerRefreshMs", d.powerRefreshMs) ?: d.powerRefreshMs,
                caseSettleMs = p?.optLong("caseSettleMs", d.caseSettleMs) ?: d.caseSettleMs,
                collectSeconds = p?.optInt("collectSeconds", d.collectSeconds) ?: d.collectSeconds,
                minSamples = p?.optInt("minSamples", d.minSamples) ?: d.minSamples,
                manualPauseSeconds = p?.optLong("manualPauseSeconds", d.manualPauseSeconds) ?: d.manualPauseSeconds,
                perfMeasureSeconds = p?.optLong("perfMeasureSeconds", d.perfMeasureSeconds) ?: d.perfMeasureSeconds,
                sampleRateTolerance = p?.optDouble("sampleRateTolerance", d.sampleRateTolerance) ?: d.sampleRateTolerance,
                longevityStreamSeconds = p?.optLong("longevityStreamSeconds", d.longevityStreamSeconds) ?: d.longevityStreamSeconds,
                binPath = p?.optString("binPath", d.binPath) ?: d.binPath,
                multiBinPaths = p?.optJSONArray("multiBinPaths")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
                } ?: d.multiBinPaths,
                multiReplayEnabled = p?.optBoolean("multiReplayEnabled", d.multiReplayEnabled)
                    ?: d.multiReplayEnabled,
            )

            return AppConfig(
                targetIdentity = json.optString("targetIdentity", fallback.targetIdentity),
                devices = devices.ifEmpty { fallback.devices },
                params = params,
            )
        }
    }
}

/**
 * 配置仓库：外部 JSON 优先，缺失/损坏回退 assets，再回退内置默认，不崩溃。
 *
 * 外部路径：`<externalFilesDir>/automation/config.json`（改目标设备无需重编 APK）。
 * 内置路径：APK assets `automation/config.json`（由 Gradle `syncAutomationAssets` 生成）。
 */
object ConfigStore {

    private const val TAG = "ConfigStore"
    private const val ASSET_PATH = "automation/config.json"
    private const val EXTERNAL_PATH = "automation/config.json"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cached: AppConfig? = null

    /** demo 页运行时选择的目标设备（逗号分隔多 identity）；null 表示用配置文件里的值。 */
    @Volatile
    private var targetOverride: String? = null

    fun bind(context: Context) {
        appContext = context.applicationContext
        cached = null
    }

    fun reload() {
        cached = null
    }

    /** 运行时切换目标设备（不改文件，供 demo 自动化页使用）。 */
    fun setTargetIdentity(value: String) {
        targetOverride = value.trim().ifEmpty { null }
        cached = null
    }

    /**
     * 把目标设备写回设备端外部 `automation/config.json`，让 instrumentation 套件与下次启动保持一致。
     * 无 Context（未 bind）或写文件失败返回 false，不抛异常。
     */
    fun persistTargetIdentity(value: String): Boolean {
        val context = appContext ?: return false
        return runCatching {
            val file = File(context.getExternalFilesDir(null) ?: context.filesDir, EXTERNAL_PATH)
            file.parentFile?.mkdirs()
            file.writeText(current().copy(targetIdentity = value).toJson().toString(2))
            true
        }.onFailure { Log.w(TAG, "写回外部 $EXTERNAL_PATH 失败", it) }.getOrDefault(false)
    }

    fun current(): AppConfig {
        val base = cached ?: load().also { cached = it }
        return targetOverride?.let { base.copy(targetIdentity = it) } ?: base
    }

    private fun load(): AppConfig {
        readExternal()?.let { return it }
        readAsset()?.let { return it }
        return AppConfig.DEFAULT
    }

    private fun readExternal(): AppConfig? {
        val context = appContext ?: return null
        val file = File(context.getExternalFilesDir(null) ?: context.filesDir, EXTERNAL_PATH)
        if (!file.isFile) return null
        return runCatching { AppConfig.fromJson(JSONObject(file.readText())) }
            .onFailure { Log.w(TAG, "外部 config 解析失败，回退 assets: ${file.absolutePath}", it) }
            .getOrNull()
    }

    private fun readAsset(): AppConfig? {
        val context = appContext ?: return null
        return runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
                AppConfig.fromJson(JSONObject(reader.readText()))
            }
        }.onFailure { Log.w(TAG, "assets config 读取失败: $ASSET_PATH", it) }
            .getOrNull()
    }
}
