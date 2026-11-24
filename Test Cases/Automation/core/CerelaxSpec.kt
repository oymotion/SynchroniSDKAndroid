package com.oymotion.sensorcapiktdemo.cerelaxpro

import com.oymotion.sensor.capi.DataType
import com.oymotion.sensor.capi.DeviceInfo
import com.oymotion.sensorcapiktdemo.devicespecs.DeviceSpec

/**
 * Spec 驱动的能力判定/命名映射辅助。
 *
 * 背景：`device_specs` 里 streams 的 key 是 setParam 的开关 key（如 `NTF_GFORCE_ACC`），
 * 与数据回调里的 DataType 常量（`NTF_ACC`）以及 DeviceInfo 的通道字段（`accChannelCount`）
 * 不是同一套命名。本对象做三层映射，让用例按 spec 自动适配型号差异。
 *
 * 判定口径（与 Python 侧一致）：以 spec 的 `supported`/`channels` 为唯一事实来源；
 * 支持流按期望通道数硬断言，不支持流断言不应产出数据。
 *
 * 注意：AAR 1.4.x 已把磁力计/角度拆为 `NTF_MAG` 与 `NTF_ANGLE`，
 * 不存在 `NTF_MAG_ANGLE` 常量；DeviceInfo 对应 `magChannelCount` / `angleChannelCount`。
 */
object CerelaxSpec {

    /** spec stream key -> DataType 常量（数据回调 getDataType() 的取值）。 */
    val KEY_TO_DATA_TYPE: Map<String, Int> = mapOf(
        "NTF_EMG" to DataType.NTF_EMG,
        "NTF_GEST" to DataType.NTF_GEST,
        "NTF_GFORCE_ACC" to DataType.NTF_ACC,
        "NTF_GFORCE_GYRO" to DataType.NTF_GYRO,
        "NTF_GFORCE_EULER" to DataType.NTF_EULER,
        "NTF_GFORCE_QUAT" to DataType.NTF_QUATERNION,
        "NTF_IMU" to DataType.NTF_IMU,
        "NTF_MAG" to DataType.NTF_MAG,
        "NTF_ANGLE_DATA" to DataType.NTF_ANGLE,
        "NTF_IMPEDANCE" to DataType.NTF_IMPEDANCE,
        "NTF_EEG" to DataType.NTF_EEG,
        "NTF_ECG" to DataType.NTF_ECG,
        "NTF_BRTH" to DataType.NTF_BRTH,
        "NTF_PPG" to DataType.NTF_PPG,
        "NTF_SPO2" to DataType.NTF_SPO2,
    )

    /** spec stream key -> setParam/getParam 开关 key（大多数与 spec key 相同）。 */
    fun paramKey(streamKey: String): String = when (streamKey) {
        "NTF_ANGLE_DATA" -> "NTF_ANGLE"
        else -> streamKey
    }

    /** spec 中 supported=true 的流 key 列表。 */
    fun supportedStreamKeys(spec: DeviceSpec): List<String> = spec.supportedStreams

    /** spec stream key -> 期望通道数；未注册返回 null。 */
    fun expectedChannels(spec: DeviceSpec, key: String): Int? = spec.streams[key]?.channels

    /**
     * 解析 `NTF_EEG|ON|NTF_IMU|OFF|...` 形式的聚合开关列表。
     *
     * 设备 getParam("NTF")/getParam("FILTER") 的返回值是权威的“真实可切换键”列表，
     * 不能仅凭 spec 的 ChannelCount>0 推断（如 Cerelax 的 ACC/GYRO 无独立开关）。
     */
    fun parseSwitchList(raw: String): List<Pair<String, String>> =
        Regex("([A-Z0-9_]+)\\|(ON|OFF)").findAll(raw)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

    /**
     * 某个流开关 key 实际可能产出的 DataType 列表。
     *
     * 部分设备的聚合流不单独回传聚合类型，而是拆成子流：例如 Cerelax 的 `NTF_IMU`
     * （DeviceInfo.imuChannelCount=6）实测回调为 `NTF_ACC`(3) + `NTF_GYRO`(3)，
     * 从不出现 `NTF_IMU`。等待数据时应同时接受「聚合」与「拆分子流」两种形态。
     */
    fun dataTypesForKey(key: String): List<Int> = when (key) {
        "NTF_IMU" -> listOf(DataType.NTF_IMU, DataType.NTF_ACC, DataType.NTF_GYRO)
        else -> listOfNotNull(KEY_TO_DATA_TYPE[key])
    }

