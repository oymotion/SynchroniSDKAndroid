package com.oymotion.sensorcapiktdemo.automation

import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import com.oymotion.sensorcapiktdemo.devicespecs.DeviceSpec
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

private const val CHAPTER = "05_多设备"

/** 多设备用例的整体超时（含多轮扫描、连接与 init）。 */
private const val MULTI_TIMEOUT_MS = 300_000L

/** 单设备边界用例超时。 */
private const val SINGLE_TIMEOUT_MS = 180_000L

/** 扫描重试轮次（对应 Instrumentation 侧 scanAllTargets 的 4 轮）。 */
private const val DEVICE_SCAN_ATTEMPTS = 4

private suspend fun initProfile(p: SensorProfile): Boolean =
    p.init(
        TestConfig.PACKAGE_SAMPLE_COUNT,
        TestConfig.INIT_TIMEOUT_MS,
        TestConfig.POWER_REFRESH_MS,
    )

/** 轮询等待 Ready（不抛断言，供需要 SKIP 语义的场景使用）。 */
private suspend fun waitReady(p: SensorProfile): Boolean {
    val deadline = System.currentTimeMillis() + TestConfig.READY_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
        if (p.isReady()) return true
        delay(100)
    }
    return p.isReady()
}

/**
 * 多轮扫描收集已登记设备（mac 去重），不足 [minCount] 时由 [requireDevices] 记 SKIP。
 *
 * 已登记设备来自 `automation/config.json` 的 devices 清单（如 Cerelax-Pro 8517、
 * gForceMax A2A2、OB6000C 6C6B），匹配口径统一走 [TestConfig.matchesKnown]。
 */
private suspend fun TestContext.knownProfiles(minCount: Int): List<SensorProfile> {
    val byMac = LinkedHashMap<String, SensorProfile>()
    repeat(DEVICE_SCAN_ATTEMPTS) { attempt ->
        for (p in requireKnownDevices()) byMac.putIfAbsent(p.device.mac.uppercase(), p)
        if (byMac.size >= minCount) return byMac.values.toList()
        if (attempt < DEVICE_SCAN_ATTEMPTS - 1) {
            log("已匹配 ${byMac.size}/$minCount 台，${TestConfig.SCAN_RETRY_INTERVAL_MS}ms 后重扫（${attempt + 1}/${DEVICE_SCAN_ATTEMPTS - 1}）...")
            delay(TestConfig.SCAN_RETRY_INTERVAL_MS)
        }
    }
    return byMac.values.toList()
}

/** 设备数不足时 SKIP（不判 FAIL）；够数时返回前 [minCount] 台。 */
private suspend fun TestContext.requireDevices(minCount: Int): List<SensorProfile> {
    val found = knownProfiles(minCount)
    if (found.size < minCount) skip("需要 ≥$minCount 台已登记设备（当前 ${found.size} 台）")
    return found.take(minCount)
}

/** connect + 等 Ready + init；任一步失败返回 null（对应 Instrumentation 侧 Assume 跳过）。 */
private suspend fun prepareOrNull(p: SensorProfile, init: Boolean = true): SensorProfile? {
    if (!p.isReady()) {
        if (!p.connect()) return null
        if (!waitReady(p)) return null
    }
    if (init && !p.hasInited() && !initProfile(p)) return null
    return p
}

/** 取 [minCount] 台连接并 init 就绪的设备，未就绪的台数不足时 SKIP。 */
private suspend fun TestContext.readyProfiles(minCount: Int): List<SensorProfile> {
    val devices = requireDevices(minCount)
    val ready = devices.mapNotNull { prepareOrNull(it) }
    if (ready.size < minCount) skip("需要 ≥$minCount 台连接就绪设备（当前 ${ready.size} 台）")
    return ready
}

/** 按 mac 反查登记的 identity，再按型号读 spec（不硬编码型号）。 */
private fun specOf(p: SensorProfile): DeviceSpec? {
    val identity = TestConfig.deviceByMac(p.device.mac)?.identity ?: return null
    return runCatching { TestConfig.specForIdentity(identity) }.getOrNull()
}

/** 设备型号（优先登记清单，回落固件上报名），用于混合型号判定。 */
private fun modelKey(p: SensorProfile): String =
    TestConfig.deviceByMac(p.device.mac)?.model ?: p.getDeviceInfo()?.modelName.orEmpty()

