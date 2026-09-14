package com.oymotion.sensorcapiktdemo.cerelax

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensor.capi.*
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cerelax 06 Bin 录制/回放/解析（对应 Manual Cases 的 BIN-*）。
 *
 * 骨架：getBinFileInfo 对不存在路径返回 null 或 valid=false。
 * 待补：真实 bin 回放/解析。前置：起流时开 DEBUG_BLE_DATA_PATH=True 录制 .bin，
 *       再用 replayBinFile / multiReplayBinFile / pauseBinReplay / resumeBinReplay /
 *       stopBinReplay / parseBinToCsv(Async) 逐项验证。
 */
@RunWith(AndroidJUnit4::class)
class CerelaxBinTest {

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

    /** BIN-*：getBinFileInfo 对不存在路径返回 null 或 valid=false。 */
    @Test
    fun getBinFileInfo_invalidPath() {
        val info = controller.getBinFileInfo("/sdcard/nonexistent_cerelax.bin")
        assertTrue("不存在路径应返回 null 或 valid=false", info == null || !info.valid)
    }
}
