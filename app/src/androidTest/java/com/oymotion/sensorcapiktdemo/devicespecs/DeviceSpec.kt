package com.oymotion.sensorcapiktdemo.devicespecs

/**
 * 设备规格（Device Spec）数据类——单一事实来源。
 *
 * 对应 Python 侧 device_specs 目录下 .py 的 SPEC 字典结构：
 *   streams      -> Map<String, StreamSpec>      数据流能力（supported/channels）
 *   filters      -> Map<String, Boolean>         滤波开关（FILTER_* 支持 ON/OFF）
 *   sample_rates -> Map<String, SampleRateSpec>  采样率参数（rates/default）
 *
 * 测试用例从 spec 读取期望值做硬断言，设备行为偏离 spec 即 FAIL。
 */

/** 单条数据流的能力基线（对应 DeviceInfo 的 ChannelCount 期望）。 */
data class StreamSpec(
    val supported: Boolean,
    val channels: Int,
)

/** 一套采样率参数的能力基线。 */
data class SampleRateSpec(
    val rates: List<String>,
    val default: String,
)

/** 设备型号规格。 */
data class DeviceSpec(
    val model: String,
    val type: String,
    val streams: Map<String, StreamSpec>,
    val filters: Map<String, Boolean>,
    val sampleRates: Map<String, SampleRateSpec>,
)