/**
 * 混合型号用例专用设备池：按型号各取一台，凑满两台不同型号连接就绪后返回。
 *
 * 普通多设备用例走 [requireKnownDevices] 的「目标 > 同型号 > 其余」排序，优先凑同型号；
 * 本用例需要的是跨型号组合，因此单独扫描并挑型号不同的两台，不足时 SKIP。
 */
private suspend fun TestContext.readyMixedProfiles(): List<SensorProfile> {
    val byMac = LinkedHashMap<String, SensorProfile>()
    for (attempt in 0 until DEVICE_SCAN_ATTEMPTS) {
        for (p in requireKnownDevices()) byMac.putIfAbsent(p.device.mac.uppercase(), p)
        val models = byMac.values.map { modelKey(it) }.filter { it.isNotBlank() }.distinct()
        if (models.size >= 2) break
        if (attempt < DEVICE_SCAN_ATTEMPTS - 1) {
            log(
                "已匹配 ${byMac.size} 台 / ${models.size} 个型号，${TestConfig.SCAN_RETRY_INTERVAL_MS}ms 后重扫" +
                    "（${attempt + 1}/${DEVICE_SCAN_ATTEMPTS - 1}）...",
            )
            delay(TestConfig.SCAN_RETRY_INTERVAL_MS)
        }
    }
    val models = byMac.values.map { modelKey(it) }.filter { it.isNotBlank() }.distinct()
    if (models.size < 2) skip("需 ≥2 台不同型号的已登记设备（当前型号 $models）")

    val pair = models.take(2).mapNotNull { m -> byMac.values.firstOrNull { modelKey(it) == m } }
    val ready = pair.mapNotNull { prepareOrNull(it) }
    if (ready.size < 2) skip("需 ≥2 台不同型号连接就绪设备（当前 ${ready.size} 台）")
    return ready
}

/** 首包类用例依赖「该型号至少有一条可起流能力」，按各自 spec 判定，不满足则 SKIP。 */
private fun TestContext.requireStreamCapability(profiles: List<SensorProfile>) {
    val lack = profiles.filter { p ->
        val spec = specOf(p)
        spec == null || spec.supportedStreams.isEmpty()
    }
    if (lack.isNotEmpty()) {
        skip("spec 无可起流能力: ${lack.joinToString { "${it.device.mac}(${specOf(it)?.model ?: "无 spec"})" }}")
    }
}

/** 从 multi 结果里按 mac（忽略大小写）取单台结果。 */
private fun resultOf(results: Map<String, Boolean>, mac: String): Boolean? =
    results.entries.firstOrNull { it.key.equals(mac, ignoreCase = true) }?.value

private fun macOf(p: SensorProfile): String = p.device.mac.uppercase()

/** 多设备首包收集器：按 mac 隔离记录 startTimeStamp 与 delay，并计算跨设备散布。 */
private class FirstBatchCollector {
    private val firstStamp = ConcurrentHashMap<String, Int>()
    private val delays = ConcurrentHashMap<String, Int>()

    fun onData(profile: SensorProfile, dataList: List<SensorData>) {
        val mac = profile.device.mac.uppercase()
        if (firstStamp.containsKey(mac)) return
        val d = dataList.firstOrNull() ?: return
        firstStamp.putIfAbsent(mac, d.startTimeStamp)
        delays.putIfAbsent(mac, d.delay)
    }

    fun stampOf(mac: String): Int? = firstStamp[mac.uppercase()]
    fun delayOf(mac: String): Int? = delays[mac.uppercase()]

    /** 跨设备 startTimeStamp 散布（ms）；不足 2 台返回 null。 */
    fun spreadMs(): Int? {
        if (firstStamp.size < 2) return null
        val longs = firstStamp.values.map { it.toLong() and 0xFFFFFFFFL }
        return (longs.maxOrNull()!! - longs.minOrNull()!!).toInt()
    }
}

