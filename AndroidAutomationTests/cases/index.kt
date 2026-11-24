package com.oymotion.sensorcapiktdemo.automation

/** 按用例 id 排序（编号 3 位补零，字符串序即数字序）。 */
private val byId = compareBy<TestCase> { it.id }

/**
 * 全量用例（按章节顺序，章内按 id 排序），供自动化标签页展示。
 *
 * 展示顺序与执行顺序分离：破坏性用例（`destructive=true`，如 CTRL-FUNC-013 terminate）
 * 在展示时仍留在原章节，但批量执行时由 [executionOrder] 挪到整轮最后——因为 Android 上
 * terminate 会拆掉 native 后端（重建实例后 isEnable=false、startScan 恒为 false），
 * 重启 App 前无法恢复，必须保证它之后不再有依赖 controller 的用例。
 */
val allCases: List<TestCase> = listOf(
    controlCases.sortedWith(byId),
    connectionCases.sortedWith(byId),
    dataflowCases.sortedWith(byId),
    paramCases.sortedWith(byId),
    multiCases.sortedWith(byId),
    binCases.sortedWith(byId),
    batteryLogCases.sortedWith(byId),
    systemCases.sortedWith(byId),
    asyncCases.sortedWith(byId),
    miscCases.sortedWith(byId),
).flatten()

/** 批量执行顺序：destructive 用例排到最后，其余保持传入顺序。 */
fun executionOrder(cases: List<TestCase>): List<TestCase> =
    cases.filterNot { it.destructive } + cases.filter { it.destructive }
