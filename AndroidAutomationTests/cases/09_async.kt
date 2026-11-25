package com.oymotion.sensorcapiktdemo.automation

import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import com.oymotion.sensorcapiktdemo.cerelaxpro.CerelaxSpec
import com.oymotion.sensorcapiktdemo.devicespecs.DeviceSpec
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** 多设备用例整体超时（含多轮扫描、连接与 init）。 */
private const val ASYNC_MULTI_TIMEOUT_MS = 600_000L

/** 扫描重试轮次（对应 Instrumentation 侧 scanAllTargets 的 4 轮）。 */
private const val ASYNC_SCAN_ATTEMPTS = 4

/** Ready 后 init（参数取 TestConfig，对应 Instrumentation 侧同名辅助）。 */
private suspend fun initProfile(p: SensorProfile): Boolean =
    p.init(TestConfig.PACKAGE_SAMPLE_COUNT, TestConfig.INIT_TIMEOUT_MS, TestConfig.POWER_REFRESH_MS)

/** CompletableFuture 取值：超时/异常一律返回 null（对应 Instrumentation 侧 getOrNull）。 */
private fun <T> CompletableFuture<T>.awaitOrNull(timeoutMs: Long): T? =
    runCatching { get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()

/**
 * 参数/命令返回是否为错误。
 * AAR 对不支持的能力会返回 `Not supported`，部分错误会以 `<...>` 形式返回；
 * 只认 `Error` 前缀会把这两类误判成成功（假 pass）。
 */
private fun isError(ret: String): Boolean =
    ret.startsWith("Error") ||
        ret.startsWith("ERROR:") ||
        ret.startsWith("Not supported", ignoreCase = true) ||
        ret.startsWith("<")

/**
 * 给 profile 挂数据监听并累计收到的样本数。
 * 用于验证「真的在收数据」，而不是只看 `isDataTransfering()` 这个状态位。
 */
private fun countSamples(p: SensorProfile): AtomicInteger {
    val counter = AtomicInteger(0)
    p.setOnDataListener { _, list -> counter.addAndGet(list.sumOf { it.sampleCount }) }
    return counter
}

/**
 * 多设备同步起流参数：同型号用严格首包离散度；混型号关闭该判定并放宽重试
 * （与 Python 侧口径一致：同型号 30s/5/3，混型号 60s/-1/5）。
 */
private fun multiParams(profiles: List<SensorProfile>): Triple<Int, Int, Int> =
    if (profiles.any { !TestConfig.matchesTarget(it.device) }) Triple(60_000, -1, 5)
    else Triple(30_000, 5, 3)

/**
 * 选一个本 spec 支持、且在设备 getParam("NTF") 聚合列表里真实存在的开关 key。
 * 不能直接用 supportedStreams.first()：spec 把 IMU 子流 ACC/GYRO 也标为 supported，
 * 但它们没有独立开关，setParam 会抛 `Not supported`。
 */
private suspend fun switchableStreamKey(p: SensorProfile, spec: DeviceSpec): String? {
    val ntf = try {
        p.getParam("NTF")
    } catch (_: Exception) {
        return null
    }
    val listed = CerelaxSpec.parseSwitchList(ntf).map { it.first }.toSet()
    return CerelaxSpec.supportedStreamKeys(spec)
        .map { CerelaxSpec.paramKey(it) }
        .firstOrNull { it in listed }
}

/** 轮询等待 Ready，超时返回 false（供需要 SKIP 语义的多设备用例使用，不抛断言）。 */
private suspend fun pollReady(p: SensorProfile): Boolean {
    val deadline = System.currentTimeMillis() + TestConfig.READY_TIMEOUT_MS
    while (System.currentTimeMillis() < deadline) {
        if (p.isReady()) return true
        delay(100)
    }
    return p.isReady()
}

/** connect + 等 Ready + init；任一步失败返回 null（对应 Instrumentation 侧 connectInit）。 */
private suspend fun connectInit(p: SensorProfile): SensorProfile? {
    if (!p.isReady()) {
        if (!p.connect()) return null
        if (!pollReady(p)) return null
    }
    return if (initProfile(p)) p else null
}

/** 多轮扫描收集已登记设备（mac 去重），连接并 init 至少 [minCount] 台；不足时 SKIP。 */
private suspend fun TestContext.readyProfiles(minCount: Int): List<SensorProfile> {
    val byMac = LinkedHashMap<String, SensorProfile>()
    for (attempt in 0 until ASYNC_SCAN_ATTEMPTS) {
        for (p in requireKnownDevices()) byMac.putIfAbsent(p.device.mac.uppercase(), p)
        if (byMac.size >= minCount) break
        if (attempt < ASYNC_SCAN_ATTEMPTS - 1) {
            log(
                "已匹配 ${byMac.size}/$minCount 台，${TestConfig.SCAN_RETRY_INTERVAL_MS}ms 后重扫" +
                    "（${attempt + 1}/${ASYNC_SCAN_ATTEMPTS - 1}）...",
            )
            delay(TestConfig.SCAN_RETRY_INTERVAL_MS)
        }
    }
    if (byMac.size < minCount) skip("需要 ≥$minCount 台已登记设备（当前 ${byMac.size} 台）")

    val ready = byMac.values.take(minCount).mapNotNull { connectInit(it) }
    if (ready.size < minCount) skip("需要 ≥$minCount 台连接并 init 就绪的设备（当前 ${ready.size} 台）")
    return ready
}

/** 从 multi 结果里按 mac（忽略大小写）取单台结果。 */
private fun resultOf(results: Map<String, Boolean>, mac: String): Boolean? =
    results.entries.firstOrNull { it.key.equals(mac, ignoreCase = true) }?.value

val asyncCases = run {
    val chapter = "09_异步接口"
    listOf(
        // =====================================================================================
        // ASYNC-FUNC-001 ~ 012（与 Instrumentation 09_Async_Interface/CerelaxAsyncTest 逐条对应）
        // =====================================================================================
        auto("ASYNC-FUNC-001", "suspend scan 返回设备列表", chapter, Priority.P0) {
            check(controller.isEnable, "蓝牙未开启（isEnable=false）")
            val devices = controller.scan(TestConfig.SCAN_TIMEOUT_MS)
            log("scan 返回 ${devices.size} 台设备")
            check(devices.isNotEmpty(), "scan 应返回非空设备列表")
            check(
                devices.any { it.name.orEmpty().isNotBlank() && it.mac.isNotBlank() },
                "至少一台设备 Name/Address 非空",
            )
            // 只要求「有设备」会让附近任意 BLE 设备都算通过，必须确认本轮目标设备在列表里。
            check(
                devices.any { TestConfig.matchesTarget(it) },
                "scan 结果应包含目标设备 identity=${TestConfig.TARGET_IDENTITY}",
            )
            "found=${devices.size}"
        },
        auto(
            "ASYNC-FUNC-002", "connectAsync 成功（True+Ready）/ 失败（无效 MAC）", chapter,
            Priority.P0, timeoutMs = 120_000,
        ) {
            // 成功场景
            val p = requireTarget()
            val ok = p.connectAsync().awaitOrNull(TestConfig.READY_TIMEOUT_MS)
            check(ok == true, "connectAsync 应返回 True，实际=$ok")
            waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "connectAsync 后 Ready")
            p.disconnectAsync().awaitOrNull(TestConfig.READY_TIMEOUT_MS)
            waitFor(
                { p.getDeviceState() == DeviceState.DISCONNECTED },
                TestConfig.READY_TIMEOUT_MS, "Disconnected",
            )

            // 失败场景（无效 MAC）：返回值 false 或 Future 异常完成均视为失败；
            // 必须真正调用并断言，不能因为构造不到设备就整段空过；超时也不算“拒绝”。
            val invalidMac = "00:00:00:00:00:00"
            val invalid = controller.requireSensor(invalidMac)
                ?: throw AssertionError("requireSensor($invalidMac) 返回 null，失败场景无法验证（不能记 PASS）")
            val errors = CopyOnWriteArrayList<String>()
            try {
                invalid.setOnErrorListener { _, errorMsg ->
                    errors.add(errorMsg)
                    log("  onError: $errorMsg")
                }
                val failOk: Boolean = try {
                    invalid.connectAsync().get(TestConfig.READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    throw AssertionError(
                        "对无效设备 connectAsync ${TestConfig.READY_TIMEOUT_MS}ms 内未返回（挂起不能视为拒绝）",
                    )
                } catch (e: Exception) {
                    log("connectAsync 异常完成（视为失败）: ${e.javaClass.simpleName}: ${e.message.orEmpty()}")
                    false
                }
                check(!failOk, "对无效设备 connectAsync 应返回 False/异常，实际=$failOk")
                check(!invalid.isReady(), "无效设备连接后不应进入 Ready")
                log("无效 MAC connectAsync -> $failOk errors=$errors")
            } finally {
                runCatching { invalid.setOnErrorListener { _, _ -> } }
                withTimeoutOrNull(5_000) { runCatching { invalid.disconnect() } }
            }
            "connectAsync ok=$ok"
        },
        auto("ASYNC-FUNC-003", "disconnectAsync 成功（True+Disconnected）", chapter, Priority.P0) {
            val p = requireTarget()
            val states = CopyOnWriteArrayList<Int>()
            p.setOnStateChangeListener { _, state ->
                states.add(state)
                log("  onStateChanged -> ${DeviceState.nameOf(state)}")
            }

            val connOk = p.connectAsync().awaitOrNull(TestConfig.READY_TIMEOUT_MS)
            check(connOk == true, "connectAsync 失败，实际=$connOk")
            waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "connectAsync 后 Ready")

            states.clear()
            val ok = p.disconnectAsync().awaitOrNull(TestConfig.READY_TIMEOUT_MS)
            check(ok == true, "disconnectAsync 应返回 True，实际=$ok")
            waitFor(
                { p.getDeviceState() == DeviceState.DISCONNECTED },
                TestConfig.READY_TIMEOUT_MS, "disconnectAsync 后 Disconnected",
            )
            check(
                p.getDeviceState() == DeviceState.DISCONNECTED,
                "disconnectAsync 后 deviceState 应为 DISCONNECTED",
            )
            check(
                states.contains(DeviceState.DISCONNECTED),
                "回调应触发 DISCONNECTED，实际=${states.map { DeviceState.nameOf(it) }}",
            )
            "disconnectAsync=true states=${states.map { DeviceState.nameOf(it) }}"
        },
        auto("ASYNC-FUNC-004", "suspend init 成功（True+hasInited）/ 失败（未 Ready）", chapter,
            Priority.P0) {
            val p = requireTarget()
            check(p.connect(), "connect() 返回 false")
            waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "设备 Ready")

            check(initProfile(p), "asyncInit 应返回 True")
            check(p.hasInited(), "asyncInit 后 hasInited 应为 True")

            p.disconnect()
            waitFor(
                { p.getDeviceState() == DeviceState.DISCONNECTED },
                TestConfig.READY_TIMEOUT_MS, "Disconnected",
            )
            val fail = runCatching { initProfile(p) }
            fail.exceptionOrNull()?.let {
                log("未 Ready init 抛异常（suspend 语义）: ${it.javaClass.simpleName}: ${it.message.orEmpty()}")
            }
            check(
                fail.getOrNull() == false || fail.isFailure,
                "未 Ready 时 asyncInit 应返回 False/抛异常，实际=${fail.getOrNull()}",
            )
            "init=true fail=${fail.getOrNull()}"
        },
        auto("ASYNC-FUNC-005", "suspend setParam 与同步一致", chapter, Priority.P0) {
            val spec = TestConfig.targetSpec()
            val p = connected()
            val key = switchableStreamKey(p, spec)
                ?: skip("设备 NTF 列表中无可切换的 spec 支持流")

            val origin = p.getParam(key)
            check(!isError(origin) && origin.isNotBlank(), "读取 $key 原值失败，实际=$origin")

            val retOn = p.setParam(key, "ON")
            check(!isError(retOn), "setParam($key, ON) 应成功，实际=$retOn")
            val vOn = p.getParam(key)
            check(vOn.contains("ON"), "getParam($key) 应含 ON，实际=$vOn")

            val retOff = p.setParam(key, "OFF")
            check(!isError(retOff), "setParam($key, OFF) 应成功，实际=$retOff")
            val vOff = p.getParam(key)
            check(vOff.contains("OFF"), "getParam($key) 应含 OFF，实际=$vOff")

            // 恢复测试前原值，避免污染后续用例
            val retRestore = p.setParam(key, origin)
            check(!isError(retRestore), "恢复 $key=$origin 应成功，实际=$retRestore")
            val vRestored = p.getParam(key)
            check(vRestored == origin, "恢复后 getParam($key) 应为 $origin，实际=$vRestored")

            p.disconnect()
            "key=$key origin=$origin on=$retOn off=$vOff restored=$vRestored"
        },
        auto("ASYNC-FUNC-006", "suspend getParam 与回调式结果一致", chapter, Priority.P0) {
            val spec = TestConfig.targetSpec()
            val p = connected()
            val key = switchableStreamKey(p, spec)
                ?: skip("设备 NTF 列表中无可切换的 spec 支持流")

            val v1 = p.getParam(key)
            check(!isError(v1), "getParam($key) 不应 Error，实际=$v1")
            check(v1.isNotBlank(), "getParam($key) 不应返回空值（空值不能算一致）")

            // 与 Java 回调式 getParam 交叉校验：两条通道读到的值应一致
            val cb = CompletableFuture<String>()
            p.getParam(key) { value, errorMsg ->
                cb.complete(if (errorMsg.isNullOrBlank()) value else "Error: $errorMsg")
            }
            val v2 = cb.awaitOrNull(5_000)
            log("getParam($key) suspend=$v1 callback=$v2")
            check(v2 != null, "回调式 getParam($key) 5s 内未返回")
            check(v2 == v1, "suspend/回调式 getParam($key) 应一致（$v1 != $v2）")

            p.disconnect()
            "key=$key value=$v1"
        },
        auto("ASYNC-FUNC-007", "suspend getBatteryLevel 与同步读取一致", chapter, Priority.P1) {
            val p = connected()
            val level = p.getBatteryLevel()
            val syncLevel = p.getBatteryLevelSync()
            log("getBatteryLevel(suspend)=$level getBatteryLevelSync()=$syncLevel")
            check(level == -1 || level in 0..100, "电量应在 0..100 或 -1，实际=$level")
            check(syncLevel == -1 || syncLevel in 0..100, "同步电量应在 0..100 或 -1，实际=$syncLevel")
            if (level >= 0 && syncLevel >= 0) {
                check(level == syncLevel, "suspend/同步电量应一致（$level != $syncLevel）")
            } else {
                // 之前接受「-1 即通过」，导致电量接口完全失效也能 PASS；至少要求一侧读到有效值。
                check(level >= 0 || syncLevel >= 0, "suspend/同步都未取到有效电量（-1/-1），不能判通过")
            }

            p.disconnect()
            "battery=$level/$syncLevel"
        },
        auto("ASYNC-FUNC-008", "suspend startDataNotification 起流（状态+实收数据）", chapter,
            Priority.P0) {
            val p = connected()
            val counter = countSamples(p)
            try {
                check(p.startDataNotification(), "startDataNotification 应返回 True")
                check(p.isDataTransfering(), "起流后 isDataTransfering 应为 True")
                // 状态位只代表「已调用起流」，必须确认真的收到数据
                waitFor({ counter.get() > 0 }, TestConfig.DATA_TIMEOUT_MS, "起流后首个数据回调")
            } finally {
                runCatching { p.stopDataNotification() }
                runCatching { p.disconnect() }
            }
            "start=true samples=${counter.get()}"
        },
        auto("ASYNC-FUNC-009", "suspend stopDataNotification 停流（回调停止）", chapter,
            Priority.P0) {
            val p = connected()
            val counter = countSamples(p)
            try {
                check(p.startDataNotification(), "startDataNotification 失败")
                waitFor({ counter.get() > 0 }, TestConfig.DATA_TIMEOUT_MS, "停流前应先收到数据")

                val ok = p.stopDataNotification()
                check(ok, "stopDataNotification 应返回 True")
                check(!p.isDataTransfering(), "停流后 isDataTransfering 应为 False")

                // 刹车：先消化缓冲尾流，再观察增量，避免把停流瞬间的残留数据误判为「仍在收数据」
                delay(2_000)
                val baseline = counter.get()
                delay(3_000)
                check(counter.get() == baseline, "停流后仍收到 ${counter.get() - baseline} 个样本")
            } finally {
                runCatching { p.stopDataNotification() }
                runCatching { p.disconnect() }
            }
            "stop=true samples=${counter.get()}"
        },
        auto("ASYNC-FUNC-010", "CompletableFuture 连接 + suspend init/起停流/断开", chapter,
            Priority.P0) {
            val p = requireTarget()

            // async 连接
            val connOk = p.connectAsync().awaitOrNull(TestConfig.READY_TIMEOUT_MS)
            check(connOk == true, "connectAsync 应返回 True，实际=$connOk")
            waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "connectAsync 后 Ready")

            // suspend init
            check(initProfile(p), "init 失败")
            check(p.hasInited(), "hasInited 应为 True")

            // suspend 起流：先确认传输态，再确认真的收到数据
            val counter = countSamples(p)
            check(p.startDataNotification(), "startDataNotification 失败")
            try {
                waitFor({ p.isDataTransfering() }, 5_000, "起流后 isDataTransfering")
                waitFor({ counter.get() > 0 }, TestConfig.DATA_TIMEOUT_MS, "起流后首个数据回调")
            } catch (e: Throwable) {
                runCatching { p.stopDataNotification() }
                throw e
            }

            // suspend 停流
            check(p.stopDataNotification(), "stopDataNotification 失败")
            waitFor({ !p.isDataTransfering() }, 5_000, "停流后 isDataTransfering=false")

            // suspend 断开
            check(p.disconnect(), "disconnect 失败")
            waitFor(
                { p.getDeviceState() == DeviceState.DISCONNECTED },
                TestConfig.READY_TIMEOUT_MS, "Disconnected",
            )
            check(
                p.getDeviceState() == DeviceState.DISCONNECTED,
                "disconnect 后 deviceState 应为 DISCONNECTED",
            )

            // ---- 混用边界：在 SDK 回调线程（CapiDispatch 的 SensorCapiCallbacks）里调同步 API ----
            // onNativeStateChange(DISCONNECTED) 会先把 powerCache 置 -1，再投递状态回调，
            // 所以此刻 getBatteryLevelSync 会命中线程守卫，而不是直接返回缓存电量。
            // 若电量回调恰好先于状态回调把缓存重新填上，则重连再试（最多 3 次）。
            var rejected: Throwable? = null
            var returnedValue: Any? = null
            for (attempt in 1..3) {
                val probe = CompletableFuture<Any>()
                p.setOnStateChangeListener { _, state ->
                    if (state == DeviceState.DISCONNECTED && !probe.isDone) {
                        probe.complete(
                            try {
                                p.getBatteryLevelSync()
                            } catch (t: Throwable) {
                                t
                            },
                        )
                    }
                }
                val connAgain = p.connectAsync().awaitOrNull(TestConfig.READY_TIMEOUT_MS)
                check(connAgain == true, "第 $attempt 次 connectAsync 应返回 True，实际=$connAgain")
                waitFor({ p.isReady() }, TestConfig.READY_TIMEOUT_MS, "第 $attempt 次 Ready")
                check(p.disconnect(), "第 $attempt 次 disconnect 失败")

                val outcome = probe.awaitOrNull(15_000)
                    ?: throw AssertionError("第 $attempt 次回调内 getBatteryLevelSync 15s 未返回（阻塞）")
                if (outcome is Throwable) {
                    rejected = outcome
                    returnedValue = null
                    break
                }
                returnedValue = outcome
                log("第 $attempt 次回调内 getBatteryLevelSync 返回缓存 $outcome，重试")
                waitFor(
                    { p.getDeviceState() == DeviceState.DISCONNECTED },
                    TestConfig.READY_TIMEOUT_MS, "第 $attempt 次 Disconnected",
                )
            }
            check(
                rejected is IllegalStateException,
                "回调线程内调同步 API 应抛 IllegalStateException，实际=" +
                    (rejected?.let { "${it.javaClass.simpleName}: ${it.message}" }
                        ?: "返回 $returnedValue（未命中线程守卫或 powerCache 未清空）"),
            )
            log("回调线程同步调用被拒绝: ${rejected?.message}")
            "asyncOk=true callbackSync=${rejected?.javaClass?.simpleName}"
        },
        auto("ASYNC-FUNC-011", "multiStartDataNotification 同步起流（≥2 台）", chapter,
            Priority.P1, ASYNC_MULTI_TIMEOUT_MS) {
            val profiles = readyProfiles(2)
            val samples = ConcurrentHashMap<String, AtomicInteger>()
            profiles.forEach { p -> samples[p.device.mac.uppercase()] = countSamples(p) }

            val (timeoutMs, dispersionMs, attempts) = multiParams(profiles)
            log("multiParams: timeout=$timeoutMs dispersionMs=$dispersionMs attempts=$attempts")
            val (results, errors) = controller.multiStartDataNotification(
                profiles, timeoutMs, dispersionMs, attempts,
            )
            log("multiStartDataNotification -> $results errors=$errors")
            profiles.forEach { p ->
                val mac = p.device.mac.uppercase()
                val value = resultOf(results, p.device.mac)
                check(value == true, "[$mac] multiStart 应为 True，实际=$value")
                check(p.isDataTransfering(), "[$mac] 起流后 isDataTransfering 应为 True")
                // 状态位之外，逐台确认真的收到数据，避免「已起流但无数据」假 pass
                waitFor(
                    { samples.getValue(mac).get() > 0 },
                    TestConfig.DATA_TIMEOUT_MS, "[$mac] 起流后首个数据回调",
                )
            }

            controller.multiStopDataNotification(profiles, TestConfig.SCAN_TIMEOUT_MS)
            profiles.forEach { it.disconnect() }
            "results=$results samples=${samples.mapValues { it.value.get() }}"
        },
        auto("ASYNC-FUNC-012", "multiStopDataNotification 同步停流（≥2 台）", chapter,
            Priority.P1, ASYNC_MULTI_TIMEOUT_MS) {
            val profiles = readyProfiles(2)
            // 起流结果必须断言：multiStart 全失败时，后面的「停流成功 + 无数据传输」可能天然成立
            val (timeoutMs, dispersionMs, attempts) = multiParams(profiles)
            log("multiParams: timeout=$timeoutMs dispersionMs=$dispersionMs attempts=$attempts")
            val (startResults, startErrors) = controller.multiStartDataNotification(
                profiles, timeoutMs, dispersionMs, attempts,
            )
            log("multiStartDataNotification -> $startResults errors=$startErrors")
            profiles.forEach { p ->
                val value = resultOf(startResults, p.device.mac)
                check(value == true, "[${p.device.mac}] 前置 multiStart 应为 True，实际=$value")
            }
            check(
                profiles.all { it.isDataTransfering() },
                "停流前各台应处于传输态（说明确实起过流）",
            )

            val (results, errors) = controller.multiStopDataNotification(
                profiles, TestConfig.SCAN_TIMEOUT_MS,
            )
            log("multiStopDataNotification -> $results errors=$errors")
            profiles.forEach { p ->
                val value = resultOf(results, p.device.mac)
                check(value == true, "[${p.device.mac}] multiStop 应为 True，实际=$value")
            }
            check(
                profiles.none { it.isDataTransfering() },
                "停流后各台 isDataTransfering 应为 False",
            )

            profiles.forEach { it.disconnect() }
            "results=$results"
        },

        // =====================================================================================
        // ASYNC-FUNC-013 ~ 015：SensorAlgo suspend 扩展（init 幂等 / destroy 后复用）
        // =====================================================================================
        auto("ASYNC-FUNC-013", "SensorAlgo init 成功后 hasInit=true", chapter, Priority.P0) {
            val algo = SensorAlgo()
            try {
                check(algo.safeInit(), "SensorAlgo.init 应返回 True")
                check(algo.hasInit(), "init 后 hasInit 应为 true")
                "init=true hasInit=true"
            } finally {
                runCatching { algo.destroy() }
            }
        },
        auto("ASYNC-FUNC-014", "SensorAlgo init 重复调用幂等", chapter, Priority.P1) {
            val algo = SensorAlgo()
            try {
                check(algo.safeInit(), "首次 init 应返回 True")
                val second = algo.safeInit()
                check(second, "重复 init 应返回 True，实际=$second")
                check(algo.hasInit(), "重复 init 后 hasInit 应保持 true")
                "init x2 OK"
            } finally {
                runCatching { algo.destroy() }
            }
        },
        // AAR 的 SensorAlgo.destroy() 会把 nativeHandle 置 0 并销毁 native 对象，该实例从此失效：
        // 再调 init() 走 nativeHandle==0 分支直接回调 0（false），不会再进 nativeInit。
        // 因此「destroy 后复用」在 Android 上只能新建实例，不能对旧实例重新 init。
        auto("ASYNC-FUNC-015", "SensorAlgo destroy 后可重新 init", chapter, Priority.P1) {
            val algo = SensorAlgo()
            var reused: SensorAlgo? = null
            try {
                check(algo.safeInit(), "首次 init 应返回 True")
                algo.destroy()
                check(!algo.hasInit(), "destroy 后 hasInit 应为 false")
                val again = algo.safeInit()
                log("destroy 后同一实例 init -> $again（AAR 已置 nativeHandle=0，预期 false）")

                reused = SensorAlgo()
                check(reused.safeInit(), "destroy 后新建实例 init 应返回 True")
                check(reused.hasInit(), "新实例 init 后 hasInit 应为 true")
                "destroy -> new instance init OK (same-instance retry=$again)"
            } finally {
                runCatching { algo.destroy() }
                runCatching { reused?.destroy() }
            }
        },
    )
}
