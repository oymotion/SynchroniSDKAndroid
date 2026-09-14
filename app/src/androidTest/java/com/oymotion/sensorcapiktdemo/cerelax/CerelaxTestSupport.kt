package com.oymotion.sensorcapiktdemo.cerelax

import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig

/**
 * Cerelax 测试公共辅助，对应 Python 侧测试框架的公共 fixture：
 * 扫描目标设备、连接并等待 Ready、轮询状态。
 */
object CerelaxTestSupport {

    /** 单次扫描，返回第一个命中本轮目标的设备（mac 精确 > 广播名后四位 identity）。 */
    suspend fun scanTarget(controller: SensorController): BleDevice? =
        controller.scan(TestConfig.SCAN_TIMEOUT_MS).firstOrNull { TestConfig.matchesTarget(it) }

    /** 轮询等待设备进入 Ready。 */
    fun waitForReady(profile: SensorProfile, timeoutMs: Long = TestConfig.READY_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (profile.isReady()) return true
            Thread.sleep(100)
        }
        return profile.isReady()
    }

    /** requireSensor + connect + 等 Ready；任一步失败返回 null。 */
    suspend fun connectReady(controller: SensorController, device: BleDevice): SensorProfile? {
        val profile = controller.requireSensor(device.mac) ?: return null
        if (!profile.connect()) return null
        return if (waitForReady(profile)) profile else null
    }
}
