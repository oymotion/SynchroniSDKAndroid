package com.oymotion.sensorcapiktdemo

import com.oymotion.sensor.capi.BleDevice

/**
 * 测试设备配置（对应 Python 侧 config.py 的 DEVICES + TARGET_IDENTITY）。
 *
 * 每台设备字段：
 *   namePrefix : 广播名前缀（仅描述/打印用，不参与匹配）
 *   mac        : 精确 MAC（大写、含冒号）；非空时优先按 MAC 精确匹配
 *   identity   : 蓝牙地址后四位（如 "850B"，对应广播名 "Cerelax(850B)" 括号内部分）
 *
 * 匹配优先级：mac > identity。
 */
data class TestDevice(
    val namePrefix: String,
    val mac: String,
    val identity: String,
)

object TestConfig {

    // ---- 本轮目标设备（identity，逗号分隔，可多个）----
    const val TARGET_IDENTITY = "850B"

    // ---- 设备清单 ----
    val DEVICES: List<TestDevice> = listOf(
        TestDevice(namePrefix = "Cerelax", mac = "BC:93:2A:3F:85:0B", identity = "850B"),
        TestDevice(namePrefix = "Cerelax", mac = "BC:93:2A:3F:85:1C", identity = "851C"),
        // TODO: 按需补充 OYWW1100 / OB6000 等其它设备
    )

    // ---- 通用测试参数 ----
    const val SCAN_TIMEOUT_MS = 5000
    const val READY_TIMEOUT_MS = 15000L
    const val DATA_TIMEOUT_SEC = 10L
    const val PACKAGE_SAMPLE_COUNT = 20
    const val POWER_REFRESH_INTERVAL_MS = 1000

    /** 本轮要测的 identity 列表（逗号分隔拆开）。 */
    val targetIdentities: List<String>
        get() = TARGET_IDENTITY.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** 按 identity 在设备清单里查找。 */
    fun devicesByIdentity(identity: String): List<TestDevice> =
        DEVICES.filter { it.identity.equals(identity, ignoreCase = true) }

    /** 按 MAC 在设备清单里查找。 */
    fun deviceByMac(mac: String): TestDevice? =
        DEVICES.firstOrNull { it.mac.isNotEmpty() && it.mac.equals(mac, ignoreCase = true) }

    /** 扫描到的 BLE 设备是否命中本轮目标（mac 精确 > 广播名后四位 identity）。 */
    fun matchesTarget(device: BleDevice): Boolean {
        if (deviceByMac(device.mac) != null) return true
        val name = device.name.orEmpty()
        return targetIdentities.any { id ->
            name.endsWith("($id)", ignoreCase = true) || name.endsWith(id, ignoreCase = true)
        }
    }
}
