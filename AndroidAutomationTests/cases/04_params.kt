package com.oymotion.sensorcapiktdemo.automation

import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import com.oymotion.sensorcapiktdemo.cerelaxpro.CerelaxSpec
import com.oymotion.sensorcapiktdemo.devicespecs.DeviceSpec
import com.oymotion.sensorcapiktdemo.devicespecs.SampleRateSpec
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentLinkedQueue

private const val CHAPTER = "04_设备参数"

/**
 * 04 参数章节（PARAM-*），与 Instrumentation 侧
 * `Test Cases/Automation/Generalized/04_Parameters/CerelaxParamTest.kt` 逐条对应。
 *
 * 型号差异读 spec（[TestConfig.targetSpec]）：流/滤波/采样率能力取自 streams、filters、
 * sampleRates（rates/default/settable/coStream）；实际可切换的开关以设备 getParam("NTF"/"FILTER")
 * 返回的列表为准（见 PARAM-FUNC-001/002），不硬编码任何型号专属参数 key。
 */

/** 与 Instrumentation 侧 isError 一致：以 "Error" 开头即失败/不支持。 */
private fun isError(s: String) = s.startsWith("Error")

/** 解析 `NTF_EEG|ON|NTF_IMU|OFF|...` 形式的聚合开关列表。 */
private fun parseSwitches(raw: String): List<Pair<String, String>> =
    Regex("([A-Z0-9_]+)\\|(ON|OFF)").findAll(raw)
        .map { it.groupValues[1] to it.groupValues[2] }
        .toList()

/**
 * spec 中第一个可设置的采样率参数（所属流受支持）。
 * 单档位设备（如 OB6000C 的 EEG 固定 250）没有其它档可切，但「起流中改参」仍需覆盖，
 * 故不要求存在默认档之外的档位；由用例决定切档还是同值重设。
 */
private fun settableRate(spec: DeviceSpec): Map.Entry<String, SampleRateSpec>? =
    spec.sampleRates.entries.firstOrNull { (_, sr) ->
        sr.settable && (sr.stream.isEmpty() || spec.supports(sr.stream))
    }

/** 优先取可设置的采样率参数，退回第一个已注册的。 */
private fun primaryRate(spec: DeviceSpec): Map.Entry<String, SampleRateSpec>? =
    spec.sampleRates.entries.firstOrNull { it.value.settable } ?: spec.sampleRates.entries.firstOrNull()

/**
 * 已知现象（非硬约束）：EMG 高采样率与手势流同时开启可能争抢算力。
 * 仅当 spec 同时支持 NTF_EMG / NTF_GEST 且待测参数是 EMG_SAMPLE_RATE 时先关 GEST，
 * 返回原值供用例结束后恢复；不把该现象写成硬断言。
 */
private suspend fun TestContext.turnGestOffForEmgRate(
    p: SensorProfile,
    spec: DeviceSpec,
    rateKey: String,
): String? {
    if (rateKey != "EMG_SAMPLE_RATE") return null
    if (rateKey !in spec.sampleRates) return null
    if (!spec.supports("NTF_EMG") || !spec.supports("NTF_GEST")) return null
    val before = p.getParam("NTF_GEST")
    if (isError(before)) return null
    val ret = p.setParam("NTF_GEST", "OFF")
    log("测 EMG_SAMPLE_RATE 前关闭 NTF_GEST：$ret（原值=$before）")
    return before
}

private suspend fun restoreGest(p: SensorProfile, before: String?) {
    if (before == null) return
    runCatching { p.setParam("NTF_GEST", if (before.contains("OFF")) "OFF" else "ON") }
}

/**
 * 持续推送 [seconds] 秒真实 EEG 批次，统计 `getResult("EEG")` 出现过的不同 STEP 数量
 * （用于验证算法 EEG_REFRESH_RATE 对输出频率的影响）。
 */
