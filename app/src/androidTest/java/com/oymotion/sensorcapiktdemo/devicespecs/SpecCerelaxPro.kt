package com.oymotion.sensorcapiktdemo.devicespecs

/**
 * Cerelax-Pro 脑电头环设备规格（Spec）——单一事实来源。
 *
 * 对应 Python 侧 device_specs/cerelax_pro.py，基于 851C 实测固化：
 *   - EegChannelCount = 2；PpgChannelCount = 2；Spo2ChannelCount = 2
 *   - ImpeChannelCount = 2；AccChannelCount = 3；GyroChannelCount = 3
 *   - ImuChannelCount = 6（acc3+gyro3 聚合，无磁力计）
 *   - Emg/Ecg/Brth/MagAngle/Euler/Quat ChannelCount = 0（不支持）
 *   - EEG_SAMPLE_RATE_LIST = 250|500|1000；IMU = 50|100|200；PPG = 50|100|200|400
 *
 * 关键判定口径：以 DeviceInfo 的 ChannelCount 为准（>0 才算支持），
 * 而非 getParam('NTF_XXX') 返回 'ON'。
 */
object SpecCerelaxPro {
    val SPEC = DeviceSpec(
        model = "Cerelax-Pro",
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
            "EEG_SAMPLE_RATE" to SampleRateSpec(rates = listOf("250", "500", "1000"), default = "1000"),
            "IMU_SAMPLE_RATE" to SampleRateSpec(rates = listOf("50", "100", "200"), default = "200"),
            "PPG_SAMPLE_RATE" to SampleRateSpec(rates = listOf("50", "100", "200", "400"), default = "50"),
        ),
    )
}
