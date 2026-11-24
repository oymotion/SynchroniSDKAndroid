package com.oymotion.sensorcapiktdemo.devicespecs

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 设备规格仓库（对应 Python 侧 `common.spec_for_identity` 与 `device_specs/<model>.py` 的加载）。
 *
 * 读取优先级（每份 spec 独立回退）：
 *   1. 设备端外部 JSON：`<externalFilesDir>/automation/device_specs/<model>.json`
 *   2. APK assets 内置默认：`automation/device_specs/<model>.json`
 *   3. 都没有 -> 抛异常（调用方决定 SKIP 还是 FAIL）
 *
 * 外部 JSON 解析失败时记录原因并回退 assets，不崩溃；结果进程内缓存。
 */
object SpecStore {

    private const val TAG = "SpecStore"
    private const val ASSET_DIR = "automation/device_specs"
    private const val EXTERNAL_DIR = "automation/device_specs"

    private val cache = HashMap<String, DeviceSpec>()

    @Volatile
    private var externalRoot: File? = null

    @Volatile
    private var appContext: Context? = null

    /** 绑定应用 Context（外部目录根 = `<externalFilesDir>`）。 */
    fun bind(context: Context) {
        appContext = context.applicationContext
        externalRoot = File(context.getExternalFilesDir(null) ?: context.filesDir, "automation")
    }

    /** 清除缓存（外部 JSON 改动后调用）。 */
    fun reload() {
        cache.clear()
    }

    fun load(model: String, forceReload: Boolean = false): DeviceSpec {
        if (!forceReload) cache[model]?.let { return it }

        val spec = readExternal(model) ?: readAsset(model)
            ?: error("未找到设备规格: $model（外部与 assets 均缺失 $ASSET_DIR/$model.json）")
        cache[model] = spec
        return spec
    }

    private fun readExternal(model: String): DeviceSpec? {
        val root = externalRoot ?: return null
        val file = File(root, "$EXTERNAL_DIR/$model.json")
        if (!file.isFile) return null
        return runCatching { DeviceSpec.fromJson(JSONObject(file.readText())) }
            .onFailure { Log.w(TAG, "外部 spec 解析失败，回退 assets: ${file.absolutePath}", it) }
            .getOrNull()
    }

    private fun readAsset(model: String): DeviceSpec? {
        val context = appContext ?: return null
        return runCatching {
            context.assets.open("$ASSET_DIR/$model.json").bufferedReader().use { reader ->
                DeviceSpec.fromJson(JSONObject(reader.readText()))
            }
        }.onFailure { Log.w(TAG, "assets spec 读取失败: $ASSET_DIR/$model.json", it) }
            .getOrNull()
    }
}
