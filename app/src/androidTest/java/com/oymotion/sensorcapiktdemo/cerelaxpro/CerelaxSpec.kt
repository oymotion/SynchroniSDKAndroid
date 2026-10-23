package com.oymotion.sensorcapiktdemo.cerelaxpro

import com.oymotion.sensor.capi.DataType
import com.oymotion.sensor.capi.DeviceInfo
import com.oymotion.sensorcapiktdemo.devicespecs.DeviceSpec

/**
 * Spec 驱动的能力判定/命名映射辅助。
 *
 * 背景：device_specs 里 streams 的 key 是 setParam 的开关 key（如 `NTF_GFORCE_ACC`），
 * 与数据回调里的 DataType 枚举（`NTF_ACC`）以及 DeviceInfo 的通道字段（`accChannelCount`）
 * 不是同一套命名。本对象做三层映射，让数据流/参数用例按 spec 自动适配型号差异，
 * 而不是在用例里硬编码 EMG/EEG 等型号专属值。
 *
 * 判定口径（与 Python 侧一致）：以 spec 的 `supported`/`channels` 为唯一事实来源；
 * 支持流按期望通道数硬断言，不支持流断言不应产出数据。
 */
object CerelaxSpec {

    /** spec stream key -> DataType 枚举（数据回调 getDataType() 的取值）。 */
    val KEY_TO_DATA_TYPE: Map<String, Int> = mapOf(
        "NTF_EMG" to DataType.NTF_EMG,
        "NTF_GEST" to DataType.NTF_GEST,
        "NTF_GFORCE_ACC" to DataType.NTF_ACC,
        "NTF_GFORCE_GYRO" to DataType.NTF_GYRO,
        "NTF_GFORCE_EULER" to DataType.NTF_EULER,
        "NTF_GFORCE_QUAT" to DataType.NTF_QUATERNION,
        "NTF_IMU" to DataType.NTF_IMU,
        "NTF_MAG_ANGLE" to DataType.NTF_MAG_ANGLE,
        "NTF_IMPEDANCE" to DataType.NTF_IMPEDANCE,
        "NTF_EEG" to DataType.NTF_EEG,
        "NTF_ECG" to DataType.NTF_ECG,
        "NTF_BRTH" to DataType.NTF_BRTH,
        "NTF_PPG" to DataType.NTF_PPG,
        "NTF_SPO2" to DataType.NTF_SPO2,
    )

    /** spec 中 supported=true 的流 key 列表。 */
    fun supportedStreamKeys(spec: DeviceSpec): List<String> =
        spec.streams.filter { it.value.supported }.map { it.key }

    /** spec 中 supported=false 的流 key 列表。 */
    fun unsupportedStreamKeys(spec: DeviceSpec): List<String> =
        spec.streams.filter { !it.value.supported }.map { it.key }

    /** spec stream key -> 期望通道数；未注册返回 null。 */
    fun expectedChannels(spec: DeviceSpec, key: String): Int? =
        spec.streams[key]?.channels

    /** DeviceInfo 里某个 spec stream key 对应的通道数字段（>0 支持，==0 不支持）。 */
    fun channelCount(info: DeviceInfo, key: String): Int = when (key) {
        "NTF_EMG" -> info.emgChannelCount
        "NTF_GEST" -> 0 // 手势是单值流，无独立通道数字段，用 supported 判定
        "NTF_GFORCE_ACC" -> info.accChannelCount
        "NTF_GFORCE_GYRO" -> info.gyroChannelCount
        "NTF_GFORCE_EULER" -> info.eulerChannelCount
        "NTF_GFORCE_QUAT" -> info.quatChannelCount
        "NTF_IMU" -> info.imuChannelCount
        "NTF_MAG_ANGLE" -> info.magAngleChannelCount
        "NTF_IMPEDANCE" -> info.impeChannelCount
        "NTF_EEG" -> info.eegChannelCount
        "NTF_ECG" -> info.ecgChannelCount
        "NTF_BRTH" -> info.brthChannelCount
        "NTF_PPG" -> info.ppgChannelCount
        "NTF_SPO2" -> info.spo2ChannelCount
        else -> 0
    }

    /** spec 采样率参数 key 列表（如 EEG_SAMPLE_RATE / IMU_SAMPLE_RATE / PPG_SAMPLE_RATE）。 */
    fun sampleRateKeys(spec: DeviceSpec): List<String> = spec.sampleRates.keys.toList()
}
