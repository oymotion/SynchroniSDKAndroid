package com.oymotion.sensorcapiktdemo.automation

import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import com.oymotion.sensorcapiktdemo.cerelaxpro.CerelaxSpec
import java.util.concurrent.CopyOnWriteArrayList

private const val CHAPTER = "10_底层接口与边界"

/** 确保 profile 处于未连接态（避免复用 profile 时上一用例残留连接影响「未连接」断言）。 */
private suspend fun TestContext.ensureDisconnected(p: SensorProfile) {
    if (p.getDeviceState() == DeviceState.DISCONNECTED) return
    runCatching { p.disconnect() }
    waitFor(
        { p.getDeviceState() == DeviceState.DISCONNECTED },
        TestConfig.READY_TIMEOUT_MS,
        "回到 DISCONNECTED",
    )
}

val miscCases = listOf(
    // =====================================================================================
    // SensorAlgo 底层接口与边界
    // =====================================================================================
    auto("MISC-BND-001", "SensorAlgo 未 init 直接推送/取结果被拒绝", CHAPTER, Priority.P0, 60_000) {
        val algo = SensorAlgo()
        try {
            check(!algo.hasInit(), "构造后 hasInit 应为 false")
            val push = algo.safePush(arrayOf(), 5_000)
            log("未 init pushSamples(arrayOf()) -> $push")
            check(!push, "未 init 时 pushSamples 应返回 false")
            val result = algo.safeResult("EEG", 5_000)
            check(algoError(result), "未 init 时 getResult 应返回错误，实际=$result")
            "push=false result='${result.trim().take(60)}'"
        } finally {
            runCatching { algo.destroy() }
        }
    },
    auto("MISC-BND-002", "SensorAlgo 空数组与 null 元素被安全处理", CHAPTER, Priority.P1, 60_000) {
        val algo = SensorAlgo()
        check(algo.safeInit(), "SensorAlgo.init 返回 false")
        try {
            // 无数据时 getResult 会返回初始占位行（STEP=0 / TIMESTAMP=-1 / VALID=0），
            // 这是正常空态而非脏数据（见 DATA-ROB-003），因此以「推送前后 STEP 不变」为判据。
            val beforeResult = algo.safeResult("EEG")
            check(!algoError(beforeResult), "初始 getResult(EEG) 不应报错: $beforeResult")
            val before = algoRows(beforeResult).lastOrNull()?.firstOrNull()?.toIntOrNull()

            val empty = algo.safePush(arrayOf(), 5_000)
            log("pushSamples(arrayOf()) -> $empty")
            check(!empty, "空数组推送应被拒绝/忽略（返回 false），实际=$empty")

            @Suppress("UNCHECKED_CAST")
            val withNull = arrayOfNulls<SensorData>(2) as Array<SensorData>
            val nullPush = algo.safePush(withNull, 5_000)
            log("pushSamples(含 null 元素) -> $nullPush")
            check(!nullPush, "含 null 元素的推送应被拒绝/忽略（返回 false），实际=$nullPush")

            val result = algo.safeResult("EEG", 5_000)
            log("空/null 推送后 getResult(EEG) -> ${result.trim().take(80)}")
            check(!algoError(result), "空/null 推送后 getResult 不应报错: $result")
            val after = algoRows(result).lastOrNull()?.firstOrNull()?.toIntOrNull()
            check(
                after == before,
                "空/null 推送不应推进算法结果（before STEP=$before after STEP=$after）: $result",
            )
            "before=$before after=$after"
        } finally {
            runCatching { algo.destroy() }
        }
    },
    auto("MISC-BND-003", "SensorAlgo 通道数不匹配的批次被拒绝或忽略", CHAPTER, Priority.P0,
        180_000, requires = setOf("NTF_EEG")) {
        val spec = TestConfig.targetSpec()
        val eegChannels = CerelaxSpec.expectedChannels(spec, "NTF_EEG") ?: 0
        val p = connected()
        val listed = runCatching { CerelaxSpec.parseSwitchList(p.getParam("NTF")).map { it.first } }
            .getOrDefault(emptyList())
            .toSet()
        // 选一个支持、有独立开关、且通道数与 EEG 不同的流，构造“通道数不匹配”的批次
        val altKey = listOf("NTF_IMU", "NTF_GFORCE_ACC", "NTF_GFORCE_GYRO", "NTF_SPO2", "NTF_IMPEDANCE")
            .firstOrNull { key ->
                key in spec.supportedStreams && key in listed &&
                    CerelaxSpec.expectedChannels(spec, key)?.let { it != eegChannels } == true
            } ?: skip("没有可独立开关且通道数与 EEG($eegChannels) 不同的流，无法构造不匹配批次")

        val alt = collectAlgoBatches(altKey, 100, 1_000, profile = p)
        val batch = alt.first()
        val actualChannels = batch.channelCount
        val batchType = DataType.nameOf(batch.dataType)
        check(
            actualChannels > 0 && actualChannels != eegChannels,
            "构造的批次通道数($actualChannels)应与 EEG($eegChannels) 不同",
        )
        val algo = SensorAlgo()
        check(algo.safeInit(), "SensorAlgo.init 返回 false")
        try {
            algo.safeSetParam("EEG_SAMPLE_RATE", "1000")
            // 基线必须可解析：getResult 报错/无数据行时不能把 null == null 当成通过。
            val beforeResult = algo.safeResult("EEG")
            check(!algoError(beforeResult), "基线 getResult(EEG) 报错: $beforeResult")
            val before = algoRows(beforeResult).lastOrNull()?.firstOrNull()?.toIntOrNull()
            check(before != null, "基线 getResult(EEG) 无数据行，无法判定是否产生新结果: $beforeResult")

            val pushed = algo.safePush(alt.toTypedArray())
            log("pushSamples($altKey -> $batchType, ${actualChannels}ch) -> $pushed")
            val afterResult = algo.safeResult("EEG")
            val after = algoRows(afterResult).lastOrNull()?.firstOrNull()?.toIntOrNull()
            check(
                !pushed || after == before,
                "通道数不匹配的批次不应被接受并产出新 EEG 结果（pushed=$pushed before=$before after=$after）",
            )
            "pushed=$pushed alt=$batchType(${actualChannels}ch) baseline=$before after=$after"
        } finally {
            runCatching { algo.destroy() }
        }
    },

    // =====================================================================================
    // 负向/边界（与 Instrumentation CerelaxBoundaryTest 原骨架逐条对应）
    // =====================================================================================
    auto("MISC-BND-004", "getSensor 空/非法/未创建 MAC 返回 null", CHAPTER, Priority.P1) {
        check(controller.getSensor("") == null, "空 MAC 应返回 null")
        check(controller.getSensor("INVALID_MAC") == null, "格式非法 MAC 应返回 null")
        // 00:00:00:00:00:00 格式合法，09 章 ASYNC-FUNC-002 会 requireSensor 创建它，
        // 全量跑时它属于"已创建"，因此这里改用从未被 require 过的合法 MAC。
        check(
            controller.getSensor("FF:FF:FF:FF:FF:FE") == null,
            "未创建过的合法 MAC 应返回 null（README：getSensor 未创建时返回 null）",
        )
        "null as expected"
    },

    auto("MISC-BND-005", "未连接时 getDeviceInfo 不崩溃（null 或缓存值）", CHAPTER, Priority.P1, 120_000) {
        val p = requireTarget()
        ensureDisconnected(p)
        // README 只约定 READY + init 后 DeviceInfo 有效，未承诺断开后清空；
        // SDK 可能返回 null，也可能返回上次缓存的 DeviceInfo，两者都接受。
        val info = p.getDeviceInfo()
        log("断开后 getDeviceInfo -> ${info?.modelName ?: "null"}")
        if (info != null) {
            check(info.modelName.orEmpty().isNotBlank(), "返回缓存时应是有效 DeviceInfo")
        }
        "info=${info?.modelName ?: "null"}"
    },

    // 未连接 init 属状态机保护，失败即视为真 bug，优先级 P0。
    auto("MISC-BND-006", "未连接时 init 返回 false", CHAPTER, Priority.P0, 120_000) {
        val p = requireTarget()
        ensureDisconnected(p)
        check(
            !p.init(
                TestConfig.PACKAGE_SAMPLE_COUNT,
                TestConfig.INIT_TIMEOUT_MS,
                TestConfig.POWER_REFRESH_MS,
            ),
            "未连接时 init 应返回 false",
        )
        "init rejected"
    },

    // =====================================================================================
    // MISC-FUNC-001..009（对应 Instrumentation CerelaxBoundaryTest 的 @Test 方法）
    // =====================================================================================

    // MISC-FUNC-001：hasDeviceFoundCallback 查询 —— Android 无此接口（仅 setOnScanResultListener）。
    manual(
        "MISC-FUNC-001", "hasDeviceFoundCallback 查询（Android 无此接口）", CHAPTER, Priority.P2,
        steps = listOf(
            "Android 不适用：SensorController 无 hasDeviceFoundCallback 等价接口，仅提供 setOnScanResultListener",
        ),
    ),

    // MISC-FUNC-002：Sample.reset 清空字段 —— Android Sample 不可变、无 reset()。
    manual(
        "MISC-FUNC-002", "Sample.reset 清空字段（Android 无 reset）", CHAPTER, Priority.P1,
        steps = listOf(
            "Android 不适用：SensorData.Sample 为不可变 final 类，无 reset() 对象复用接口",
        ),
    ),

    // MISC-FUNC-003：SensorData.clear/reset 复用 —— Android SensorData 不可变、无此接口。
    manual(
        "MISC-FUNC-003", "SensorData.clear/reset 复用（Android 无此接口）", CHAPTER, Priority.P1,
        steps = listOf(
            "Android 不适用：SensorData 为不可变对象，无 clear()/reset() 复用接口",
        ),
    ),

    // MISC-FUNC-004：flatbuffers 序列化往返 —— Android 落盘/解析走 replayBinFile/parseBinToCsv。
    manual(
        "MISC-FUNC-004", "SensorData flatbuffers 序列化往返（Android 无此接口）", CHAPTER, Priority.P1,
        steps = listOf(
            "Android 不适用：SensorData 无 to_flatbuffers/from_flatbuffers；" +
                "bin 落盘/解析改由 SensorController.replayBinFile / parseBinToCsv 承担",
        ),
    ),

    // MISC-FUNC-005：池化反序列化 —— Android 无 from_flatbuffers_pooled。
    manual(
        "MISC-FUNC-005", "SensorData from_flatbuffers_pooled 池化（Android 无此接口）", CHAPTER, Priority.P1,
        steps = listOf(
            "Android 不适用：无池化反序列化接口 from_flatbuffers_pooled",
        ),
    ),

    // MISC-FUNC-006：连续多批 SensorData 的 startSampleIndex 存在且为非负整数。
    auto(
        "MISC-FUNC-006", "SensorData.startSampleIndex 存在且非负", CHAPTER, Priority.P1,
        180_000, requires = setOf("NTF_EEG"),
    ) {
        val p = connected()
        val indices = CopyOnWriteArrayList<Int>()
        p.setOnDataListener { _, list ->
            for (d in list) {
                if (d.sampleCount > 0) indices.add(d.startSampleIndex)
            }
        }

        check(p.startDataNotification(), "startDataNotification 返回 false")
        try {
            waitFor({ indices.size >= 3 }, TestConfig.DATA_TIMEOUT_MS, ">=3 批数据")
        } finally {
            runCatching { p.stopDataNotification() }
        }

        check(indices.size >= 3, "应收到 >=3 批数据，实际=${indices.size}")
        indices.forEachIndexed { i, v ->
            check(v >= 0, "startSampleIndex 应 >= 0（批[$i]=$v）")
        }
        "batches=${indices.size}"
    },

    // MISC-FUNC-007：DeviceState.INVALID 常量存在；未连接 init 不生效；状态机可恢复。
    // 注意：INVALID 的真实触发路径属手工 semi-auto 用例（可能不可达），本自动用例只做
    // 常量/状态机冒烟，不宣称验证「进入 INVALID」。
    auto(
        "MISC-FUNC-007", "DeviceState.INVALID 常量存在 + 未连接 init 被拒 + 状态机可恢复（不含 INVALID 触发）",
        CHAPTER, Priority.P2, 180_000,
    ) {
        // DeviceState / DataType 是 Java int 常量类（非 Kotlin enum），用 nameOf 做名称解析冒烟。
        check(DeviceState.nameOf(DeviceState.INVALID).isNotBlank(), "nameOf(INVALID) 不应为空")
        check(DataType.NTF_EEG != DataType.NTF_PPG, "DataType int 常量应互不相同")
        check(DataType.nameOf(DataType.NTF_EEG).isNotBlank(), "DataType.nameOf(NTF_EEG) 不应为空")

        val p = requireTarget()
        ensureDisconnected(p)

        // 未连接时 init：不应返回 true，且 SDK 不崩溃（返回 false / 抛异常均可接受）。
        val ret: Boolean? = try {
            p.init(
                TestConfig.PACKAGE_SAMPLE_COUNT,
                TestConfig.INIT_TIMEOUT_MS,
                TestConfig.POWER_REFRESH_MS,
            )
        } catch (e: Exception) {
            log("未连接 init 抛异常 ${e.javaClass.simpleName}")
            null
        }
        check(ret != true, "未连接时 init 不应返回 true（实际=$ret）")

        // 正常连接 → Ready → 断开 → Disconnected（验证状态机可恢复）。
        check(p.connect(), "connect 失败")
        waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "设备 Ready")
        check(
            p.getDeviceState() == DeviceState.READY,
            "Ready 后 deviceState 应为 READY，实际=${DeviceState.nameOf(p.getDeviceState())}",
        )
        p.disconnect()
        waitFor(
            { p.getDeviceState() == DeviceState.DISCONNECTED },
            TestConfig.READY_TIMEOUT_MS,
            "回到 DISCONNECTED",
        )
        "state machine recovered"
    },

    // MISC-FUNC-008：DeviceInfo 能力字段完整，且各流通道数与 spec（单一事实来源）一致。
    auto(
        "MISC-FUNC-008", "DeviceInfo 能力字段完整且与 spec 一致", CHAPTER, Priority.P1,
        180_000,
    ) {
        val info = connected().getDeviceInfo()
            ?: throw AssertionError("getDeviceInfo() 不应返回 null")

        check(info.modelName.isNotBlank(), "modelName 应为非空字符串，实际='${info.modelName}'")

        // AAR 暴露的全部能力字段均为 public 字段（非 getter），逐项断言合法。
        val channelCounts = listOf(
            "emg" to info.emgChannelCount, "eeg" to info.eegChannelCount, "ecg" to info.ecgChannelCount,
            "brth" to info.brthChannelCount, "acc" to info.accChannelCount, "gyro" to info.gyroChannelCount,
            "mag" to info.magChannelCount, "angle" to info.angleChannelCount, "imu" to info.imuChannelCount,
            "euler" to info.eulerChannelCount, "quat" to info.quatChannelCount, "gest" to info.gestChannelCount,
            "ppg" to info.ppgChannelCount, "spo2" to info.spo2ChannelCount, "impe" to info.impeChannelCount,
        )
        for ((name, v) in channelCounts) {
            check(v >= 0, "$name ChannelCount 应 >= 0，实际=$v")
        }

        val sampleRates = listOf(
            "emg" to info.emgSampleRate, "eeg" to info.eegSampleRate, "ecg" to info.ecgSampleRate,
            "brth" to info.brthSampleRate, "acc" to info.accSampleRate, "gyro" to info.gyroSampleRate,
            "mag" to info.magSampleRate, "angle" to info.angleSampleRate, "imu" to info.imuSampleRate,
            "euler" to info.eulerSampleRate, "quat" to info.quatSampleRate, "gest" to info.gestSampleRate,
            "ppg" to info.ppgSampleRate, "spo2" to info.spo2SampleRate, "impe" to info.impeSampleRate,
        )
        for ((name, v) in sampleRates) {
            check(v >= 0f, "$name SampleRate 应 >= 0，实际=$v")
        }

        // 型号差异只来自 spec：支持流通道数必须一致，不支持流必须为 0（不写死具体型号数值）。
        val spec = TestConfig.targetSpec()
        check(spec.streams.isNotEmpty(), "目标型号未注册任何流规格")
        for ((key, expected) in spec.streams) {
            val actual = CerelaxSpec.channelCount(info, key)
            if (expected.supported && expected.channels > 0) {
                check(
                    actual == expected.channels,
                    "$key 通道数应与 spec 一致（期望=${expected.channels} 实际=$actual）",
                )
            } else {
                check(actual == 0, "$key 在 spec 中不支持，通道数应为 0（实际=$actual）")
            }
        }
        "model=${info.modelName} streams=${spec.streams.size}"
    },

    // MISC-FUNC-009：submit 后台线程执行器 —— Android 无 submit，不适用。
    manual(
        "MISC-FUNC-009", "submit 后台线程执行器（Android 无此接口）", CHAPTER, Priority.P2,
        steps = listOf(
            "Android 不适用：无 submit 线程调度 API，后台阻塞式调用改用 Kotlin suspend 扩展",
        ),
    ),

    // MISC-FUNC-010：submit 执行阻塞式连接/断开 —— Android 无 submit，不适用。
    manual(
        "MISC-FUNC-010", "submit 执行阻塞式连接/断开（Android 无此接口）", CHAPTER, Priority.P2,
        steps = listOf(
            "Android 不适用：阻塞式 connect/disconnect 由 suspend 扩展（返回布尔）替代 submit",
        ),
    ),

    // MISC-FUNC-012：onSuspend（App 退后台通知）重复调用不崩溃、不抛异常。auto（无需设备）。
    auto("MISC-FUNC-012", "onSuspend 重复调用不抛异常", CHAPTER, Priority.P1) {
        try {
            controller.onSuspend()
            controller.onSuspend()
        } catch (e: Exception) {
            throw AssertionError("onSuspend 不应抛异常: ${e.javaClass.simpleName}: ${e.message}")
        }
        "onSuspend x2 OK"
    },

    // MISC-FUNC-011：SensorAlgo 算法模块可加载、版本号合法。
    auto("MISC-FUNC-011", "SensorAlgo 构造实例与算法版本", CHAPTER, Priority.P1, 60_000) {
        val version = SensorAlgo.getAlgoCapiVersion()
        log("SensorAlgo.getAlgoCapiVersion() = $version")
        check(version > 0, "算法版本号应为正整数，实际=$version")
        // 构造不抛异常即说明 AAR 内算法动态库可加载
        val algo = SensorAlgo()
        runCatching { algo.destroy() }
        "algoVersion=$version"
    },
)
