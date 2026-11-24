package com.oymotion.sensorcapiktdemo.automation

import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import com.oymotion.sensorcapiktdemo.cerelaxpro.CerelaxSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

private const val CHAPTER = "03_数据流与 SensorData"

/** init 参数边界用例的超时（与 Instrumentation 侧 @Test(timeout=300000) 对齐）。 */
private const val BOUNDARY_TIMEOUT_MS = 300_000L

private suspend fun initProfile(p: SensorProfile): Boolean =
    p.init(
        TestConfig.PACKAGE_SAMPLE_COUNT,
        TestConfig.INIT_TIMEOUT_MS,
        TestConfig.POWER_REFRESH_MS,
    )

/** 断开再重连，保证 init 每次状态干净。 */
private suspend fun TestContext.reconnectProfile(p: SensorProfile) {
    runCatching { p.disconnect() }
    check(p.connect(), "重连 connect 应成功")
    waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "设备 Ready")
}

/** 监听指定 dataType 的首个数据批，clone 后写入 slot（仅首次生效）。 */
private fun SensorProfile.captureFirst(type: Int, slot: AtomicReference<SensorData?>) {
    setOnDataListener { _, list ->
        val d = list.firstOrNull { it.dataType == type }
        if (d != null) slot.compareAndSet(null, d.clone())
    }
}

/**
 * getParam("NTF") 返回形如 `NTF_EEG|ON|NTF_IMU|ON|...`，解析为「开关 key -> 状态」。
 *
 * 这是判断某流是否有独立 setParam 开关的权威依据：DeviceInfo 的 ChannelCount>0 只说明
 * 有数据通道，不代表能单独起停（聚合子流 / 共流无法独立开关）。解析失败返回空表，
 * 调用方回退为「spec 支持流全部尝试」的旧行为。
 */
private suspend fun ntfSwitchStates(p: SensorProfile): Map<String, String> {
    val raw = try {
        p.getParam("NTF")
    } catch (_: Exception) {
        ""
    }
    if (raw.isBlank() || raw.startsWith("Error")) return emptyMap()
    val tokens = raw.split('|')
    val states = linkedMapOf<String, String>()
    var i = 0
    while (i + 1 < tokens.size) {
        states[tokens[i]] = tokens[i + 1]
        i += 2
    }
    return states
}

/** 把流开关恢复到用例开始前的状态，避免 OFF 残留污染后续用例。 */
private suspend fun restoreSwitches(p: SensorProfile, before: Map<String, String>) {
    for ((key, value) in before) {
        runCatching { p.setParam(key, value) }
    }
}

/** EEG 设备用例的前置：显式开启 NTF_EEG，不依赖默认流状态。 */
private suspend fun ensureEegOn(p: SensorProfile) {
    val ret = try {
        p.setParam("NTF_EEG", "ON")
    } catch (e: Exception) {
        "Error: ${e.message}"
    }
    if (ret.startsWith("Error")) throw AssertionError("setParam(NTF_EEG, ON) 应成功，实际=$ret")
}

/** 起流前尽力开启一个能产出数据的流（优先 EEG，否则 spec 首个支持流）。 */
private suspend fun enablePrimaryStream(p: SensorProfile) {
    val streams = TestConfig.targetSpec().supportedStreams
    val key = streams.firstOrNull { it == "NTF_EEG" } ?: streams.firstOrNull() ?: return
    runCatching { p.setParam(CerelaxSpec.paramKey(key), "ON") }
}

/**
 * PPG 算法主路径：采集 → 推送 → 取结果 → 校验心率/血氧范围。
 * 供 DATA-FUNC-018（自动）与 DATA-FUNC-023（半自动验证）复用。
 */
private suspend fun TestContext.runPpgAlgorithm(minDurationMs: Long): String {
    val samples = collectAlgoBatches("NTF_PPG", 200, minDurationMs)
    val algo = SensorAlgo()
    check(algo.safeInit(), "SensorAlgo.init 返回 false")
    try {
        check(algo.safePush(samples.toTypedArray()), "pushSamples(PPG) 返回 false")
        val result = algo.safeResult("PPG")
        check(!algoError(result), "getResult(PPG) 报错: $result")
        val rows = algoRows(result)
        check(rows.isNotEmpty(), "getResult(PPG) 未返回数据行: $result")
        val header = algoHeader(result)
        val row = rows.last()

        // 优先按表头字段名定位 HR/SpO2；无表头时退化为「在合理区间内找数值」
        fun pick(markers: List<String>, range: ClosedFloatingPointRange<Float>, fallbackIndex: Int): Float? {
            val idx = header?.indexOfFirst { h -> markers.any { marker -> h.contains(marker, true) } } ?: -1
            if (idx >= 0) return row.getOrNull(idx)?.toFloatOrNull()
            row.getOrNull(fallbackIndex)?.toFloatOrNull()?.takeIf { it in range }?.let { return it }
            return row.mapNotNull { it.toFloatOrNull() }.firstOrNull { it in range }
        }

        val hr = pick(listOf("HR", "HEART", "BPM"), 30f..220f, 1)
        val spo2 = pick(listOf("SPO2", "SPO_2", "OX"), 70f..100f, 2)
        check(hr != null && hr in 30f..220f, "心率应在 30~220 bpm，实际=$hr（row=$row）")
        check(spo2 != null && spo2 in 70f..100f, "SpO2 应在 70~100%，实际=$spo2（row=$row）")
        return "hr=$hr spo2=$spo2 fields=${row.size}"
    } finally {
        runCatching { algo.destroy() }
    }
}

/**
 * 起流 [seconds] 秒，用单通道 `sampleIndex` 跨度换算指定流的实测采样率。
 *
 * 多流并存时各流 sampleIndex 是独立编号空间：这里先锁定首个到达的同类 DataType，
 * 只统计该类型，绝不跨流混算。
 */
private suspend fun TestContext.measureStreamDataRate(streamKey: String, seconds: Long): Double {
    val p = connected()
    val paramKey = CerelaxSpec.paramKey(streamKey)
    val onRet = try {
        p.setParam(paramKey, "ON")
    } catch (e: Exception) {
        "Error: ${e.message}"
    }
    check(!algoError(onRet), "$paramKey=ON 被拒绝（$onRet）")

    val types = CerelaxSpec.dataTypesForKey(streamKey).toSet()
    val targetType = AtomicInteger(-1)
    val startIdx = AtomicLong(-1)
    val endIdx = AtomicLong(-1)
    val got = AtomicBoolean(false)
    p.setOnDataListener { _, list ->
        for (d in list) {
            if (d.dataType !in types) continue
            if (targetType.get() < 0) targetType.compareAndSet(-1, d.dataType)
            if (d.dataType != targetType.get()) continue
            val s = d.startSampleIndex.toLong()
            if (startIdx.get() < 0) startIdx.set(s)
            endIdx.set(s + d.sampleCount - 1)
            got.set(true)
        }
    }
    check(p.startDataNotification(), "startDataNotification 失败")
    try {
        waitFor({ got.get() }, TestConfig.DATA_TIMEOUT_MS, "$streamKey 首批数据")
        delay(seconds * 1000)
    } finally {
        runCatching { p.stopDataNotification() }
    }
    val span = endIdx.get() - startIdx.get() + 1
    check(span > 0, "$streamKey 样本跨度应为正（span=$span）")
    return span.toDouble() / seconds
}