val multiCases = listOf(
    // =====================================================================================
    // MULTI-FUNC-*（与 Instrumentation 05_MultiDevice/CerelaxMultiDeviceTest 逐条对应）
    // =====================================================================================
    auto("MULTI-FUNC-001", "两台同时起流", CHAPTER, Priority.P0, MULTI_TIMEOUT_MS) {
        val profiles = readyProfiles(2)
        val (results, errors) = controller.multiStartDataNotification(profiles, 30_000, 5, 3)
        check(results.size == 2, "multiStart 应覆盖两台，实际=$results")
        check(results.values.all { it }, "每台 multiStart 应成功（errors=$errors）")
        check(profiles.all { it.isDataTransfering() }, "两台均应处于传输中")
        "results=$results"
    },
    auto("MULTI-FUNC-002", "两台同时停流", CHAPTER, Priority.P0, MULTI_TIMEOUT_MS) {
        val profiles = readyProfiles(2)
        val (startResults, startErrors) = controller.multiStartDataNotification(profiles, 30_000, 5, 3)
        if (!startResults.values.all { it }) {
            skip("起流失败，跳过停流断言（results=$startResults errors=$startErrors）")
        }

        val (stopResults, stopErrors) = controller.multiStopDataNotification(profiles, 10_000)
        check(stopResults.size == 2, "multiStop 应覆盖两台，实际=$stopResults")
        check(stopResults.values.all { it }, "每台 multiStop 应成功（errors=$stopErrors）")
        check(profiles.none { it.isDataTransfering() }, "两台均应已停流")
        "start=$startResults stop=$stopResults"
    },
    auto("MULTI-FUNC-003", "某台未 init 不影响其余起流", CHAPTER, Priority.P1, MULTI_TIMEOUT_MS) {
        val devices = requireDevices(2)
        val p1 = prepareOrNull(devices[0]) ?: skip("p1 connect/init 未就绪")
        val p2 = devices[1]
        if (!p2.isReady()) {
            if (!p2.connect() || !waitReady(p2)) skip("两台均需 connect 成功并到达 Ready")
        }

        // p2 已连接 Ready 但不 init
        val (results, errors) = controller.multiStartDataNotification(listOf(p1, p2), 30_000, 5, 3)
        check(
            resultOf(results, p1.device.mac) == true && p1.isDataTransfering(),
            "已 init 台应起流成功（results=$results errors=$errors）",
        )
        check(resultOf(results, p2.device.mac) != true, "未 init 台应起流失败（results=$results）")
        "results=$results"
    },
    auto("MULTI-FUNC-004", "首包时差 ≤ maxDelayDispersionMs", CHAPTER, Priority.P1,
        MULTI_TIMEOUT_MS) {
        val profiles = readyProfiles(2)
        requireStreamCapability(profiles)

        val collector = FirstBatchCollector()
        profiles.forEach { it.setOnDataListener(collector::onData) }
        val (results, errors) = controller.multiStartDataNotification(profiles, 30_000, 5, 3)
        check(results.values.all { it }, "两台均起流（results=$results errors=$errors）")

        delay(TestConfig.COLLECT_SECONDS * 1000L)

        profiles.forEach {
            check(collector.stampOf(macOf(it)) != null, "设备 ${macOf(it)} 应收到带 startTimeStamp 的首包")
        }
        val spread = collector.spreadMs() ?: throw AssertionError("应至少收到两台首包以计算散布")
        log("跨设备 startTimeStamp 散布 = $spread ms")
        check(spread <= 5, "散布应 ≤ 5ms，实际 $spread ms")
        "spread=$spread ms"
    },
    auto("MULTI-FUNC-005", "maxDelayDispersionMs=-1 跳过时差校验", CHAPTER, Priority.P1,
        MULTI_TIMEOUT_MS) {
        val profiles = readyProfiles(2)
        val (results, errors) = controller.multiStartDataNotification(profiles, 30_000, -1, 3)
        check(results.values.all { it }, "两台均起流（results=$results errors=$errors）")
        "results=$results"
    },
    auto("MULTI-FUNC-006", "混合型号放宽参数起流", CHAPTER, Priority.P2, MULTI_TIMEOUT_MS) {
        val profiles = readyMixedProfiles()
        val models = profiles.map { modelKey(it) }.toSet()

        val (results, errors) = controller.multiStartDataNotification(profiles, 60_000, -1, 5)
        check(results.values.all { it }, "混合型号两台均起流（results=$results errors=$errors）")
        "models=$models results=$results"
    },
    auto("MULTI-FUNC-007", "已起流设备再次 multiStart（restart 语义）", CHAPTER, Priority.P1,
        MULTI_TIMEOUT_MS) {
        val profiles = readyProfiles(2)
        val p1 = profiles[0]
        val p2 = profiles[1]
        check(p1.startDataNotification(), "p1 单独起流失败")
        delay(2_000)

        val (results, errors) = controller.multiStartDataNotification(profiles, 30_000, 5, 3)
        check(resultOf(results, p1.device.mac) == true, "已起流 p1 应 restart 成功（results=$results）")
        check(resultOf(results, p2.device.mac) == true, "空闲 p2 应起流成功（results=$results）")
        "results=$results errors=$errors"
    },
    manual("MULTI-FUNC-008", "两后端各测对齐下发", CHAPTER, Priority.P2,
        steps = listOf(
            "Android 不适用：SDK 固定使用系统原生 BLE，无 bumble/bleak 后端切换能力" +
                "（对应 Python 子进程 SENSOR_SDK_BLE_BACKEND），Instrumentation 侧 @Ignore。",
        )),
    auto("MULTI-FUNC-009", "跨设备 startTimeStamp 散布与 getDelay 符合预期", CHAPTER,
        Priority.P1, MULTI_TIMEOUT_MS) {
        val profiles = readyProfiles(2)
        requireStreamCapability(profiles)

        val collector = FirstBatchCollector()
        profiles.forEach { it.setOnDataListener(collector::onData) }
        val (results, errors) = controller.multiStartDataNotification(profiles, 30_000, 5, 3)
        check(results.values.all { it }, "两台均起流（results=$results errors=$errors）")

        delay(TestConfig.COLLECT_SECONDS * 1000L)

        profiles.forEach {
            val m = macOf(it)
            val ts = collector.stampOf(m)
            val dl = collector.delayOf(m)
            log("设备 $m: startTimeStamp=$ts delay=$dl")
            check(ts != null, "设备 $m startTimeStamp 应非空")
            check(dl != null && dl >= 0, "设备 $m delay 应 ≥ 0")
        }
        val spread = collector.spreadMs() ?: throw AssertionError("应至少收到两台首包以计算散布")
        log("跨设备 startTimeStamp 散布 = $spread ms")
        check(spread <= 5, "散布应 ≤ 5ms，实际 $spread ms")
        "spread=$spread ms"
    },

    // =====================================================================================
    // MULTI-BND / MULTI-ROB
    // =====================================================================================
    auto("MULTI-BND-001", "multiStart/multiStop 空集合 / 单台 / 非法参数不崩溃", CHAPTER,
        Priority.P1, SINGLE_TIMEOUT_MS) {
        // 空集合：异步调用，验证不抛同步异常
        try {
            controller.multiStartDataNotification(emptyList(), 30_000, 5, 3) { _, _ -> }
            controller.multiStopDataNotification(emptyList(), 10_000) { _, _ -> }
        } catch (t: Throwable) {
            throw AssertionError("空集合 multiStart/multiStop 不应崩溃: $t")
        }

        val devices = requireDevices(1)
        val profile = prepareOrNull(devices[0]) ?: skip("需要 1 台连接就绪设备")

        // 单台不崩溃（成功/失败均可）
        val (single, singleErrors) = controller.multiStartDataNotification(
            listOf(profile), 30_000, 5, 3,
        )
        log("单台 multiStart -> $single ($singleErrors)")

        // 非法参数不抛同步异常
        try {
            controller.multiStartDataNotification(listOf(profile), 0, 5, 3) { _, _ -> }
            controller.multiStartDataNotification(listOf(profile), 30_000, -2, 3) { _, _ -> }
            controller.multiStartDataNotification(listOf(profile), 30_000, 5, 0) { _, _ -> }
            controller.multiStopDataNotification(listOf(profile), 0) { _, _ -> }
        } catch (t: Throwable) {
            throw AssertionError("非法参数 multiStart/multiStop 不应崩溃: $t")
        }
        "single=$single"
    },
    auto("MULTI-ROB-001", "multiStop 重复调用幂等", CHAPTER, Priority.P2, MULTI_TIMEOUT_MS) {
        val profiles = readyProfiles(2)
        val (startResults, startErrors) = controller.multiStartDataNotification(profiles, 30_000, 5, 3)
        if (!startResults.values.all { it }) {
            skip("起流失败，跳过幂等断言（results=$startResults errors=$startErrors）")
        }

        val (stop1, err1) = controller.multiStopDataNotification(profiles, 10_000)
        val (stop2, err2) = controller.multiStopDataNotification(profiles, 10_000)
        log("multiStop#1 -> $stop1 ($err1)；multiStop#2 -> $stop2 ($err2)")
        check(profiles.none { it.isDataTransfering() }, "重复 multiStop 后两台均应保持停流")
        "stop1=$stop1 stop2=$stop2"
    },
)
