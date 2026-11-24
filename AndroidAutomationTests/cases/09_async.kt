package com.oymotion.sensorcapiktdemo.automation

import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import com.oymotion.sensorcapiktdemo.cerelaxpro.CerelaxSpec
import com.oymotion.sensorcapiktdemo.devicespecs.DeviceSpec
import kotlinx.coroutines.delay
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** 多设备用例整体超时（含多轮扫描、连接与 init）。 */
private const val ASYNC_MULTI_TIMEOUT_MS = 300_000L

/** 扫描重试轮次（对应 Instrumentation 侧 scanAllTargets 的 4 轮）。 */
private const val ASYNC_SCAN_ATTEMPTS = 4

/** Ready 后 init（参数取 TestConfig，对应 Instrumentation 侧同名辅助）。 */
private suspend fun initProfile(p: SensorProfile): Boolean =
    p.init(TestConfig.PACKAGE_SAMPLE_COUNT, TestConfig.INIT_TIMEOUT_MS, TestConfig.POWER_REFRESH_MS)

/** CompletableFuture 取值：超时/异常一律返回 null（对应 Instrumentation 侧 getOrNull）。 */
private fun <T> CompletableFuture<T>.awaitOrNull(timeoutMs: Long): T? =
    runCatching { get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()

private fun isError(ret: String): Boolean = ret.startsWith("Error")

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

            // 失败场景（无效 MAC）：返回值 false 或 Future 异常完成均视为失败
            val invalid = controller.requireSensor("00:00:00:00:00:00")
            if (invalid == null) {
                log("requireSensor(无效 MAC) 返回 null，无法构造无效设备，跳过失败场景断言")
            } else {
                val errors = CopyOnWriteArrayList<String>()
                invalid.setOnErrorListener { _, errorMsg ->
                    errors.add(errorMsg)
                    log("  onError: $errorMsg")
                }
                val fail = runCatching { invalid.connectAsync().awaitOrNull(TestConfig.READY_TIMEOUT_MS) }
                fail.exceptionOrNull()?.let {
                    log("connectAsync 抛异常（Future 未包装）: ${it.javaClass.simpleName}: ${it.message.orEmpty()}")
                }
                val failOk = fail.getOrNull()
                check(failOk != true, "对无效设备 connectAsync 应返回 False/异常，实际=$failOk")
                log("无效 MAC connectAsync -> $failOk errors=$errors")
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

            val retOn = p.setParam(key, "ON")
            check(!isError(retOn), "setParam($key, ON) 应成功，实际=$retOn")
            val vOn = p.getParam(key)
            check(vOn.contains("ON"), "getParam($key) 应含 ON，实际=$vOn")

            p.setParam(key, "OFF")
            val vOff = p.getParam(key)
            check(vOff.contains("OFF"), "getParam($key) 应含 OFF，实际=$vOff")

            p.disconnect()
            "key=$key on=$retOn off=$vOff"
        },
        auto("ASYNC-FUNC-006", "suspend getParam 两次结果一致", chapter, Priority.P0) {
            val spec = TestConfig.targetSpec()
            val p = connected()
            val key = switchableStreamKey(p, spec)
                ?: skip("设备 NTF 列表中无可切换的 spec 支持流")

            val v1 = p.getParam(key)
            val v2 = p.getParam(key)
            check(!isError(v1), "getParam($key) 不应 Error，实际=$v1")
            check(v1 == v2, "两次 getParam($key) 应一致（$v1 != $v2）")

            p.disconnect()
            "key=$key value=$v1"
        },
        auto("ASYNC-FUNC-007", "suspend getBatteryLevel 返回 0~100/-1", chapter, Priority.P1) {
            val p = connected()
            val level = p.getBatteryLevel()
            log("getBatteryLevel() = $level")
            check(level == -1 || level in 0..100, "电量应在 0..100 或 -1，实际=$level")

            p.disconnect()
            "battery=$level"
        },
        auto("ASYNC-FUNC-008", "suspend startDataNotification 起流", chapter, Priority.P0) {
            val p = connected()
            val ok = p.startDataNotification()
            check(ok, "startDataNotification 应返回 True")
            check(p.isDataTransfering(), "起流后 isDataTransfering 应为 True")

            p.stopDataNotification()
            p.disconnect()
            "start=true"
        },
        auto("ASYNC-FUNC-009", "suspend stopDataNotification 停流", chapter, Priority.P0) {
            val p = connected()
            check(p.startDataNotification(), "startDataNotification 失败")

            val ok = p.stopDataNotification()
            check(ok, "stopDataNotification 应返回 True")
            check(!p.isDataTransfering(), "停流后 isDataTransfering 应为 False")

            p.disconnect()
            "stop=true"
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

            // suspend 起流（轮询确认进入传输态，不用固定 sleep）
            check(p.startDataNotification(), "startDataNotification 失败")
            waitFor({ p.isDataTransfering() }, 5_000, "起流后 isDataTransfering")

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
            "mixed async/sync OK"
        },
        auto("ASYNC-FUNC-011", "multiStartDataNotification 同步起流（≥2 台）", chapter,
            Priority.P1, ASYNC_MULTI_TIMEOUT_MS) {
            val profiles = readyProfiles(2)
            val (results, errors) = controller.multiStartDataNotification(
                profiles, TestConfig.INIT_TIMEOUT_MS, 5, 3,
            )
            log("multiStartDataNotification -> $results errors=$errors")
            profiles.forEach { p ->
                val value = resultOf(results, p.device.mac)
                check(value == true, "[${p.device.mac}] multiStart 应为 True，实际=$value")
            }
            check(
                profiles.all { it.isDataTransfering() },
                "起流后各台 isDataTransfering 应为 True",
            )

            controller.multiStopDataNotification(profiles, TestConfig.SCAN_TIMEOUT_MS)
            profiles.forEach { it.disconnect() }
            "results=$results"
        },
        auto("ASYNC-FUNC-012", "multiStopDataNotification 同步停流（≥2 台）", chapter,
            Priority.P1, ASYNC_MULTI_TIMEOUT_MS) {
            val profiles = readyProfiles(2)
            controller.multiStartDataNotification(profiles, TestConfig.INIT_TIMEOUT_MS, 5, 3)

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
        auto("ASYNC-FUNC-015", "SensorAlgo destroy 后可重新 init", chapter, Priority.P1) {
            val algo = SensorAlgo()
            try {
                check(algo.safeInit(), "首次 init 应返回 True")
                algo.destroy()
                check(!algo.hasInit(), "destroy 后 hasInit 应为 false")
                val again = algo.safeInit()
                check(again, "destroy 后重新 init 应返回 True，实际=$again")
                check(algo.hasInit(), "重新 init 后 hasInit 应为 true")
                "destroy -> init OK"
            } finally {
                runCatching { algo.destroy() }
            }
        },
    )
}