/** 采集一批主数据流数据，供单点访问器越界测试。 */
private suspend fun TestContext.capturePrimaryBatch(): SensorData {
    val p = connected()
    enablePrimaryStream(p)
    val queue = ConcurrentLinkedQueue<SensorData>()
    p.setOnDataListener { _, list -> list.firstOrNull()?.let { queue.offer(it.clone()) } }
    check(p.startDataNotification(), "startDataNotification 失败")
    try {
        waitFor({ queue.isNotEmpty() }, TestConfig.DATA_TIMEOUT_MS, "首批数据")
    } finally {
        runCatching { p.stopDataNotification() }
    }
    return queue.firstOrNull() ?: throw AssertionError("未采集到数据")
}

val dataflowCases = listOf(
    // ---- DATA-BND：init 参数边界 ------------------------------------------------------
    auto("DATA-BND-001", "init(packageSampleCount) 6 点边界", CHAPTER, Priority.P1,
        BOUNDARY_TIMEOUT_MS) {
        val p = requireTarget()
        val max = Int.MAX_VALUE.toLong()
        val values = listOf(1L, 2L, max - 1, max, 0L, max + 1)
        val mustSucceed = setOf(1L, 2L)       // 正常范围内的合法值必须 init 成功
        val tolerated = setOf(max - 1, max)   // 极端值可能超出 SDK 实际上限，只要求不崩溃

        for (v in values) {
            val pkg = v.toInt() // MAX+1 溢出为 Int.MIN_VALUE（模拟越界入参）
            reconnectProfile(p)
            val ret: Boolean? = try {
                p.init(pkg, TestConfig.INIT_TIMEOUT_MS, TestConfig.POWER_REFRESH_MS)
            } catch (e: Exception) {
                log("init($v) 抛异常 ${e.javaClass.simpleName}")
                null
            }
            when {
                v in mustSucceed -> check(ret == true, "合法值 $v 的 init 应成功，实际=$ret")
                v in tolerated -> {
                    check(ret != null, "极端合法值 $v 不应抛异常")
                    if (ret == false) log("init($v) 返回 false（超出实际处理上限，仅验证不崩溃）")
                }
                else -> check(ret != true, "非法值 $v 应被拒绝（返回 false 或抛异常），实际=$ret")
            }
        }
        runCatching { p.disconnect() }
        "6 点边界 OK"
    },
    auto("DATA-BND-002", "init(_, powerRefreshInterval) 6 点边界", CHAPTER, Priority.P1,
        BOUNDARY_TIMEOUT_MS) {
        val p = requireTarget()
        val max = Int.MAX_VALUE.toLong()
        val values = listOf(0L, 1L, max - 1, max, -1L, max + 1)
        val mustSucceed = setOf(0L, 1L)       // 正常范围内的合法值必须 init 成功
        val tolerated = setOf(max - 1, max)   // 极端值可能超出 SDK 实际上限，只要求不崩溃

        for (v in values) {
            val power = v.toInt()
            reconnectProfile(p)
            val ret: Boolean? = try {
                p.init(TestConfig.PACKAGE_SAMPLE_COUNT, TestConfig.INIT_TIMEOUT_MS, power)
            } catch (e: Exception) {
                log("init(_, $v) 抛异常 ${e.javaClass.simpleName}")
                null
            }
            when {
                v in mustSucceed -> check(ret == true, "合法值 $v 的 init 应成功，实际=$ret")
                v in tolerated -> {
                    check(ret != null, "极端合法值 $v 不应抛异常")
                    if (ret == false) log("init(_, $v) 返回 false（超出实际处理上限，仅验证不崩溃）")
                }
                else -> check(ret != true, "非法值 $v 应被拒绝（返回 false 或抛异常），实际=$ret")
            }
        }
        runCatching { p.disconnect() }
        "6 点边界 OK"
    },
    auto("DATA-BND-004", "getData/getRawData 越界返回 0 且不抛异常", CHAPTER,
        Priority.P1, 120_000) {
        val d = capturePrimaryBatch()
        val cc = d.channelCount
        val sc = d.sampleCount
        check(cc > 0 && sc > 0, "批次应有通道/样本（cc=$cc sc=$sc）")
        val probes = listOf(
            Triple("getData(channelCount,0)", cc, 0),
            Triple("getData(0,sampleCount)", 0, sc),
            Triple("getRawData(-1,0)", -1, 0),
        )
        for ((name, ch, si) in probes) {
            val data = runCatching { d.getData(ch, si) }
                .getOrElse { throw AssertionError("$name 不应抛异常: ${it.javaClass.simpleName}") }
            val raw = runCatching { d.getRawData(ch, si) }
                .getOrElse { throw AssertionError("$name(raw) 不应抛异常: ${it.javaClass.simpleName}") }
            check(data == 0f, "$name 应返回 0，实际=$data")
            check(raw == 0, "$name(raw) 应返回 0，实际=$raw")
        }
        "3 组越界均返回 0"
    },
    auto("DATA-BND-005", "getImpedance/getSaturation/isLost 越界返回安全值", CHAPTER,
        Priority.P1, 120_000) {
        val d = capturePrimaryBatch()
        val cc = d.channelCount
        val sc = d.sampleCount
        check(cc > 0 && sc > 0, "批次应有通道/样本（cc=$cc sc=$sc）")
        for ((ch, si) in listOf(cc to 0, 0 to sc, -1 to 0)) {
            val imp = runCatching { d.getImpedance(ch, si) }
                .getOrElse { throw AssertionError("getImpedance($ch,$si) 不应抛异常: ${it.javaClass.simpleName}") }
            val sat = runCatching { d.getSaturation(ch, si) }
                .getOrElse { throw AssertionError("getSaturation($ch,$si) 不应抛异常: ${it.javaClass.simpleName}") }
            val lost = runCatching { d.isLost(ch, si) }
                .getOrElse { throw AssertionError("isLost($ch,$si) 不应抛异常: ${it.javaClass.simpleName}") }
            check(imp == 0f, "越界 getImpedance($ch,$si) 应返回 0，实际=$imp")
            check(sat == 0f, "越界 getSaturation($ch,$si) 应返回 0，实际=$sat")
            check(!lost, "越界 isLost($ch,$si) 应返回 false")
        }
        "越界阻抗/饱和度=0、isLost=false"
    },
    auto("DATA-BND-007", "isDataValid/isChannelEnabled 越界返回 false", CHAPTER,
        Priority.P1, 120_000) {
        val d = capturePrimaryBatch()
        val cc = d.channelCount
        val sc = d.sampleCount
        check(cc > 0 && sc > 0, "批次应有通道/样本（cc=$cc sc=$sc）")
        for ((ch, si) in listOf(-1 to 0, cc to 0, 0 to sc)) {
            val valid = runCatching { d.isDataValid(ch, si) }
                .getOrElse { throw AssertionError("isDataValid($ch,$si) 不应抛异常: ${it.javaClass.simpleName}") }
            check(!valid, "越界 isDataValid($ch,$si) 应返回 false")
        }
        for (ch in listOf(-1, cc)) {
            val enabled = runCatching { d.isChannelEnabled(ch) }
                .getOrElse { throw AssertionError("isChannelEnabled($ch) 不应抛异常: ${it.javaClass.simpleName}") }
            check(!enabled, "越界 isChannelEnabled($ch) 应返回 false")
        }
        "越界有效性探测均返回 false"
    },

    // ---- DATA-FUNC：起流/停流/数据访问 ------------------------------------------------
    auto("DATA-FUNC-001", "init 成功 hasInited=true", CHAPTER, Priority.P0) {
        val p = requireTarget()
        // requireTarget 只扫描不连接；未 Ready 时 init 必然返回 false（与 FUNC-002 的预期一致）
        reconnectProfile(p)
        check(!p.hasInited(), "init 前 hasInited 应为 false")
        val initRet = initProfile(p)
        check(
            initRet,
            "init 应成功，实际=false（state=${DeviceState.nameOf(p.getDeviceState())} " +
                "ready=${p.isReady()} hasInited=${p.hasInited()}）",
        )
        check(p.hasInited(), "init 后 hasInited 应为 true（init 返回 $initRet）")
        p.disconnect()
        "hasInited=true"
    },
    auto("DATA-FUNC-002", "未 Ready 前 init/startDataNotification 失败", CHAPTER, Priority.P0,
        60_000) {
        val p = requireTarget()
        if (p.isReady()) {
            p.disconnect()
            waitFor({ !p.isReady() }, 10_000, "Disconnected")
        }
        check(p.getDeviceState() != DeviceState.READY, "前置：未连接 deviceState 应非 READY")
        check(!initProfile(p), "未 Ready init 应返回 false")
        check(!p.startDataNotification(), "未 Ready startDataNotification 应返回 false")
        "init/start rejected"
    },
    auto("DATA-FUNC-003", "起流后 isDataTransfering=true 且回调按批触发", CHAPTER, Priority.P0) {
        val p = connected()
        enablePrimaryStream(p)
        val batches = AtomicInteger()
        val slot = AtomicReference<SensorData?>(null)
        p.setOnDataListener { _, list ->
            if (list.isNotEmpty()) {
                batches.incrementAndGet()
                slot.compareAndSet(null, list.first().clone())
            }
        }
        check(p.startDataNotification(), "startDataNotification 失败")
        check(p.isDataTransfering(), "起流后 isDataTransfering 应为 true")
        waitFor({ slot.get() != null }, TestConfig.DATA_TIMEOUT_MS, "首批数据")
        check(batches.get() > 0, "回调批次数应 > 0")
        p.stopDataNotification()
        "batches=${batches.get()}"
    },
    auto("DATA-FUNC-004", "stopDataNotification 后停止回调", CHAPTER, Priority.P0) {
        val p = connected()
        enablePrimaryStream(p)
        val batches = AtomicInteger()
        val slot = AtomicReference<SensorData?>(null)
        p.setOnDataListener { _, list ->
            if (list.isNotEmpty()) {
                batches.incrementAndGet()
                slot.compareAndSet(null, list.first().clone())
            }
        }
        check(p.startDataNotification(), "startDataNotification 失败")
        waitFor({ slot.get() != null }, TestConfig.DATA_TIMEOUT_MS, "首批数据")
        check(p.stopDataNotification(), "stopDataNotification 失败")
        check(!p.isDataTransfering(), "停流后 isDataTransfering 应为 false")

        val before = batches.get()
        delay(2_000)
        val after = batches.get()
        check(after == before, "停流后回调应停止（before=$before after=$after）")
        "batches=$before"
    },
    auto("DATA-FUNC-005", "spec 支持流逐一收到数据且通道数符合 spec", CHAPTER, Priority.P1,
        BOUNDARY_TIMEOUT_MS) {
        val spec = TestConfig.targetSpec()
        val supported = CerelaxSpec.supportedStreamKeys(spec)
        check(supported.isNotEmpty(), "spec 应注册支持流")
        val p = connected()

        // DeviceInfo 通道数>0 只说明有数据通道，不代表有独立 setParam 开关；
        // 以 getParam("NTF") 的开关列表为准（聚合子流/共流可能不在其中）。
        val before = ntfSwitchStates(p)
        log("NTF 开关状态: $before")
        val switchable = if (before.isEmpty()) supported else supported.filter { it in before }
        val noSwitch = supported - switchable.toSet()
        if (noSwitch.isNotEmpty()) log("无独立开关，跳过逐流起停验证: $noSwitch")
        check(switchable.isNotEmpty(), "getParam(NTF) 未返回任何可切换的支持流")

        val failures = mutableListOf<String>()
        try {
            for (key in switchable) {
                val expectedCh = CerelaxSpec.expectedChannels(spec, key) ?: continue
                val expectedTypes = CerelaxSpec.dataTypesForKey(key)
                if (expectedTypes.isEmpty()) continue

                // 单流隔离：关掉其它可切换流避免串扰；共流（如 Impedance 需 EEG）保持开启
                val keepOn = spec.sampleRates.values
                    .filter { it.stream == key }
                    .mapNotNull { it.coStream }
                    .toSet()
                if (before.isNotEmpty()) {
                    for (k in switchable) {
                        if (k !in keepOn) runCatching { p.setParam(k, "OFF") }
                    }
                    for (k in keepOn) runCatching { p.setParam(k, "ON") }
                }
                val onRet = try {
                    p.setParam(key, "ON")
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                log("$key setParam(ON) -> $onRet")
                if (onRet.startsWith("Error")) {
                    failures += "$key setParam(ON) 被拒绝（$onRet）"
                    continue
                }

                // 聚合流（NTF_IMU）在部分设备上拆成 ACC+GYRO 子流回传，按布局判定而非死等单一 DataType
                val got = ConcurrentHashMap<Int, Int>()
                p.setOnDataListener { _, list ->
                    for (d in list) {
                        if (d.dataType in expectedTypes) got[d.dataType] = d.channelCount
                    }
                }
                if (!p.startDataNotification()) {
                    failures += "$key startDataNotification 失败"
                    continue
                }
                try {
                    waitFor(
                        { CerelaxSpec.aggregateLayoutOk(spec, key, got) },
                        TestConfig.DATA_TIMEOUT_MS,
                        "流 $key 数据",
                    )
                } catch (e: AssertionError) {
                    failures += "${e.message ?: "流 $key 等待数据失败"}（期望通道 $expectedCh，实际=$got）"
                } finally {
                    runCatching { p.stopDataNotification() }
                }
            }
        } finally {
            // 关键：无论成功失败都恢复流开关，避免后续用例收不到任何数据
            restoreSwitches(p, before)
        }

        check(failures.isEmpty(), "逐流验证失败 ${failures.size} 项: ${failures.joinToString("；")}")
        "switchable=${switchable.size} skipped=${noSwitch.size}"
    },
    auto("DATA-FUNC-006", "SensorData 元数据合法", CHAPTER, Priority.P0,
        requires = setOf("NTF_EEG")) {
        val p = connected()
        ensureEegOn(p)
        val slot = AtomicReference<SensorData?>(null)
        p.captureFirst(DataType.NTF_EEG, slot)
        check(p.startDataNotification(), "startDataNotification 失败")
        waitFor({ slot.get() != null }, TestConfig.DATA_TIMEOUT_MS, "EEG 数据")

        val d = slot.get() ?: throw AssertionError("EEG SensorData 不应为 null")
        check(d.deviceMac.equals(p.device.mac, ignoreCase = true), "deviceMac 应为目标设备")
        check(d.channelCount > 0, "channelCount 应 > 0")
        check(d.sampleRate > 0f, "sampleRate 应 > 0")
        check(d.sampleCount > 0, "sampleCount 应 > 0")
        check(d.channelMask != 0L, "channelMask 应非 0")
        check(d.lostPackageCount >= 0, "lostPackageCount 应 >= 0")
        check(d.startSampleIndex >= 0, "startSampleIndex 应 >= 0")
        check(d.isDataValid(), "isDataValid 应为 true")

        p.stopDataNotification()
        "ch=${d.channelCount} rate=${d.sampleRate} samples=${d.sampleCount}"
    },
    auto("DATA-FUNC-007", "getChannelSample 与单点访问器一致且 sampleIndex 单调", CHAPTER,
        Priority.P1) {
        val p = connected()
        enablePrimaryStream(p)
        val got = AtomicBoolean(false)
        val consistent = AtomicBoolean(true)
        val monotonic = AtomicBoolean(true)
        // 按 dataType 分桶（不同流的 channelIndex 是彼此独立的编号空间）
        val lastByKey = ConcurrentHashMap<Long, AtomicLong>()

        p.setOnDataListener { _, list ->
            for (d in list) {
                for (ch in 0 until d.channelCount) {
                    val last = lastByKey.getOrPut(d.dataType.toLong() * 1000 + ch) { AtomicLong(-1) }
                    for (i in 0 until d.sampleCount) {
                        val s = d.getChannelSample(ch, i) ?: continue
                        if (s.sampleIndex != d.getSampleIndex(ch, i)) consistent.set(false)
                        if (s.data != d.getData(ch, i)) consistent.set(false)
                        val idx = d.getSampleIndex(ch, i).toLong()
                        if (idx <= last.get()) monotonic.set(false)
                        last.set(idx)
                    }
                }
                got.set(true)
            }
        }

        check(p.startDataNotification(), "startDataNotification 失败")
        waitFor({ got.get() }, TestConfig.DATA_TIMEOUT_MS, "首批数据")
        delay(TestConfig.COLLECT_SECONDS * 1000L)
        p.stopDataNotification()

        check(consistent.get(), "getChannelSample 与单点访问器应一致")
        check(monotonic.get(), "sampleIndex 应单调递增")
        "consistent & monotonic"
    },
    auto("DATA-FUNC-008", "isLost 与 lostPackageCount 一致", CHAPTER, Priority.P1) {
        val p = connected()
        enablePrimaryStream(p)
        val got = AtomicBoolean(false)
        val lostPoints = AtomicInteger(0)
        val lostPackages = AtomicLong(0)

        p.setOnDataListener { _, list ->
            for (d in list) {
                lostPackages.addAndGet(d.lostPackageCount.toLong())
                for (ch in 0 until d.channelCount) {
                    for (i in 0 until d.sampleCount) {
                        if (d.isLost(ch, i)) lostPoints.incrementAndGet()
                    }
                }
                got.set(true)
            }
        }

        check(p.startDataNotification(), "startDataNotification 失败")
        waitFor({ got.get() }, TestConfig.DATA_TIMEOUT_MS, "首批数据")
        delay(TestConfig.COLLECT_SECONDS * 1000L)
        p.stopDataNotification()

        val lp = lostPackages.get()
        val pts = lostPoints.get()
        check(
            (lp == 0L && pts == 0) || (lp > 0L && pts > 0),
            "isLost 标记与 lostPackageCount 应同零或同非零（lp=$lp pts=$pts）",
        )
        "lostPackageCount=$lp lostPoints=$pts"
    },
    auto("DATA-FUNC-009", "每批样本数 ≈ packageSampleCount", CHAPTER, Priority.P1) {
        val p = connected()
        enablePrimaryStream(p)
        // 多流并存时，每包样本数由各流速率/设备分包决定（Cerelax 实测 ACC/GYRO=4、EEG=20、SPO2/Imp=1），
        // 不能把所有流混在一起断言 ≈ packageSampleCount；按 dataType 分桶，要求至少一个流符合。
        val batches = ConcurrentLinkedQueue<Pair<Int, Int>>()
        val got = AtomicBoolean(false)
        p.setOnDataListener { _, list ->
            for (d in list) batches.offer(d.dataType to d.sampleCount)
            if (list.isNotEmpty()) got.set(true)
        }

        check(p.startDataNotification(), "startDataNotification 失败")
        waitFor({ got.get() }, TestConfig.DATA_TIMEOUT_MS, "首批数据")
        delay(TestConfig.COLLECT_SECONDS * 1000L)
        p.stopDataNotification()

        val grouped = batches.toList().groupBy({ it.first }, { it.second })
        check(grouped.isNotEmpty(), "应至少收到一批数据")
        val packageCount = TestConfig.PACKAGE_SAMPLE_COUNT
        val stats = grouped.mapValues { (_, sizes) ->
            sizes.count { abs(it - packageCount) <= packageCount * 0.3 } to sizes.size
        }
        val ok = stats.entries.firstOrNull { (_, s) -> s.first >= s.second * 0.7 }
        check(
            ok != null,
            "应至少有一个流的多数批≈$packageCount（实际=${stats.mapKeys { DataType.nameOf(it.key) }}）",
        )
        val detail = stats.mapKeys { DataType.nameOf(it.key) }
        "ok=${DataType.nameOf(ok!!.key)} batches=${ok.value.second} close=${ok.value.first} all=$detail"
    },
    auto("DATA-FUNC-010", "IMU 聚合流通道数与 spec 一致", CHAPTER, Priority.P1,
        requires = setOf("NTF_IMU")) {
        val spec = TestConfig.targetSpec()
        val expectedChannels = spec.channels("NTF_IMU")
        val p = connected()

        p.setParam(CerelaxSpec.paramKey("NTF_IMU"), "ON")
        // 允许两种形态：聚合 NTF_IMU(6ch)，或拆分子流 NTF_ACC(3)+NTF_GYRO(3)
        val got = ConcurrentHashMap<Int, Int>()
        p.setOnDataListener { _, list ->
            for (d in list) {
                if (d.dataType in CerelaxSpec.dataTypesForKey("NTF_IMU")) {
                    got[d.dataType] = d.channelCount
                }
            }
        }

        check(p.startDataNotification(), "startDataNotification 失败")
        waitFor(
            { CerelaxSpec.aggregateLayoutOk(spec, "NTF_IMU", got) },
            TestConfig.DATA_TIMEOUT_MS,
            "IMU 数据",
        )
        check(
            CerelaxSpec.aggregateLayoutOk(spec, "NTF_IMU", got),
            "NTF_IMU 通道布局应与 spec 一致（聚合 $expectedChannels ch 或 ACC+GYRO 子流），实际=$got",
        )
        p.stopDataNotification()
        "IMU layout=${got.mapKeys { DataType.nameOf(it.key) }}"
    },
    auto("DATA-FUNC-011", "EEG_SAMPLE_RATE 变更后实际 data rate 随之变化", CHAPTER, Priority.P0,
        300_000, requires = setOf("NTF_EEG")) {
        val spec = TestConfig.targetSpec()
        val key = "EEG_SAMPLE_RATE"
        val sr = spec.sampleRates[key]?.takeIf { it.settable }
            ?: skip("spec 未登记可设置的 $key")
        val p = connected()
        ensureEegOn(p)

        val current = safeGetParam(p, key)
        check(!algoError(current), "getParam($key) 不应报错，实际=$current")
        val original = sr.rates.firstOrNull { current.contains(it) } ?: sr.default
        val details = mutableListOf<String>()
        try {
            for (rate in sr.rates) {
                val ret = safeSetParam(p, key, rate)
                check(!algoError(ret), "setParam($key, $rate) 应成功，实际=$ret")
                val measured = measureStreamDataRate("NTF_EEG", 5)
                val ratio = measured / rate.toDouble()
                check(
                    abs(ratio - 1.0) <= TestConfig.SAMPLE_RATE_TOLERANCE,
                    "EEG 实测 data rate=${"%.1f".format(measured)}Hz 应与设置值 $rate 一致" +
                        "（偏差=${"%.1f".format((ratio - 1) * 100)}%）",
                )
                details += "$rate→${"%.1f".format(measured)}Hz"
            }
        } finally {
            runCatching { safeSetParam(p, key, original) }
        }
        details.joinToString(" ")
    },
    auto("DATA-FUNC-012", "SensorData.clone 为独立对象且内容一致", CHAPTER, Priority.P0,
        requires = setOf("NTF_EEG")) {
        val p = connected()
        ensureEegOn(p)
        val original = AtomicReference<SensorData?>(null)
        val cloned = AtomicReference<SensorData?>(null)
        p.setOnDataListener { _, list ->
            list.firstOrNull { it.dataType == DataType.NTF_EEG }?.let { d ->
                original.compareAndSet(null, d)
                cloned.compareAndSet(null, d.clone())
            }
        }

        check(p.startDataNotification(), "startDataNotification 失败")
        waitFor({ original.get() != null && cloned.get() != null },
            TestConfig.DATA_TIMEOUT_MS, "EEG 数据")

        val o = original.get() ?: throw AssertionError("原始对象不应为 null")
        val c = cloned.get() ?: throw AssertionError("克隆对象不应为 null")
        check(c !== o, "clone 应为独立对象（非同一引用）")
        check(c.getDataType() == o.getDataType(), "clone 内容应一致（dataType）")
        check(c.channelCount == o.channelCount, "clone 内容应一致（channelCount）")
        check(c.sampleCount == o.sampleCount, "clone 内容应一致（sampleCount）")
        check(c.getData(0, 0) == o.getData(0, 0), "clone 首点数据应一致")

        p.stopDataNotification()
        "clone OK"
    },
    auto("DATA-FUNC-013", "Sample 字段与单点访问器一致", CHAPTER, Priority.P1) {
        val p = connected()
        val got = AtomicBoolean(false)
        val consistent = AtomicBoolean(true)
        p.setOnDataListener { _, list ->
            for (d in list) {
                for (ch in 0 until d.channelCount) {
                    for (i in 0 until d.sampleCount) {
                        val s = d.getChannelSample(ch, i) ?: continue
                        if (s.channelIndex != ch) consistent.set(false)
                        if (s.sampleIndex != d.getSampleIndex(ch, i)) consistent.set(false)
                        if (s.data != d.getData(ch, i)) consistent.set(false)
                        if (s.rawData != d.getRawData(ch, i)) consistent.set(false)
                        if (s.impedance != d.getImpedance(ch, i)) consistent.set(false)
                        if (s.saturation != d.getSaturation(ch, i)) consistent.set(false)
                    }
                }
                got.set(true)
            }
        }

        check(p.startDataNotification(), "startDataNotification 失败")
        waitFor({ got.get() }, TestConfig.DATA_TIMEOUT_MS, "首批数据")
        delay(TestConfig.COLLECT_SECONDS * 1000L)
        p.stopDataNotification()

        check(consistent.get(), "Sample 字段应与单点访问器一致")
        "sample fields consistent"
    },

    // ---- SensorAlgo：算法主路径 --------------------------------------------------------
    auto("DATA-FUNC-017", "推送 EEG 批次后得到合法结果行", CHAPTER, Priority.P0, 120_000,
        requires = setOf("NTF_EEG")) {
        val samples = collectAlgoBatches("NTF_EEG", 4_000, 4_000)
        val algo = SensorAlgo()
        check(algo.safeInit(), "SensorAlgo.init 返回 false")
        try {
            algo.syncEegSampleRate(samples)
            check(algo.safePush(samples.toTypedArray()), "pushSamples(EEG) 返回 false")

            val result = algo.safeResult("EEG")
            check(!algoError(result), "getResult(EEG) 报错: $result")
            val rows = algoRows(result)
            check(rows.isNotEmpty(), "getResult(EEG) 未返回数据行: $result")
            val header = algoHeader(result)
            val row = rows.last()
            check(
                row.size >= 6,
                "EEG 结果行应为 6 字段 STEP|TIMESTAMP|VALID|RELAX|CONCENTRATE|POSITION，实际=${row.size}: $row",
            )
            val relax = algoField(row, header, "RELAX", 3)?.toFloatOrNull()
            val concentrate = algoField(row, header, "CONCENTRATE", 4)?.toFloatOrNull()
            check(relax != null && relax in 0f..100f, "RELAX 应在 0~100，实际=$relax（row=$row）")
            check(
                concentrate != null && concentrate in 0f..100f,
                "CONCENTRATE 应在 0~100，实际=$concentrate（row=$row）",
            )
            "relax=$relax concentrate=$concentrate fields=${row.size}"
        } finally {
            runCatching { algo.destroy() }
        }
    },
    auto("DATA-FUNC-018", "推送 PPG 批次后得到心率/血氧结果", CHAPTER, Priority.P0, 180_000,
        requires = setOf("NTF_PPG")) {
        runPpgAlgorithm(8_000)
    },
    auto("DATA-FUNC-019", "getResult(NO_HEADER) 返回无表头裸数据行", CHAPTER, Priority.P2, 120_000,
        requires = setOf("NTF_EEG")) {
        val samples = collectAlgoBatches("NTF_EEG", 4_000, 4_000)
        val algo = SensorAlgo()
        check(algo.safeInit(), "SensorAlgo.init 返回 false")
        try {
            algo.syncEegSampleRate(samples)
            // 两次取结果各推送一批新数据，避免“上一次取结果已消费数据导致本次为空”
            check(algo.safePush(samples.toTypedArray()), "pushSamples(EEG) 返回 false")
            val noHeader = algo.safeResult("NO_HEADER")
            check(algo.safePush(samples.toTypedArray()), "pushSamples(EEG) 返回 false")
            val withHeader = algo.safeResult("EEG")
            check(!algoError(withHeader), "getResult(EEG) 报错: $withHeader")
            check(!algoError(noHeader), "getResult(NO_HEADER) 报错: $noHeader")

            val expected = algoRows(withHeader).lastOrNull()
                ?: throw AssertionError("EEG 结果应含数据行: $withHeader")
            val bare = algoRows(noHeader)
            check(bare.isNotEmpty(), "NO_HEADER 应返回裸数据行: $noHeader")
            check(algoHeader(noHeader) == null, "NO_HEADER 不应含字段名表头: $noHeader")
            check(
                bare.last().size == expected.size,
                "NO_HEADER 字段数应与 EEG 数据行一致（${expected.size} vs ${bare.last().size}）",
            )
            "fields=${expected.size}"
        } finally {
            runCatching { algo.destroy() }
        }
    },
    auto("DATA-FUNC-020", "STEP 递增语义（有新数据才递增）", CHAPTER, Priority.P1, 180_000,
        requires = setOf("NTF_EEG")) {
        val samples = collectAlgoBatches("NTF_EEG", 4_000, 4_000)
        val algo = SensorAlgo()
        check(algo.safeInit(), "SensorAlgo.init 返回 false")
        try {
            algo.syncEegSampleRate(samples)
            check(algo.safePush(samples.toTypedArray()), "首次 pushSamples 返回 false")
            val first = algoStep(algo.safeResult("EEG"))
            val repeat = algoStep(algo.safeResult("EEG"))
            check(repeat == first, "无新数据时 STEP 应保持不变（first=$first repeat=$repeat）")

            check(algo.safePush(samples.toTypedArray()), "第二次 pushSamples 返回 false")
            val next = algoStep(algo.safeResult("EEG"))
            check(next > first, "有新数据时 STEP 应递增（first=$first next=$next）")
            "step $first -> $next"
        } finally {
            runCatching { algo.destroy() }
        }
    },
    auto("DATA-FUNC-021", "reset 后重新产出结果", CHAPTER, Priority.P1, 180_000,
        requires = setOf("NTF_EEG")) {
        val samples = collectAlgoBatches("NTF_EEG", 4_000, 4_000)
        val algo = SensorAlgo()
        check(algo.safeInit(), "SensorAlgo.init 返回 false")
        try {
            algo.syncEegSampleRate(samples)
            check(algo.safePush(samples.toTypedArray()), "首次 pushSamples 返回 false")
            val before = algoStep(algo.safeResult("EEG"))

            algo.reset()
            val afterReset = algo.safeResult("EEG")
            log("reset 后未推送数据时 getResult(EEG)=${afterReset.trim().take(80)}")

            check(algo.safePush(samples.toTypedArray()), "reset 后 pushSamples 返回 false")
            val result = algo.safeResult("EEG")
            check(!algoError(result), "reset 后 getResult(EEG) 报错: $result")
            val after = algoStep(result)
            check(after <= before, "reset 后 STEP 应从初始值重新开始（before=$before after=$after）")
            "before=$before after=$after"
        } finally {
            runCatching { algo.destroy() }
        }
    },
    auto("DATA-FUNC-022", "真实 EEG 流持续输出放松度（60s）", CHAPTER, Priority.P0, 180_000,
        requires = setOf("NTF_EEG")) {
        val p = connected()
        val onRet = try {
            p.setParam("NTF_EEG", "ON")
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
        check(!algoError(onRet), "NTF_EEG=ON 被拒绝（$onRet）")

        val algo = SensorAlgo()
        check(algo.safeInit(), "SensorAlgo.init 返回 false")
        try {
            val queue = ConcurrentLinkedQueue<SensorData>()
            p.setOnDataListener { _, list ->
                list.filter { it.dataType == DataType.NTF_EEG }.forEach { queue.offer(it.clone()) }
            }
            check(p.startDataNotification(), "startDataNotification 失败")

            val steps = LinkedHashSet<Int>()
            val relaxValues = ArrayList<Float>()
            var pushedBatches = 0
            var rejectedBatches = 0
            var rateSet = false
            val startedAt = System.currentTimeMillis()
            try {
                while (System.currentTimeMillis() - startedAt < 60_000) {
                    val batch = ArrayList<SensorData>()
                    while (true) {
                        batch += queue.poll() ?: break
                    }
                    if (batch.isNotEmpty()) {
                        if (!rateSet) {
                            algo.syncEegSampleRate(batch)
                            rateSet = true
                        }
                        if (algo.safePush(batch.toTypedArray())) pushedBatches++ else rejectedBatches++
                    }
                    val result = algo.safeResult("EEG")
                    if (!algoError(result)) {
                        val rows = algoRows(result)
                        rows.forEach { row -> row[0].toIntOrNull()?.let { steps.add(it) } }
                        algoField(rows.lastOrNull().orEmpty(), algoHeader(result), "RELAX", 3)
                            ?.toFloatOrNull()?.let { relaxValues.add(it) }
                    }
                    delay(200)
                }
            } finally {
                runCatching { p.stopDataNotification() }
            }

            check(pushedBatches > 0, "60s 内未成功推送 EEG 批次（拒绝 $rejectedBatches 批）")
            check(steps.isNotEmpty(), "60s 内算法未产出任何结果行")
            check(relaxValues.isNotEmpty(), "算法结果未包含 RELAX 字段")
            val outOfRange = relaxValues.filter { it !in 0f..100f }
            check(outOfRange.isEmpty(), "RELAX 应始终在 0~100，异常值=${outOfRange.take(5)}")
            "steps=${steps.size} relax=${relaxValues.minOrNull()}~${relaxValues.maxOrNull()} " +
                "pushed=$pushedBatches rejected=$rejectedBatches"
        } finally {
            runCatching { algo.destroy() }
        }
    },
    semi("DATA-FUNC-023", "真实 PPG 流输出心率/血氧（需静止佩戴）", CHAPTER, Priority.P1,
        steps = listOf(
            "将待测设备佩戴/贴合到指定部位（指端或额部），保持静止，避免大幅运动。",
            "如条件允许，用参考血氧仪/心率设备同时记录读数，便于人工比对偏差。",
            "点「我已执行，继续」后程序采集 PPG 并运行算法，校验心率 30~220 bpm、SpO2 70~100%。",
        ),
        timeoutMs = 240_000, requires = setOf("NTF_PPG")) {
        promptManual("请确认待测设备已【静止佩戴】，然后点「我已执行，继续」。")
        runPpgAlgorithm(15_000)
    },

    // ---- DATA-PERF：采样率/长稳 -------------------------------------------------------
    auto("DATA-PERF-001", "实测采样率与标称偏差不超过容差", CHAPTER, Priority.P1, 120_000,
        requires = setOf("NTF_EEG")) {
        val p = connected()
        ensureEegOn(p)
        val got = AtomicBoolean(false)
        val startIdx = AtomicLong(-1)
        val endIdx = AtomicLong(-1)
        val nominal = AtomicReference(0f)
        p.setOnDataListener { _, list ->
            list.firstOrNull { it.dataType == DataType.NTF_EEG }?.let { d ->
                nominal.set(d.sampleRate)
                val s = d.startSampleIndex.toLong()
                if (startIdx.get() < 0) startIdx.set(s)
                endIdx.set(s + d.sampleCount - 1)
                got.set(true)
            }
        }

        check(p.startDataNotification(), "startDataNotification 失败")
        waitFor({ got.get() }, TestConfig.DATA_TIMEOUT_MS, "EEG 数据")
        delay(TestConfig.PERF_MEASURE_SECONDS * 1000L)
        p.stopDataNotification()

        val span = endIdx.get() - startIdx.get() + 1
        val measured = span.toDouble() / TestConfig.PERF_MEASURE_SECONDS
        val nom = nominal.get().toDouble()
        check(nom > 0, "标称采样率应 > 0")
        val ratio = measured / nom
        check(
            abs(ratio - 1.0) <= TestConfig.SAMPLE_RATE_TOLERANCE,
            "实测采样率偏差应 ≤ ${TestConfig.SAMPLE_RATE_TOLERANCE * 100}%（ratio=$ratio）",
        )
        "measured=$measured nominal=$nom ratio=$ratio"
    },
    auto("DATA-PERF-002", "连续长稳起流不掉速", CHAPTER, Priority.P2, 180_000,
        requires = setOf("NTF_EEG")) {
        val p = connected()
        ensureEegOn(p)
        val got = AtomicBoolean(false)
        val firstStart = AtomicLong(-1)
        val lastEnd = AtomicLong(-1)
        p.setOnDataListener { _, list ->
            list.firstOrNull { it.dataType == DataType.NTF_EEG }?.let { d ->
                val s = d.startSampleIndex.toLong()
                if (firstStart.get() < 0) firstStart.set(s)
                lastEnd.set(s + d.sampleCount - 1)
                got.set(true)
            }
        }

        check(p.startDataNotification(), "startDataNotification 失败")
        waitFor({ got.get() }, TestConfig.DATA_TIMEOUT_MS, "EEG 数据")

        // 分段观察：前段 vs 后段采样率，判断是否掉速
        val halfSec = TestConfig.LONGEVITY_STREAM_SECONDS / 2
        val seg1Start = firstStart.get()
        delay(halfSec * 1000L)
        val seg1End = lastEnd.get()
        delay(halfSec * 1000L)
        val seg2End = lastEnd.get()

        check(seg2End > seg1End, "长稳期间应持续有数据")
        val rate1 = (seg1End - seg1Start + 1).toDouble() / halfSec
        val rate2 = (seg2End - seg1End).toDouble() / halfSec
        check(
            rate2 >= rate1 * (1 - TestConfig.SAMPLE_RATE_TOLERANCE),
            "长稳不应掉速（前段=$rate1 后段=$rate2）",
        )
        check(p.stopDataNotification(), "stopDataNotification 失败")
        "rate1=$rate1 rate2=$rate2"
    },
    auto("DATA-PERF-003", "SensorData 标称采样率与 DeviceInfo 一致", CHAPTER, Priority.P2,
        120_000, requires = setOf("NTF_EEG")) {
        val p = connected()
        ensureEegOn(p)
        val info = p.getDeviceInfo() ?: throw AssertionError("getDeviceInfo 不应为 null")

        val slot = AtomicReference<SensorData?>(null)
        p.captureFirst(DataType.NTF_EEG, slot)
        check(p.startDataNotification(), "startDataNotification 失败")
        waitFor({ slot.get() != null }, TestConfig.DATA_TIMEOUT_MS, "EEG 数据")
        p.stopDataNotification()

        val nominal = slot.get()?.sampleRate ?: 0f
        check(nominal > 0f, "SensorData.sampleRate 应 > 0")
        val infoRate = info.eegSampleRate
        if (infoRate > 0f) {
            check(nominal == infoRate, "SensorData 标称采样率($nominal) 应与 DeviceInfo($infoRate) 一致")
        }
        "nominal=$nominal deviceInfo=$infoRate"
    },
    auto("DATA-PERF-004", "单批推送与取结果平均耗时 <100ms", CHAPTER, Priority.P2, 120_000,
        requires = setOf("NTF_EEG")) {
        val samples = collectAlgoBatches("NTF_EEG", 1_000, 1_000)
        val algo = SensorAlgo()
        check(algo.safeInit(), "SensorAlgo.init 返回 false")
        try {
            algo.syncEegSampleRate(samples)
            val batch = samples.toTypedArray()
            val rounds = 1_000
            var pushTotalNs = 0L
            var getTotalNs = 0L
            var pushOk = 0
            var getOk = 0
            repeat(rounds) {
                val t0 = System.nanoTime()
                if (algo.safePush(batch)) pushOk++
                val t1 = System.nanoTime()
                if (!algoError(algo.safeResult("EEG"))) getOk++
                val t2 = System.nanoTime()
                pushTotalNs += t1 - t0
                getTotalNs += t2 - t1
            }
            check(pushOk > 0, "$rounds 次 pushSamples 全部失败")
            val pushAvgMs = pushTotalNs / 1e6 / rounds
            val getAvgMs = getTotalNs / 1e6 / rounds
            check(pushAvgMs < 100.0, "单批 pushSamples 平均耗时应 <100ms，实际=${"%.2f".format(pushAvgMs)}ms")
            check(getAvgMs < 100.0, "getResult 平均耗时应 <100ms，实际=${"%.2f".format(getAvgMs)}ms")
            "push=${"%.2f".format(pushAvgMs)}ms get=${"%.2f".format(getAvgMs)}ms pushOk=$pushOk getOk=$getOk"
        } finally {
            runCatching { algo.destroy() }
        }
    },

    // ---- DATA-ROB：健壮性 / 幂等 ------------------------------------------------------
    auto("DATA-ROB-001", "重复 init 幂等", CHAPTER, Priority.P1) {
        val p = connected()
        check(initProfile(p), "首次 init 应成功")

        var err: Exception? = null
        val ret = try {
            initProfile(p)
        } catch (e: Exception) {
            err = e
            false
        }
        check(err == null, "重复 init 不应抛异常：${err?.javaClass?.simpleName}")
        "repeat init=$ret"
    },
    auto("DATA-ROB-002", "重复起停流幂等", CHAPTER, Priority.P1) {
        val p = connected()
        check(initProfile(p), "init 失败")

        check(p.startDataNotification(), "首次 startDataNotification 应成功")
        var err: Exception? = null
        try {
            p.startDataNotification()
        } catch (e: Exception) {
            err = e
        }
        check(err == null, "重复 startDataNotification 不应抛异常：${err?.javaClass?.simpleName}")

        check(p.stopDataNotification(), "首次 stopDataNotification 应成功")
        try {
            p.stopDataNotification()
        } catch (e: Exception) {
            err = e
        }
        check(err == null, "重复 stopDataNotification 不应抛异常：${err?.javaClass?.simpleName}")
        check(!p.isDataTransfering(), "停流后 isDataTransfering 应为 false")
        "start/stop idempotent"
    },
    auto("DATA-ROB-003", "无数据时取结果为初始态，不伪造结果", CHAPTER, Priority.P1, 60_000) {
        val algo = SensorAlgo()
        check(algo.safeInit(), "SensorAlgo.init 返回 false")
        try {
            var initialStep: Int? = null
            val details = mutableListOf<String>()
            repeat(3) {
                for (type in listOf("EEG", "PPG")) {
                    val result = algo.safeResult(type)
                    check(!algoError(result), "无数据时 getResult($type) 不应报错: $result")
                    val step = algoRows(result).lastOrNull()?.firstOrNull()?.toIntOrNull()
                    if (initialStep == null) initialStep = step
                    check(
                        step == initialStep,
                        "无数据时 STEP 应保持初始值 $initialStep，实际=$step（$type）",
                    )
                    details += "$type step=$step rows=${algoRows(result).size}"
                }
            }
            details.joinToString("; ")
        } finally {
            runCatching { algo.destroy() }
        }
    },
    auto("DATA-ROB-004", "EEG/PPG 双线程并发推送不崩溃", CHAPTER, Priority.P2, 180_000,
        requires = setOf("NTF_EEG", "NTF_PPG")) {
        val eeg = collectAlgoBatches("NTF_EEG", 2_000, 2_000)
        val ppg = collectAlgoBatches("NTF_PPG", 200, 8_000)
        val algo = SensorAlgo()
        check(algo.safeInit(), "SensorAlgo.init 返回 false")
        try {
            algo.syncEegSampleRate(eeg)
            val errors = ConcurrentLinkedQueue<String>()
            val resultLines = AtomicInteger(0)
            val deadline = System.currentTimeMillis() + 5_000
            coroutineScope {
                launch(Dispatchers.Default) {
                    while (System.currentTimeMillis() < deadline) {
                        if (!algo.safePush(eeg.toTypedArray())) errors.offer("push(EEG) 返回 false")
                        delay(20)
                    }
                }
                launch(Dispatchers.Default) {
                    while (System.currentTimeMillis() < deadline) {
                        if (!algo.safePush(ppg.toTypedArray())) errors.offer("push(PPG) 返回 false")
                        delay(20)
                    }
                }
                launch(Dispatchers.Default) {
                    while (System.currentTimeMillis() < deadline) {
                        for (type in listOf("EEG", "PPG")) {
                            val result = algo.safeResult(type)
                            if (algoError(result)) {
                                errors.offer("getResult($type): ${result.trim().take(60)}")
                            } else {
                                resultLines.addAndGet(algoRows(result).size)
                            }
                        }
                        delay(50)
                    }
                }
            }
            check(errors.isEmpty(), "并发推送/取结果出现异常: ${errors.take(5)}")
            check(resultLines.get() > 0, "并发期间未产出可解析的结果行")
            "resultLines=${resultLines.get()}"
        } finally {
            runCatching { algo.destroy() }
        }
    },

    // ---- SDK 未实现 / 需人工，Instrumentation 侧 @Ignore --------------------------------
    auto("DATA-FUNC-014", "IMU/PPG 采样率变更后实际 data rate 随之变化", CHAPTER, Priority.P1,
        400_000, requires = setOf("NTF_IMU", "NTF_PPG")) {
        val spec = TestConfig.targetSpec()
        val targets = listOf("IMU_SAMPLE_RATE", "PPG_SAMPLE_RATE")
            .mapNotNull { key -> spec.sampleRates[key]?.takeIf { it.settable }?.let { key to it } }
        if (targets.isEmpty()) skip("spec 未登记可设置的 IMU/PPG 采样率")

        val p = connected()
        val details = mutableListOf<String>()
        for ((key, sr) in targets) {
            val streamKey = sr.stream
            if (streamKey.isEmpty() || !spec.supports(streamKey)) {
                log("$key 对应流 '$streamKey' 不支持，跳过")
                continue
            }
            val current = safeGetParam(p, key)
            val original = sr.rates.firstOrNull { current.contains(it) } ?: sr.default
            try {
                for (rate in sr.rates) {
                    val ret = safeSetParam(p, key, rate)
                    check(!algoError(ret), "setParam($key, $rate) 应成功，实际=$ret")
                    val measured = measureStreamDataRate(streamKey, 5)
                    val ratio = measured / rate.toDouble()
                    check(
                        abs(ratio - 1.0) <= TestConfig.SAMPLE_RATE_TOLERANCE,
                        "$streamKey 实测 data rate=${"%.1f".format(measured)}Hz 应与 $key=$rate 一致" +
                            "（偏差=${"%.1f".format((ratio - 1) * 100)}%）",
                    )
                    details += "$key=$rate→${"%.1f".format(measured)}Hz"
                }
            } finally {
                runCatching { safeSetParam(p, key, original) }
            }
        }
        details.joinToString(" ")
    },
    manual("DATA-FUNC-015", "EEG 数据真实性（需佩戴头环）", CHAPTER, Priority.P2,
        steps = listOf(
            "Android 不适用：需人工佩戴头环的 semi-auto 用例，EEG 非全零/幅值合理性依赖真实脑电信号，" +
                "仪器环境无法可靠触发（Instrumentation 侧 @Ignore）。",
            "人工步骤：佩戴头环 → 运行起流 → 检查样本非全零且幅值在合理范围。",
        )),
    auto("DATA-FUNC-016", "spec 不支持流无数据（反向）", CHAPTER, Priority.P1, 300_000) {
        val spec = TestConfig.targetSpec()
        val unsupported = spec.streams.filterValues { !it.supported || it.channels <= 0 }.keys.toList()
        check(unsupported.isNotEmpty(), "spec 未登记任何不支持流，无法做反向验证")

        val p = connected()
        val info = p.getDeviceInfo() ?: throw AssertionError("getDeviceInfo 不应为 null")
        val wrongChannels = unsupported.filter { CerelaxSpec.channelCount(info, it) != 0 }
        check(wrongChannels.isEmpty(), "不支持流在 DeviceInfo 中通道数应为 0，异常=$wrongChannels")

        val unsupportedTypes = unsupported.mapNotNull { CerelaxSpec.KEY_TO_DATA_TYPE[it] }.toSet()
        check(unsupportedTypes.isNotEmpty(), "不支持流未映射到 DataType: $unsupported")

        val before = ntfSwitchStates(p)
        val observed = ConcurrentHashMap.newKeySet<Int>()
        val accepted = mutableListOf<String>()
        try {
            // 先关掉所有可切换流，确保观察到的数据只能来自“不支持流”的开关尝试
            for (key in before.keys) runCatching { p.setParam(key, "OFF") }
            for (key in unsupported) {
                val paramKey = CerelaxSpec.paramKey(key)
                val ret = try {
                    p.setParam(paramKey, "ON")
                } catch (e: Exception) {
                    "Error: ${e.message}"
                }
                if (!algoError(ret)) accepted += "$paramKey=$ret"
            }
            if (accepted.isNotEmpty()) {
                log("以下不支持流开关被接受（仍需验证无数据）: $accepted")
            }

            p.setOnDataListener { _, list ->
                list.forEach { d -> if (d.dataType in unsupportedTypes) observed.add(d.dataType) }
            }
            check(p.startDataNotification(), "startDataNotification 失败")
            try {
                delay(5_000)
            } finally {
                runCatching { p.stopDataNotification() }
            }
        } finally {
            restoreSwitches(p, before)
        }

        check(
            observed.isEmpty(),
            "不支持流不应产出数据，实际收到=${observed.map { DataType.nameOf(it) }}",
        )
        "unsupported=${unsupported.size} accepted=${accepted.size}"
    },
)
