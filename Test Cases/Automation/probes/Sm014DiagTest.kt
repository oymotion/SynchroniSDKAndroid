package com.oymotion.sensorcapiktdemo.automation.probes

import android.Manifest
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.oymotion.sensorcapiktdemo.TestConfig
import com.oymotion.sensorcapiktdemo.automation.AutomationRunner
import com.oymotion.sensorcapiktdemo.automation.TestContext
import com.oymotion.sensorcapiktdemo.automation.connectionCases
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 临时诊断：单独跑 demo 的 DEV-SM-014，观察 onDeviceInfoUpdate 未触发的原因。 */
@RunWith(AndroidJUnit4::class)
class Sm014DiagTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    @Test
    fun runDevSm014() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        TestConfig.bind(context)
        println("[SM014] ctx=${context.packageName} extDir=${context.getExternalFilesDir(null)}")
        val cfg = java.io.File(context.getExternalFilesDir(null), "automation/config.json")
        println("[SM014] cfg exists=${cfg.isFile} firstLine=${runCatching { cfg.readLines().take(2) }.getOrNull()}")
        println("[SM014] TARGET_IDENTITY=${TestConfig.TARGET_IDENTITY} TARGET_MODEL=${runCatching { TestConfig.targetModel() }.getOrNull()}")
        val ctrl = com.oymotion.sensor.capi.SensorController.getInstance()
            .also { it.registerBleBridge(context.applicationContext) }
        repeat(3) { i ->
            val list = ctrl.scan(5_000)
            println("[SM014] scan#$i 找到 ${list.size} 台: " + list.joinToString { "${it.name.orEmpty()}@${it.mac}" })
        }
        val runner = AutomationRunner { log -> TestContext(context, log) }
        val case = connectionCases.first { it.id == "DEV-SM-014" }
        val result = runner.runCase(case) { println("[SM014] $it") }
        println("RESULT ${result.id} ${result.verdict} ${result.detail}")
    }
}