private suspend fun TestContext.countRefreshSteps(algo: SensorAlgo, seconds: Long): Int {
    val p = connected()
    val onRet = try {
        p.setParam("NTF_EEG", "ON")
    } catch (e: Exception) {
        "Error: ${e.message}"
    }
    check(!algoError(onRet), "NTF_EEG=ON 被拒绝（$onRet）")

    val queue = ConcurrentLinkedQueue<SensorData>()
    p.setOnDataListener { _, list ->
        list.filter { it.dataType == DataType.NTF_EEG }.forEach { queue.offer(it.clone()) }
    }
    check(p.startDataNotification(), "startDataNotification 失败")

    val steps = LinkedHashSet<Int>()
    var rateSet = false
    val deadline = System.currentTimeMillis() + seconds * 1000
    try {
        while (System.currentTimeMillis() < deadline) {
            val batch = ArrayList<SensorData>()
            while (true) {
                batch += queue.poll() ?: break
            }
            if (batch.isNotEmpty()) {
                if (!rateSet) {
                    algo.syncEegSampleRate(batch)
                    rateSet = true
                }
                algo.safePush(batch.toTypedArray())
            }
            algoRows(algo.safeResult("EEG")).forEach { row -> row[0].toIntOrNull()?.let { steps.add(it) } }
            delay(100)
        }
    } finally {
        runCatching { p.stopDataNotification() }
    }
    return steps.size
}

