package com.oymotion.sensorcapiktdemo.devicespecs

import org.json.JSONObject

/*
 * 设备规格（Device Spec）——单一事实来源。
 *
 * 对应 Python 侧 `Test Cases/Automation/device_specs/<model>.py` 的 SPEC 字典：
 *   streams        数据流能力（supported/channels）
 *   imuSubChannels 聚合流子通道组成（如 NTF_IMU = acc+gyro+euler+quat）
 *   filters        滤波开关（FILTER_* 支持 ON/OFF）
 *   sampleRates    采样率参数（档位/默认值/所属流/是否可设/共流）
 *
 * 运行时来源：设备端外部 JSON > assets 内置默认（见 [SpecStore]）。
 * 测试只按 spec + DeviceInfo 运行时结果断言，不把型号数值写进用例。
 */

/** 单条数据流的能力基线。 */
data class StreamSpec(
    val supported: Boolean,
    val channels: Int,
)

/** 一套采样率参数的能力基线。 */
data class SampleRateSpec(
    val rates: List<String>,
    val default: String,
    val stream: String = "",
    val settable: Boolean = true,
    val coStream: String? = null,
    val subStreams: List<String> = emptyList(),
)

/** 设备型号规格。 */
data class DeviceSpec(
    val model: String,
    val type: String = "",
    val namePrefix: String = "",
    val streams: Map<String, StreamSpec> = emptyMap(),
    val imuSubChannels: Map<String, Int> = emptyMap(),
    val filters: Map<String, Boolean> = emptyMap(),
    val sampleRates: Map<String, SampleRateSpec> = emptyMap(),
) {

    /** 真正支持的流 key（supported=true 且 channels>0）。 */
    val supportedStreams: List<String>
        get() = streams.filterValues { it.supported && it.channels > 0 }.keys.toList()

    /** 不支持的流 key。 */
    val unsupportedStreams: List<String>
        get() = streams.filterValues { !it.supported || it.channels <= 0 }.keys.toList()

    fun channels(key: String): Int = streams[key]?.channels ?: 0

    fun supports(key: String): Boolean = streams[key]?.let { it.supported && it.channels > 0 } ?: false

    companion object {

        fun fromJson(json: JSONObject): DeviceSpec {
            val streams = LinkedHashMap<String, StreamSpec>()
            json.optJSONObject("streams")?.let { obj ->
                for (key in obj.keys()) {
                    val item = obj.optJSONObject(key) ?: continue
                    streams[key] = StreamSpec(
                        supported = item.optBoolean("supported", false),
                        channels = item.optInt("channels", 0),
                    )
                }
            }

            val imuSubChannels = LinkedHashMap<String, Int>()
            json.optJSONObject("imuSubChannels")?.let { obj ->
                for (key in obj.keys()) imuSubChannels[key] = obj.optInt(key, 0)
            }

            val filters = LinkedHashMap<String, Boolean>()
            json.optJSONObject("filters")?.let { obj ->
                for (key in obj.keys()) filters[key] = obj.optBoolean(key, false)
            }

            val sampleRates = LinkedHashMap<String, SampleRateSpec>()
            json.optJSONObject("sampleRates")?.let { obj ->
                for (key in obj.keys()) {
                    val item = obj.optJSONObject(key) ?: continue
                    val rates = ArrayList<String>()
                    item.optJSONArray("rates")?.let { arr ->
                        for (i in 0 until arr.length()) rates += arr.optString(i)
                    }
                    val subStreams = ArrayList<String>()
                    item.optJSONArray("subStreams")?.let { arr ->
                        for (i in 0 until arr.length()) subStreams += arr.optString(i)
                    }
                    sampleRates[key] = SampleRateSpec(
                        rates = rates,
                        default = item.optString("default", rates.firstOrNull().orEmpty()),
                        stream = item.optString("stream", ""),
                        settable = item.optBoolean("settable", true),
                        coStream = if (item.has("coStream") && !item.isNull("coStream")) item.optString("coStream") else null,
                        subStreams = subStreams,
                    )
                }
            }

            return DeviceSpec(
                model = json.optString("model"),
                type = json.optString("type"),
                namePrefix = json.optString("namePrefix"),
                streams = streams,
                imuSubChannels = imuSubChannels,
                filters = filters,
                sampleRates = sampleRates,
            )
        }
    }
}
