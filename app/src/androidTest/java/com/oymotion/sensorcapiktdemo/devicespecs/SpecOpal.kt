package com.oymotion.sensorcapiktdemo.devicespecs

/**
 * Opal 脑电头环设备规格（Spec）——单一事实来源。
 *
 * 与 Cerelax-Pro 的差异仅在采样率档位：
 *   - EEG_SAMPLE_RATE = 250（单档；Pro 为 250/500/1000）
 *   - IMU_SAMPLE_RATE = 50（单档；Pro 为 50/100/200）
 *   - PPG_SAMPLE_RATE = 50|100（两档；Pro 为 50/100/200/400）
 *   - 阻抗固定 1Hz（与 Pro 一致；Kotlin 侧 sampleRates 不单独建模阻抗）
 *   - 各数据流通道数与 Cerelax-Pro 完全一致（EEG=2 / IMU=6 / PPG=2 / SpO2=2 / 阻抗=2）
 *
 * 关键判定口径：以 DeviceInfo 的 ChannelCount 为准（>0 才算支持），
 * 而非 getParam('NTF_XXX') 返回 'ON'。
 */
object SpecOpal {
    val SPEC = DeviceSpec(
        model = "Opal",
        type = "脑电头环（2 通道 EEG + 2 通道 PPG + 6 轴 IMU 聚合 + 电极接触检测）",
        streams = mapOf(
            "NTF_EMG"          to StreamSpec(supported = false, channels = 0),
            "NTF_GEST"         to StreamSpec(supported = false, channels = 0),
            "NTF_GFORCE_ACC"   to StreamSpec(supported = true,  channels = 3),
            "NTF_GFORCE_GYRO"  to StreamSpec(supported = true,  channels = 3),
            "NTF_GFORCE_EULER" to StreamSpec(supported = false, channels = 0),
            "NTF_GFORCE_QUAT"  to StreamSpec(supported = false, channels = 0),
            "NTF_IMU"          to StreamSpec(supported = true,  channels = 6),
            "NTF_MAG_ANGLE"    to StreamSpec(supported = false, channels = 0),
            "NTF_IMPEDANCE"    to StreamSpec(supported = true,  channels = 2),
            "NTF_EEG"          to StreamSpec(supported = true,  channels = 2),
            "NTF_ECG"          to StreamSpec(supported = false, channels = 0),
            "NTF_BRTH"         to StreamSpec(supported = false, channels = 0),
            "NTF_PPG"          to StreamSpec(supported = true,  channels = 2),
            "NTF_SPO2"         to StreamSpec(supported = true,  channels = 2),
        ),
        filters = mapOf(
            "FILTER_50HZ" to true,
            "FILTER_60HZ" to true,
            "FILTER_HPF"  to true,
            "FILTER_LPF"  to true,
        ),
        sampleRates = mapOf(
            "EEG_SAMPLE_RATE" to SampleRateSpec(rates = listOf("250"), default = "250"),
            "IMU_SAMPLE_RATE" to SampleRateSpec(rates = listOf("50"), default = "50"),
            "PPG_SAMPLE_RATE" to SampleRateSpec(rates = listOf("50", "100"), default = "50"),
        ),
    )
}