val paramCases = listOf(
    // ---- PARAM-BND：参数边界 ----------------------------------------------------------
    auto("PARAM-BND-001", "setParam 非法 value 被拒绝，不崩溃", CHAPTER, Priority.P0) {
        val spec = TestConfig.targetSpec()
        val p = connected()

        // 代表性键：核心流开关 + 滤波开关（按 spec 能力选取）
        val keys = mutableListOf<String>()
        spec.supportedStreams.take(3).forEach { keys.add(CerelaxSpec.paramKey(it)) }
        spec.filters.filterValues { it }.keys.take(2).forEach { keys.add(it) }
        check(keys.isNotEmpty(), "spec 未注册可测的流/滤波参数")

        val illegalValues = listOf("", "MAYBE", "on", "On", "1", "TRUE")
        var probes = 0
        for (key in keys) {
            for (v in illegalValues) {
                var ret = ""
                val rejected = failureResult { p.setParam(key, v).also { ret = it } }
                log("setParam($key, '$v') -> $ret")
                check(rejected, "setParam($key, '$v') 应报错，实际=$ret")
                probes++
            }
        }
        "keys=${keys.size} probes=$probes"
    },
    auto("PARAM-BND-002", "setParam/getParam 空串 key 被拒绝", CHAPTER) {
        val p = connected()
        check(failureResult { p.setParam("", "true") }, "setParam(空串) 应返回 Error")
        check(failureResult { p.getParam("") }, "getParam(空串) 应返回 Error")
        "empty key rejected"
    },
    auto("PARAM-BND-003", "EEG/IMU/PPG 采样率 6 点边界", CHAPTER, Priority.P1, 180_000) {
        val spec = TestConfig.targetSpec()
        val settable = spec.sampleRates.filterValues { it.settable }
        if (settable.isEmpty()) skip("spec 未登记可设置的采样率参数")

        val p = connected()
        val details = mutableListOf<String>()
        for ((key, sr) in settable) {
            val nums = sr.rates.mapNotNull { it.toIntOrNull() }.sorted()
            if (nums.isEmpty()) {
                log("$key 档位非数字（${sr.rates}），跳过数值边界")
                continue
            }
            val min = nums.first()
            val max = nums.last()
            val mid = nums[nums.size / 2]

            val current = safeGetParam(p, key)
            val original = sr.rates.firstOrNull { current.contains(it) } ?: sr.default
            var lastLegal = original

            // 合法：min / mid / max
            for (v in listOf(min, mid, max)) {
                val ret = safeSetParam(p, key, v.toString())
                check(!isError(ret), "合法值 $key=$v 应成功，实际=$ret")
                val back = safeGetParam(p, key)
                check(back.contains(v.toString()), "$key=$v 读回应一致，实际=$back")
                lastLegal = v.toString()
            }
            // 越界：min-1 / max+1 / 空串
            for (v in listOf((min - 1).toString(), (max + 1).toString(), "")) {
                val ret = safeSetParam(p, key, v)
                check(isError(ret), "越界值 $key='$v' 应返回 Error，实际=$ret")
                val back = safeGetParam(p, key)
                check(back.contains(lastLegal), "越界值不应改变 $key（应仍为 $lastLegal，实际=$back）")
            }

            safeSetParam(p, key, original)
            details += "$key ${min}/${mid}/${max} 边界 OK"
        }
        details.joinToString("; ")
    },
    auto("PARAM-BND-004", "算法 EEG_SAMPLE_RATE 非法值被拒绝", CHAPTER, Priority.P1, 60_000) {
        val algo = SensorAlgo()
        check(algo.safeInit(), "SensorAlgo.init 返回 false")
        try {
            val valid = "1000"
            val setValid = algo.safeSetParam("EEG_SAMPLE_RATE", valid)
            check(!algoError(setValid), "setParam(EEG_SAMPLE_RATE, $valid) 应成功，实际=$setValid")

            for (bad in listOf("0", "999", "", "abc")) {
                val ret = algo.safeSetParam("EEG_SAMPLE_RATE", bad)
                check(algoError(ret), "setParam(EEG_SAMPLE_RATE, '$bad') 应被拒绝，实际=$ret")
                val read = algo.safeGetParam("EEG_SAMPLE_RATE")
                check(!algoError(read), "非法值后 getParam(EEG_SAMPLE_RATE) 不应报错: $read")
                check(
                    read.contains(valid),
                    "非法值 '$bad' 不应改变当前采样率（应仍为 $valid，实际=$read）",
                )
            }
            "invalid values rejected, kept $valid"
        } finally {
            runCatching { algo.destroy() }
        }
    },
    auto("PARAM-BND-005", "算法 EEG_REFRESH_RATE 非法值被拒绝", CHAPTER, Priority.P2, 60_000) {
        val algo = SensorAlgo()
        check(algo.safeInit(), "SensorAlgo.init 返回 false")
        try {
            val setValid = algo.safeSetParam("EEG_REFRESH_RATE", "1")
            log("setParam(EEG_REFRESH_RATE, 1) -> $setValid")
            if (algoError(setValid) && setValid.contains("Not supported", ignoreCase = true)) {
                // 参数在当前 AAR 未实现：非法值也只会返回 Not supported，校验没有区分度，
                // 与 PARAM-FUNC-016 同口径记 SKIP，避免空过成“通过”。
                skip("当前 SDK 算法不支持 EEG_REFRESH_RATE（$setValid），非法值校验无意义")
            }
            for (bad in listOf("-1", "9999", "")) {
                val ret = algo.safeSetParam("EEG_REFRESH_RATE", bad)
                check(algoError(ret), "setParam(EEG_REFRESH_RATE, '$bad') 应被拒绝，实际=$ret")
            }
            val read = algo.safeGetParam("EEG_REFRESH_RATE")
            log("非法值后 getParam(EEG_REFRESH_RATE) -> $read")
            if (!algoError(read)) {
                check(read.contains("1"), "非法值不应改变 EEG_REFRESH_RATE（应仍为 1，实际=$read）")
            }
            "invalid values rejected"
        } finally {
            runCatching { algo.destroy() }
        }
    },
    auto("PARAM-BND-006", "算法未知 key 返回错误", CHAPTER, Priority.P1, 60_000) {
        val algo = SensorAlgo()
        check(algo.safeInit(), "SensorAlgo.init 返回 false")
        try {
            val set = algo.safeSetParam("UNKNOWN_KEY", "X")
            val get = algo.safeGetParam("UNKNOWN_KEY")
            val result = algo.safeResult("UNKNOWN")
            check(algoError(set), "setParam(未知 key) 应返回错误，实际=$set")
            check(algoError(get), "getParam(未知 key) 应返回错误，实际=$get")
            check(algoError(result), "getResult(未知类型) 应返回错误，实际=$result")
            "unknown key/result rejected"
        } finally {
            runCatching { algo.destroy() }
        }
    },

    // ---- PARAM-FUNC：参数功能 ---------------------------------------------------------
    auto("PARAM-FUNC-001", "流开关 ON/OFF 回读一致，无独立开关/不支持流被拒绝", CHAPTER, Priority.P0) {
        val spec = TestConfig.targetSpec()
        val p = connected()

        // 以设备 getParam("NTF") 为权威开关列表：spec 的 ChannelCount>0 不代表有独立开关
        val ntf = safeGetParam(p, "NTF")
        check(!isError(ntf) && ntf.contains("|"), "getParam(NTF) 应返回开关列表，实际=$ntf")
        val switches = parseSwitches(ntf)
        check(switches.isNotEmpty(), "无法从 NTF 解析流开关: $ntf")
        log("NTF 开关: $switches")

        var toggled = 0
        val skipped = mutableListOf<String>()
        for ((key, orig) in switches) {
            val flip = if (orig == "ON") "OFF" else "ON"
            val ret = safeSetParam(p, key, flip)
            if (isError(ret)) {
                // 设备在 NTF 列表里列出但拒绝单独设置（固件能力不一致），记录后跳过
                skipped += "$key(${ret.trim()})"
                continue
            }
            val back = safeGetParam(p, "NTF")
            check(back.contains("$key|$flip"), "$key 切到 $flip 后 getParam(NTF) 应体现，实际=$back")
            val restore = safeSetParam(p, key, orig)
            check(!isError(restore), "$key 还原 $orig 应成功，实际=$restore")
            toggled++
        }

        // spec 支持但不在 NTF 开关列表中的流（如 IMU 子流 ACC/GYRO）：可能无独立开关，
        // 不同型号行为不同（有的可以从聚合流拆开单独查），只记录不硬断言
        val switchKeys = switches.map { it.first }.toSet()
        val noSwitch = spec.supportedStreams.map { CerelaxSpec.paramKey(it) }
            .filter { it !in switchKeys }
        val independent = noSwitch.mapNotNull { key ->
            val v = safeGetParam(p, key)
            if (isError(v)) null else "$key=$v"
        }

        // spec 判定不支持的流：设置应被拒绝
        for (key in spec.unsupportedStreams) {
            val paramKey = CerelaxSpec.paramKey(key)
            check(failureResult { p.setParam(paramKey, "ON") }, "不支持流 $paramKey，setParam 应报错")
        }

        check(toggled > 0 || independent.isNotEmpty(), "没有任何可验证的流开关")
        "toggled=$toggled skipped=${skipped.size} independent=$independent"
    },
    auto("PARAM-FUNC-002", "滤波参数 ON/OFF 回读一致", CHAPTER) {
        val p = connected()

        // 以 getParam("FILTER") 聚合列表为准：单键 getParam 可能返回 Not supported（实测 FILTER_50HZ）
        val agg = safeGetParam(p, "FILTER")
        if (isError(agg) || !agg.contains("|")) {
            skip("设备无 FILTER 聚合开关（$agg），spec 声明与实际能力不一致，记为 SKIP")
        }
        val entries = parseSwitches(agg)
        if (entries.isEmpty()) skip("FILTER 列表无法解析: $agg")
        log("FILTER 开关: $entries")

        val failed = mutableListOf<String>()
        for ((key, orig) in entries) {
            val flip = if (orig == "ON") "OFF" else "ON"
            val a = safeSetParam(p, key, flip)
            val back = safeGetParam(p, "FILTER")
            val b = safeSetParam(p, key, orig)
            val ok = a == "OK" && back.contains("$key|$flip") && b == "OK"
            if (!ok) failed += "$key(set=$a readback=$back restore=$b)"
        }
        check(failed.isEmpty(), "以下滤波开关失败: ${failed.joinToString(" | ")}")
        "filters=${entries.size}"
    },
    auto("PARAM-FUNC-003", "NTF_IMU 聚合流开关与子流联动观察", CHAPTER,
        requires = setOf("NTF_IMU")) {
        val spec = TestConfig.targetSpec()
        check(spec.supports("NTF_IMU"), "spec 判定 NTF_IMU 不支持")
        val p = connected()

        val retOn = safeSetParam(p, "NTF_IMU", "ON")
        check(!isError(retOn), "setParam(NTF_IMU, ON) 应成功，实际=$retOn")
        val vImu = safeGetParam(p, "NTF_IMU")
        check(vImu.contains("ON"), "getParam(NTF_IMU) 应含 ON，实际=$vImu")

        // 子流联动以实测为准（聚合流差异），仅记录观察；单键 getParam 可能抛 Not supported
        for (key in spec.streams.keys.filter { it.startsWith("NTF_GFORCE_") }) {
            log("NTF_IMU ON 后 getParam($key) = ${safeGetParam(p, key)}")
        }

        safeSetParam(p, "NTF_IMU", "OFF")
        "NTF_IMU ON/OFF OK"
    },
    auto("PARAM-FUNC-004", "spec 采样率参数默认值/列表/逐一设置回读", CHAPTER, Priority.P0,
        timeoutMs = 180_000) {
        val spec = TestConfig.targetSpec()
        check(spec.sampleRates.isNotEmpty(), "spec 应注册采样率参数")
        val p = connected()

        val gestBefore = turnGestOffForEmgRate(p, spec, "EMG_SAMPLE_RATE")
        try {
            for ((key, sr) in spec.sampleRates) {
                val cur = safeGetParam(p, key)

                if (!sr.settable) {
                    // 不可设置：只验证回读为固定值（或设备无该采样率参数）
                    sr.coStream?.let { log("$key coStream=$it（随 $it 起停）") }
                    if (isError(cur)) {
                        log("$key 无采样率参数（$cur），符合 spec settable=false")
                    } else {
                        check(cur.contains(sr.default), "$key 固定值应为 ${sr.default}，实际=$cur")
                    }
                    continue
                }

                // 不假设上电默认值：设备状态可能被历史用例/上一轮修改，按当前值做回环校验
                check(!isError(cur), "getParam($key) 不应 Error，实际=$cur")
                val current = sr.rates.firstOrNull { cur.contains(it) } ?: cur.trim()
                if (current != sr.default) {
                    log("$key 当前=$current（spec 默认=${sr.default}，可能被历史用例修改，按当前值回环校验）")
                }

                // 可选列表
                val list = safeGetParam(p, "${key}_LIST")
                if (isError(list) || list.isBlank()) {
                    log("${key}_LIST 不可用（$list），跳过列表校验")
                } else {
                    for (r in sr.rates) {
                        check(list.contains(r), "${key}_LIST 应含 $r，实际=$list")
                    }
                }

                // 逐档位设置读回
                for (r in sr.rates) {
                    val ret = safeSetParam(p, key, r)
                    check(!isError(ret), "setParam($key, $r) 应成功，实际=$ret")
                    val readBack = safeGetParam(p, key)
                    check(readBack.contains(r), "$key=$r 读回应一致，实际=$readBack")
                }

                // 还原用例前的值，避免污染后续用例
                safeSetParam(p, key, current)
            }
        } finally {
            restoreGest(p, gestBefore)
        }
        "rates=${spec.sampleRates.size}"
    },
    auto("PARAM-FUNC-005", "spec 采样率非法值（列表外/空串）被拒绝", CHAPTER) {
        val spec = TestConfig.targetSpec()
        val settable = spec.sampleRates.filterValues { it.settable }
        if (settable.isEmpty()) skip("spec 无 settable 采样率参数")
        val p = connected()

        for ((key, sr) in settable) {
            // 列表外值：取一个明显不在 rates 里的值
            val outside = listOf("750", "749", "1234").first { it !in sr.rates }
            check(failureResult { p.setParam(key, outside) },
                "setParam($key, $outside) 应返回 Error")
            check(failureResult { p.setParam(key, "") },
                "setParam($key, 空串) 应返回 Error")
        }
        "checked=${settable.size}"
    },
    auto("PARAM-FUNC-006", "spec 采样率参数默认值与可选列表查询", CHAPTER) {
        val spec = TestConfig.targetSpec()
        val settable = spec.sampleRates.filterValues { it.settable }
        if (settable.isEmpty()) skip("spec 无 settable 采样率参数")
        val p = connected()

        var listed = 0
        for ((key, sr) in settable) {
            val current = safeGetParam(p, key)
            check(!isError(current), "$key 查询不应 Error，实际=$current")
            // 只校验当前值属于合法档位；上电默认值可能被历史用例/上一轮修改，不作为硬条件
            check(
                sr.rates.any { current.contains(it) },
                "$key 当前值应属于可选档位 ${sr.rates}，实际=$current",
            )
            if (!current.contains(sr.default)) {
                log("$key 当前=$current（spec 默认=${sr.default}，可能被历史用例修改）")
            }

            val list = safeGetParam(p, "${key}_LIST")
            if (isError(list) || list.isBlank()) {
                log("${key}_LIST 查询返回 $list，跳过列表校验")
                continue
            }
            for (r in sr.rates) {
                check(list.contains(r), "${key}_LIST 应含 $r，实际=$list")
            }
            listed++
        }
        "keys=${settable.size} listed=$listed"
    },
    auto("PARAM-FUNC-007", "起流中修改采样率生效（读回一致）", CHAPTER, Priority.P0) {
        val spec = TestConfig.targetSpec()
        val entry = settableRate(spec) ?: run { skip("spec 无 settable 采样率参数") }
        val (key, sr) = entry
        val p = connected()

        // 记录原值并还原原值（不要写死 spec 默认，避免覆盖设备既有状态、污染后续用例）
        val originalRaw = safeGetParam(p, key)
        val original = sr.rates.firstOrNull { originalRaw.contains(it) } ?: sr.default
        // 有其它档则切到不同档（强校验）；单档位设备只能同值重设，另翻转 FILTER 验证起流中改参生效
        val target = sr.rates.firstOrNull { it != original } ?: original
        val switched = target != original
        var filterRestore: Pair<String, String>? = null

        val gestBefore = turnGestOffForEmgRate(p, spec, key)
        try {
            check(p.startDataNotification(), "startDataNotification 失败")
            check(p.isDataTransfering(), "起流后 isDataTransfering 应为 true")

            val ret = safeSetParam(p, key, target)
            check(!isError(ret), "起流中改 $key 应成功，实际=$ret")

            delay(3_000)
            val readBack = safeGetParam(p, key)
            check(readBack.contains(target), "改参后 $key 应为 $target，实际=$readBack")

            if (!switched) {
                // 单档位设备同值重设无法证明「新状态生效」，按手工用例口径在起流中翻转一个 FILTER_*。
                val agg = safeGetParam(p, "FILTER")
                val entries = if (isError(agg) || !agg.contains("|")) emptyList() else parseSwitches(agg)
                val filterKey = entries.map { it.first }.firstOrNull { k -> spec.filters[k] == true }
                if (filterKey == null) {
                    log("单档位设备且无可解析 FILTER_*，仅验证起流中同值重设 $key=$target 的读回")
                } else {
                    val orig = entries.first { it.first == filterKey }.second
                    val flip = if (orig == "ON") "OFF" else "ON"
                    val fRet = safeSetParam(p, filterKey, flip)
                    check(!isError(fRet), "起流中改 $filterKey 应成功，实际=$fRet")
                    filterRestore = filterKey to orig
                    delay(1_000)
                    val aggAfter = safeGetParam(p, "FILTER")
                    check(
                        aggAfter.contains("$filterKey|$flip"),
                        "起流中 $filterKey 切到 $flip 后 getParam(FILTER) 应体现，实际=$aggAfter",
                    )
                    log("单档位设备：起流中 $key 同值重设 + $filterKey $orig->$flip 已生效")
                }
            }

            safeSetParam(p, key, original)
        } finally {
            filterRestore?.let { (k, v) -> safeSetParam(p, k, v) }
            runCatching { if (p.isDataTransfering()) p.stopDataNotification() }
            restoreGest(p, gestBefore)
        }
        if (switched) "$key=$target" else "$key=$target(单档同值重设)+filter"
    },
    auto("PARAM-FUNC-008", "未知 key 返回 Error", CHAPTER) {
        val p = connected()
        var ret = ""
        val rejected = failureResult { p.setParam("UNKNOWN_KEY", "ON").also { ret = it } }
        check(rejected, "未知 key 应返回 Error，实际=$ret")
        "unknown key rejected"
    },
    manual("PARAM-FUNC-009", "NEUCIR_* 参数（非 NeuCir 设备不适用）", CHAPTER, Priority.P2,
        steps = listOf(
            "Android 不适用：待测设备非 NeuCir 设备，NEUCIR_* 参数不适用（Instrumentation 侧 @Ignore）。",
        )),
    auto("PARAM-FUNC-010", "DEBUG_BLE_DATA_PATH / DEBUG_LOG_PATH 读写", CHAPTER) {
        val p = connected()

        var supported = 0
        for (key in listOf("DEBUG_BLE_DATA_PATH", "DEBUG_LOG_PATH")) {
            val ret = p.setParam(key, "true")
            if (isError(ret)) {
                log("$key 不支持/不适用（$ret），跳过")
                continue
            }
            val read = p.getParam(key)
            log("$key -> set=$ret get=$read")
            check(!isError(read), "getParam($key) 应非 Error，实际=$read")
            p.setParam(key, "false")
            supported++
        }
        if (supported == 0) skip("DEBUG_BLE_DATA_PATH / DEBUG_LOG_PATH 均不支持，无法验证读写")
        "debug keys=$supported"
    },
    auto("PARAM-FUNC-011", "spec 采样率在 DeviceInfo 的只读字段合法", CHAPTER) {
        val spec = TestConfig.targetSpec()
        check(spec.sampleRates.isNotEmpty(), "spec 应注册采样率参数")
        val p = connected()
        val info = p.getDeviceInfo() ?: throw AssertionError("getDeviceInfo 不应为 null")

        for ((key, sr) in spec.sampleRates) {
            if (sr.stream.isEmpty()) continue
            if (!spec.supports(sr.stream)) {
                log("$key -> 流 ${sr.stream} 不支持，跳过")
                continue
            }
            val rate = CerelaxSpec.sampleRate(info, sr.stream)
            log("$key -> DeviceInfo(${sr.stream}).sampleRate=$rate")
            check(rate > 0.0, "$key 只读采样率应 > 0，实际=$rate")
        }
        "readonly fields OK"
    },
    auto("PARAM-ROB-001", "同一采样率参数重复 setParam 幂等", CHAPTER, Priority.P2) {
        val spec = TestConfig.targetSpec()
        val entry = primaryRate(spec) ?: run { skip("spec 无采样率参数") }
        val (key, sr) = entry
        val p = connected()
        // 用当前值做幂等校验（不写死默认值，避免改变设备既有状态）
        val raw = safeGetParam(p, key)
        val value = sr.rates.firstOrNull { raw.contains(it) } ?: sr.default

        repeat(3) { i ->
            val ret = safeSetParam(p, key, value)
            check(!isError(ret), "第 ${i + 1} 次 setParam($key, $value) 应成功，实际=$ret")
            val read = safeGetParam(p, key)
            check(read.contains(value), "第 ${i + 1} 次 $key 读回应含 $value，实际=$read")
        }
        "$key=$value x3"
    },

    // ---- SDK 未实现，Instrumentation 侧 @Ignore ---------------------------------------
    auto("PARAM-FUNC-012", "IMU_SAMPLE_RATE 设置（正向 + 反向）", CHAPTER, Priority.P1, 120_000) {
        val spec = TestConfig.targetSpec()
        val key = "IMU_SAMPLE_RATE"
        val sr = spec.sampleRates[key]?.takeIf { it.settable }
            ?: skip("spec 未登记可设置的 $key")
        val p = connected()

        val current = safeGetParam(p, key)
        check(!isError(current), "getParam($key) 不应报错，实际=$current")
        val original = sr.rates.firstOrNull { current.contains(it) } ?: sr.default
        var lastLegal = original

        for (r in sr.rates) {
            val ret = safeSetParam(p, key, r)
            check(!isError(ret), "setParam($key, $r) 应成功，实际=$ret")
            val back = safeGetParam(p, key)
            check(back.contains(r), "$key=$r 读回应一致，实际=$back")
            lastLegal = r
        }
        // 反向：列表外值（400 不是 IMU 档位）与空串
        for (bad in listOf("400", "")) {
            val ret = safeSetParam(p, key, bad)
            check(isError(ret), "setParam($key, '$bad') 应返回 Error，实际=$ret")
            val back = safeGetParam(p, key)
            check(back.contains(lastLegal), "非法值不应改变 $key（应仍为 $lastLegal，实际=$back）")
        }

        safeSetParam(p, key, original)
        "rates=${sr.rates} original=$original"
    },
    auto("PARAM-FUNC-013", "PPG_SAMPLE_RATE 设置（正向 + 反向）", CHAPTER, Priority.P1, 120_000) {
        val spec = TestConfig.targetSpec()
        val key = "PPG_SAMPLE_RATE"
        val sr = spec.sampleRates[key]?.takeIf { it.settable }
            ?: skip("spec 未登记可设置的 $key")
        val p = connected()

        val current = safeGetParam(p, key)
        check(!isError(current), "getParam($key) 不应报错，实际=$current")
        val original = sr.rates.firstOrNull { current.contains(it) } ?: sr.default
        var lastLegal = original

        for (r in sr.rates) {
            val ret = safeSetParam(p, key, r)
            check(!isError(ret), "setParam($key, $r) 应成功，实际=$ret")
            val back = safeGetParam(p, key)
            check(back.contains(r), "$key=$r 读回应一致，实际=$back")
            lastLegal = r
        }
        // 反向：列表外值（1000 不是 PPG 档位）与空串
        for (bad in listOf("1000", "")) {
            val ret = safeSetParam(p, key, bad)
            check(isError(ret), "setParam($key, '$bad') 应返回 Error，实际=$ret")
            val back = safeGetParam(p, key)
            check(back.contains(lastLegal), "非法值不应改变 $key（应仍为 $lastLegal，实际=$back）")
        }

        safeSetParam(p, key, original)
        "rates=${sr.rates} original=$original"
    },

    // ---- SensorAlgo：算法参数 ----------------------------------------------------------
    auto("PARAM-FUNC-014", "算法 EEG_SAMPLE_RATE 三档可设置并回读", CHAPTER, Priority.P0, 60_000) {
        val algo = SensorAlgo()
        check(algo.safeInit(), "SensorAlgo.init 返回 false")
        try {
            for (v in listOf("250", "500", "1000")) {
                val ret = algo.safeSetParam("EEG_SAMPLE_RATE", v)
                check(!algoError(ret), "setParam(EEG_SAMPLE_RATE, $v) 应成功，实际=$ret")
                check(ret.contains("OK", ignoreCase = true), "setParam(EEG_SAMPLE_RATE, $v) 应返回 OK，实际=$ret")
                val read = algo.safeGetParam("EEG_SAMPLE_RATE")
                check(!algoError(read), "getParam(EEG_SAMPLE_RATE) 报错: $read")
                check(read.contains(v), "EEG_SAMPLE_RATE 回读应为 $v，实际=$read")
            }
            "250/500/1000 OK"
        } finally {
            runCatching { algo.destroy() }
        }
    },
    auto("PARAM-FUNC-015", "算法 CLONE 开关生效", CHAPTER, Priority.P1, 120_000,
        requires = setOf("NTF_EEG")) {
        val samples = collectAlgoBatches("NTF_EEG", 4_000, 4_000)
        val algo = SensorAlgo()
        check(algo.safeInit(), "SensorAlgo.init 返回 false")
        try {
            algo.syncEegSampleRate(samples)

            val cloneOn = algo.safeSetParam("CLONE", "True")
            check(!algoError(cloneOn), "setParam(CLONE, True) 应成功，实际=$cloneOn")
            check(algo.safePush(samples.toTypedArray()), "CLONE=True pushSamples 返回 false")
            val onResult = algo.safeResult("EEG")
            check(!algoError(onResult), "CLONE=True getResult 报错: $onResult")
            check(algoRows(onResult).isNotEmpty(), "CLONE=True 应能读到结果行: $onResult")

            val cloneOff = algo.safeSetParam("CLONE", "False")
            check(!algoError(cloneOff), "setParam(CLONE, False) 应成功，实际=$cloneOff")
            check(algo.safePush(samples.toTypedArray()), "CLONE=False pushSamples 返回 false")
            // CLONE=False 只要求「不越界、不崩溃」，结果是否保留由 SDK 语义决定，仅记录
            val offResult = algo.safeResult("EEG")
            log("CLONE=False getResult -> ${offResult.trim().take(80)}")
            "clone=True rows=${algoRows(onResult).size} clone=False='${offResult.trim().take(40)}'"
        } finally {
            runCatching { algo.destroy() }
        }
    },
    auto("PARAM-FUNC-016", "算法 EEG_REFRESH_RATE 控制输出频率", CHAPTER, Priority.P1, 180_000,
        requires = setOf("NTF_EEG")) {
        val algo = SensorAlgo()
        check(algo.safeInit(), "SensorAlgo.init 返回 false")
        try {
            val setOne = algo.safeSetParam("EEG_REFRESH_RATE", "1")
            if (algoError(setOne)) {
                // 实测当前 AAR 的 SensorAlgo 未实现该参数（setParam -> "Error: Not supported"）。
                // 按“能力不支持记 SKIP、其它错误判 FAIL”的口径处理，避免把 SDK 缺能力误报成用例失败。
                if (setOne.contains("Not supported", ignoreCase = true)) {
                    skip("当前 SDK 算法不支持 EEG_REFRESH_RATE（$setOne），无法验证刷新频率")
                }
                check(false, "setParam(EEG_REFRESH_RATE, 1) 应成功，实际=$setOne")
            }
            val one = countRefreshSteps(algo, 5)

            algo.reset()
            val setZero = algo.safeSetParam("EEG_REFRESH_RATE", "0")
            check(!algoError(setZero), "setParam(EEG_REFRESH_RATE, 0) 应成功，实际=$setZero")
            val zero = countRefreshSteps(algo, 5)

            check(one in 1..15, "EEG_REFRESH_RATE=1 时 5s 内应约每秒一行（实际 STEP 数=$one）")
            check(zero >= 1, "EEG_REFRESH_RATE=0 时 5s 内应至少产出 1 行（实际=$zero）")
            "refresh=1 -> $one 行/5s; refresh=0 -> $zero 行/5s"
        } finally {
            runCatching { algo.destroy() }
        }
    },
)