    /**
     * 聚合流（目前只有 NTF_IMU）的通道布局是否满足 spec：
     * 优先认聚合类型通道数 = spec.channels；否则认子流 ACC/GYRO 通道数分别等于 imuSubChannels。
     */
    fun aggregateLayoutOk(spec: DeviceSpec, key: String, got: Map<Int, Int>): Boolean {
        if (key != "NTF_IMU") {
            val type = KEY_TO_DATA_TYPE[key] ?: return false
            return got[type] == expectedChannels(spec, key)
        }
        // 首选聚合类型
        if (got[DataType.NTF_IMU] == spec.channels("NTF_IMU")) return true
        // 其次按 spec 声明的子流逐一校验（Cerelax: ACC3+GYRO3；gForceMax/OB6000C: +EULER3+QUAT4）
        var checked = 0
        for ((subKey, type) in IMU_SUB_TYPE_BY_COUNT) {
            val expected = spec.imuSubChannels[subKey] ?: 0
            if (expected <= 0) continue
            if (got[type] != expected) return false
            checked++
        }
        return checked > 0
    }

    /** imuSubChannels 字段 -> 对应的回调 DataType。 */
    private val IMU_SUB_TYPE_BY_COUNT: Map<String, Int> = linkedMapOf(
        "AccChannelCount" to DataType.NTF_ACC,
        "GyroChannelCount" to DataType.NTF_GYRO,
        "EulerChannelCount" to DataType.NTF_EULER,
        "QuatChannelCount" to DataType.NTF_QUATERNION,
    )

    /** DeviceInfo 里某个 spec stream key 对应的通道数字段（>0 支持，==0 不支持）。 */
    fun channelCount(info: DeviceInfo, key: String): Int = when (key) {
        "NTF_EMG" -> info.emgChannelCount
        "NTF_GEST" -> info.gestChannelCount
        "NTF_GFORCE_ACC" -> info.accChannelCount
        "NTF_GFORCE_GYRO" -> info.gyroChannelCount
        "NTF_GFORCE_EULER" -> info.eulerChannelCount
        "NTF_GFORCE_QUAT" -> info.quatChannelCount
        "NTF_IMU" -> info.imuChannelCount
        "NTF_MAG" -> info.magChannelCount
        "NTF_ANGLE_DATA" -> info.angleChannelCount
        "NTF_IMPEDANCE" -> info.impeChannelCount
        "NTF_EEG" -> info.eegChannelCount
        "NTF_ECG" -> info.ecgChannelCount
        "NTF_BRTH" -> info.brthChannelCount
        "NTF_PPG" -> info.ppgChannelCount
        "NTF_SPO2" -> info.spo2ChannelCount
        else -> 0
    }

    /** DeviceInfo 里某个 spec stream key 对应的采样率字段。 */
    fun sampleRate(info: DeviceInfo, key: String): Double = when (key) {
        "NTF_EMG" -> info.emgSampleRate
        "NTF_GEST" -> info.gestSampleRate
        "NTF_GFORCE_ACC" -> info.accSampleRate
        "NTF_GFORCE_GYRO" -> info.gyroSampleRate
        "NTF_GFORCE_EULER" -> info.eulerSampleRate
        "NTF_GFORCE_QUAT" -> info.quatSampleRate
        "NTF_IMU" -> info.imuSampleRate
        "NTF_MAG" -> info.magSampleRate
        "NTF_ANGLE_DATA" -> info.angleSampleRate
        "NTF_IMPEDANCE" -> info.impeSampleRate
        "NTF_EEG" -> info.eegSampleRate
        "NTF_ECG" -> info.ecgSampleRate
        "NTF_BRTH" -> info.brthSampleRate
        "NTF_PPG" -> info.ppgSampleRate
        "NTF_SPO2" -> info.spo2SampleRate
        else -> 0f
    }.toDouble()
}
