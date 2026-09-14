package com.oymotion.sensorcapiktdemo.cerelax

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import com.oymotion.sensorcapiktdemo.TestConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Cerelax 03 数据流（对应 Manual Cases 的 DATA-*）。
 *
 * 已覆盖骨架：DATA-FUNC-001/003/004（init + 起流收到 EEG + 停流）、
 *            DATA-FUNC-006（SensorData 元数据合法）。
 * 待补：DATA-FUNC-005（逐 DataType 能力判定起流）、DATA-FUNC-007/008/009/010/011 等。
 */
@RunWith(AndroidJUnit4::class)
class CerelaxDataflowTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    private lateinit var controller: SensorController

    @Before
    fun setUp() {
        controller = SensorController.getInstance()
        controller.registerBleBridge(ApplicationProvider.getApplicationContext())
    }

    /** DATA-FUNC-001/003/004：init + startDataNotification 收到数据 + stopDataNotification 停止。 */
    @Test
    fun init_startStop_receivesData() {
        runBlocking {
            val device = CerelaxTestSupport.scanTarget(controller)
            assertNotNull("未扫描到目标设备", device)

            val profile = CerelaxTestSupport.connectReady(controller, device!!)
            assertNotNull("连接未就绪", profile)
            val p = profile!!

            assertTrue("init 失败", p.init(TestConfig.PACKAGE_SAMPLE_COUNT, 5000, 60000))
            assertTrue("hasInited 应为 true", p.hasInited())

            val latch = CountDownLatch(1)
            val receivedTypes = mutableSetOf<Int>()
            var totalSamples = 0
            p.setOnDataListener { _, dataList ->
                dataList.forEach {
                    receivedTypes.add(it.getDataType())
                    totalSamples += it.getSampleCount()
                }
                if (dataList.isNotEmpty()) latch.countDown()
            }

            assertTrue("startDataNotification 失败", p.startDataNotification())
            assertTrue("isDataTransfering 应为 true", p.isDataTransfering())

            val got = latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS)
            assertTrue("${TestConfig.DATA_TIMEOUT_SEC}s 内未收到数据", got)
            assertTrue("样本数应 > 0", totalSamples > 0)
            assertTrue("应至少收到一种 DataType", receivedTypes.isNotEmpty())

            assertTrue("stopDataNotification 失败", p.stopDataNotification())
            assertTrue("停流后 isDataTransfering 应为 false", !p.isDataTransfering())

            p.disconnect()
        }
    }

    /** DATA-FUNC-006：SensorData 元数据合法（channelCount/sampleRate/dataType/deviceMac）。 */
    @Test
    fun sensorData_metadataValid() {
        runBlocking {
            val device = CerelaxTestSupport.scanTarget(controller)
            assertNotNull("未扫描到目标设备", device)

            val profile = CerelaxTestSupport.connectReady(controller, device!!)
            assertNotNull("连接未就绪", profile)
            val p = profile!!

            assertTrue("init 失败", p.init(TestConfig.PACKAGE_SAMPLE_COUNT, 5000, 60000))

            val latch = CountDownLatch(1)
            val first = AtomicReference<SensorData?>(null)
            p.setOnDataListener { _, dataList ->
                val eeg = dataList.firstOrNull { it.getDataType() == DataType.NTF_EEG }
                if (eeg != null) {
                    first.compareAndSet(null, eeg)
                    latch.countDown()
                }
            }

            assertTrue("startDataNotification 失败", p.startDataNotification())
            val got = latch.await(TestConfig.DATA_TIMEOUT_SEC, TimeUnit.SECONDS)
            assertTrue("未收到 EEG 数据", got)

            val d = first.get()
            assertNotNull("EEG SensorData 不应为 null", d)
            val sd = d!!
            assertTrue("deviceMac 应为目标设备", sd.getDeviceMac().equals(device.mac, ignoreCase = true))
            assertTrue("channelCount 应 > 0（Cerelax EEG 预期 2）", sd.getChannelCount() > 0)
            assertTrue("sampleRate 应 > 0", sd.getSampleRate() > 0f)
            assertTrue("sampleCount 应 > 0", sd.getSampleCount() > 0)
            assertTrue("isDataValid 应为 true", sd.isDataValid())

            p.stopDataNotification()
            p.disconnect()
        }
    }
}
