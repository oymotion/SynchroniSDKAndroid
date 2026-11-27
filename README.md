# sensorcapi-android

OYMotion Sensor SDK for Android (Kotlin/Java), plus a Kotlin demo app.

## Brief

OYMotion SDK is the software development kit for developers to access OYMotion
products. This project shows how to use the Android binding (`sensorcapi`,
Java/Kotlin API over the C++ core) from a Kotlin app: scan, connect, configure
and stream EEG/ECG/EMG/IMU/PPG/SpO2 data, and replay recorded bin files
offline.

## Installation

The SDK ships as a prebuilt AAR (`app/libs/sensorcapi-release.aar`, contains
the native libraries and the Java/Kotlin binding classes). Depend on it
directly:

```groovy
dependencies {
    implementation files('libs/sensorcapi-release.aar')
    implementation 'org.jetbrains.kotlin:kotlin-stdlib:2.1.0'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0'
}
```

Supported ABIs: `arm64-v8a`, `x86`, `x86_64` (this demo
enables `arm64-v8a` and `x86`). minSdk 26.

## 1. Permissions

Declare the Bluetooth permissions and request them at runtime:

```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" android:maxSdkVersion="30" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
    android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
```

On Android 12+ (API 31) request `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT`; on
older versions `ACCESS_FINE_LOCATION` is required for scanning.

Writing SDK logs and bin exports to shared storage
(`/sdcard/Documents/sensorsdklog`) additionally needs all-files access on
Android 11+: declare `MANAGE_EXTERNAL_STORAGE` and send the user to the
system all-files-access settings page.

## 2. Initialize the SDK

```kotlin
import com.oymotion.sensor.capi.*

val controller = SensorController.getInstance()

// Optional: redirect SDK logs (must be called BEFORE DEBUG_ENABLED=True,
// and both must precede registerBleBridge)
controller.setParam("LOG_PATH", "/sdcard/Documents/sensorsdklog/<session>", null)
controller.setParam("DEBUG_ENABLED", "True", null)

// Register the BLE bridge once, before any scan/connect
controller.registerBleBridge(applicationContext)

// Scan results arrive here
controller.setOnScanResultListener { devices ->
    // List<BleDevice>: name / mac / rssi; devices not yet connected
}
```

Use `getVersion()` to get the SDK version string.

## SensorController methods

### 1. Start scan

```kotlin
val ok = controller.startScan(6000)
```

`onScanResult` is invoked every `periodInMs` with the current device list.

The one-shot variant is the suspend extension `controller.scan(periodInMs)`
which returns the `List<BleDevice>` once.

### 2. Stop scan / check state

```kotlin
controller.stopScan()
val scanning = controller.isScanning()
val btOn = controller.isEnable()
controller.setOnEnableChangedListener { enabled -> /* bluetooth on/off */ }
```

### 3. Create / get a SensorProfile

```kotlin
val profile = controller.requireSensor(mac)   // null when mac is invalid
val same    = controller.getSensor(mac)       // null when not created yet
val connected = controller.getConnectedSensors()
```

### 4. Terminate

```kotlin
SensorController.terminate()
```

Please MAKE SURE to call `terminate()` when the app exits.

## SensorProfile methods

### 5. Register listeners

```kotlin
profile.setOnStateChangeListener { p, newState ->
    // DeviceState.CONNECTING / CONNECTED / READY / DISCONNECTED / ...
    // handle unexpected disconnects here
}
profile.setOnErrorListener { p, errorMsg -> }
profile.setOnPowerListener { p, power ->
    // battery 0-100 pushed periodically (see init); -1 is never reported
}
profile.setOnDataListener { p, dataList ->
    // List<SensorData> batches after startDataNotification
    for (data in dataList) { }
}
profile.setOnDeviceInfoUpdateListener { p, info -> }
profile.setOnDataTransferStateChangeListener { p, isTransferring -> }
```

Listeners do not run on the Android main thread — keep them short and
marshal UI updates to the main thread.

A further listener, `setOnAutoReconnectListener`, customizes stream recovery
after an abnormal disconnect — see 8.1.

### 6. Connect / disconnect

```kotlin
profile.connect { result, errorMsg -> }     // callback style
val ok = profile.connect()                  // suspend extension
val ok = profile.disconnect()               // suspend extension
```

`disconnect()` stops an active data notification first.

### 7. Device state

```kotlin
val state = profile.deviceState   // DeviceState.DISCONNECTED .. INVALID
if (profile.isReady) { }          // READY: commands may be sent
```

### 8. Get the BLE device

```kotlin
val dev = profile.device          // BleDevice: name / mac / rssi
```

### 8.1 Auto reconnect and resume data stream

```kotlin
profile.setAutoReconnect(true)    // default on; set false to opt out
```

While enabled and the device is streaming, an abnormal disconnect is followed
by automatic reconnect -> `init()` with the previous arguments -> re-applying
the previous `setParam` values -> `startDataNotification()`. A pending resume
is cancelled by `connect()`, `disconnect()`, `setAutoReconnect(false)`, or a
successful `startDataNotification()`.

To customize recovery, call `answer(true)` from `onAutoReconnect(profile,
hasLastSession, answer)` and handle it yourself (the answer may come from any
thread, at any later time); `answer(false)` falls back to the default flow.
If no answer arrives within 10 s the SDK runs the default recovery.

### 9. Device info

```kotlin
val info = profile.getDeviceInfo()   // valid after READY + successful init
// deviceName, modelName, hardwareVersion, firmwareVersion, mtuSize,
// plus a channelCount / sampleRate pair per modality:
// emg/eeg/ecg/brth/acc/gyro/magAngle/euler/quat/ppg/spo2/impe/gest, the
// aggregated imuChannelCount / imuSampleRate (0 = no aggregated stream),
// and emg/eeg/ecgMaxSampleRate (0 = not reported).
```

`fetchDeviceInfo(timeoutMs)` (suspend) re-queries the device;
`setOnDeviceInfoUpdateListener` reports later changes.

### 10. Init data transfer

```kotlin
val ok = profile.init(packageSampleCount = 15, timeoutMs = 30000,
                      powerRefreshIntervalMs = 60000)   // suspend extension
val inited = profile.hasInited()
```

- `packageSampleCount`: samples per channel in each `SensorData` batch
  delivered to `onData`.
- `powerRefreshIntervalMs`: push period for `onPowerChanged` (0 = off).

### 11. Data notification

```kotlin
val ok = profile.startDataNotification()    // suspend extension
val streaming = profile.isDataTransfering()
profile.stopDataNotification()
```

Data type list (`DataType`):

```
NTF_ACC = 1            acceleration, unit g
NTF_GYRO = 2           gyroscope, unit degree/s
NTF_EULER = 4          euler angle, unit degree
NTF_QUATERNION = 5     quaternion (w, x, y, z)
NTF_GEST = 7           gesture id
NTF_EMG = 8            unit uV
NTF_MAG_ANGLE = 13
NTF_EEG = 16           unit uV
NTF_ECG = 17           unit uV
NTF_IMPEDANCE = 18     electrode impedance
NTF_IMU = 19           aggregated IMU batch (acc 0-2 / gyro 3-5 / euler 6-8 /
                       quat 9-12; see DeviceInfo.imuChannelCount)
NTF_ADS = 20
NTF_BRTH = 21          respiration, unit uV
NTF_IMPEDANCE_EXT = 22
NTF_SPO2 = 23          SpO2 percentage
NTF_PPG = 24           PPG raw samples
```

Process data in `onData`. Each `SensorData` batch offers:

- metadata: `deviceMac` / `dataType` / `sampleRate` / `channelCount` /
  `sampleCount` / `channelMask` / `isChannelEnabled(ch)` /
  `lostPackageCount` / `startTimeStamp` / `startTimeSec` / `delay` /
  `isDataValid()`
- per-point accessors (`channel`, `index`): `getData` / `getRawData` /
  `getImpedance` / `getSaturation` / `getSampleIndex` / `getTimeStampInMs` /
  `getAbsTimeStampInSec` / `isLost`, or `getChannelSample(channel, index)`
  for the whole `Sample`
- `clone()` for an owned deep copy; a borrowed batch is only guaranteed
  readable inside the callback — `clone()` whatever you keep.

```kotlin
profile.setOnDataListener { _, dataList ->
    for (data in dataList) {
        when (data.dataType) {
            DataType.NTF_EEG -> { }
            DataType.NTF_ECG -> { }
        }
        for (ch in 0 until data.channelCount) {
            for (i in 0 until data.sampleCount) {
                if (!data.isLost(ch, i)) {
                    val v = data.getData(ch, i)
                }
            }
        }
    }
}
```

### 12. Battery level

```kotlin
val power = profile.getBatteryLevel()   // suspend extension; fresh reading
                                        // 0-100, throws IOException on failure
```

`getCachedPower()` returns the last reading pushed via `onPowerChanged` or
delivered by a successful `getBatteryLevel()`, without touching the device;
`-1` means no valid reading yet.

### 13. setParam / getParam

`setParam(key, value)` (suspend; callback style also available) configures
the profile after `READY`. Changing an `NTF_*` key while streaming restarts
the data notification so the setting takes effect; `FILTER_*` keys are
applied on the fly without interrupting the stream.
Returns `"OK"` on success or an error string: argument/state rejections start
with `"Error: "`, while command-outcome failures reported by the device are
all-caps `"ERROR: ..."` strings (e.g. `"ERROR: restart stream fail"`).

```kotlin
// Data stream toggles, "ON" / "OFF"
profile.setParam("NTF_EEG", "ON")
profile.setParam("NTF_ECG", "ON")
profile.setParam("NTF_EMG", "ON")
profile.setParam("NTF_GEST", "ON")     // NTF_GEST and NTF_EMG are mutually
                                       // exclusive on legacy EMG devices
profile.setParam("NTF_BRTH", "ON")
profile.setParam("NTF_IMPEDANCE", "ON")
profile.setParam("NTF_MAG_ANGLE", "ON")
profile.setParam("NTF_PPG", "ON")
profile.setParam("NTF_SPO2", "ON")
profile.setParam("NTF_IMU", "ON")      // all four ACC/GYRO/EULER/QUAT
                                       // streams together

// Firmware filter toggles
profile.setParam("FILTER_50HZ", "ON")  // 50 Hz notch
profile.setParam("FILTER_60HZ", "ON")  // 60 Hz notch
profile.setParam("FILTER_HPF", "ON")   // 0.5 Hz high-pass
profile.setParam("FILTER_LPF", "ON")   // 80 Hz low-pass

// EEG/ECG sample rate (bound together on devices that have both); validated
// against getParam("EEG_SAMPLE_RATE_LIST")
profile.setParam("EEG_SAMPLE_RATE", "500")

// EMG sample rate (new EMG devices only); validated against
// getParam("EMG_SAMPLE_RATE_LIST")
profile.setParam("EMG_SAMPLE_RATE", "1000")

// IMU sample rate (devices answering the IMU cap query only); validated
// against getParam("IMU_SAMPLE_RATE_LIST")
profile.setParam("IMU_SAMPLE_RATE", "100")

// PPG sample rate (PPG devices only); validated against
// getParam("PPG_SAMPLE_RATE_LIST")
profile.setParam("PPG_SAMPLE_RATE", "200")

// NeuCir remote control (NeuCir devices only)
profile.setParam("NEUCIR_SET_MODE", "APP_REMOTE")
profile.setParam("NEUCIR_APP_CONTROL", "OPEN")   // OPEN / CLOSE / STOP

// Bin export: "True" exports the session capture on stop/disconnect into
// the SDK log directory; an absolute path exports there; "False"/"" disables.
// Once set, the capture is written directly at that path (visible while
// streaming); without a path it is copied there on stop/disconnect
profile.setParam("DEBUG_BLE_DATA_PATH", "True")

// Per-profile log file in the SDK log directory ("True"), or an absolute
// custom path; "False"/"" disables
profile.setParam("DEBUG_LOG_PATH", "True")

// Flush this device's pending bin capture and SDK log to disk; the value
// is ignored
profile.setParam("FLUSH_BLE_DATA", "")
```

Aggregate queries via `getParam(key)`:

```kotlin
profile.getParam("FILTER")              // "FILTER_50HZ|ON|FILTER_60HZ|ON|..."
profile.getParam("NTF")                 // "NTF_BRTH|ON|NTF_ECG|ON|..."
profile.getParam("EEG_SAMPLE_RATE")     // e.g. "250"
profile.getParam("EEG_SAMPLE_RATE_LIST")// e.g. "250|500"
profile.getParam("EMG_SAMPLE_RATE")     // e.g. "500"
profile.getParam("EMG_SAMPLE_RATE_LIST")// e.g. "500|1000"
profile.getParam("IMU_SAMPLE_RATE")     // e.g. "50"
profile.getParam("IMU_SAMPLE_RATE_LIST")// e.g. "50|100|200|250"
profile.getParam("PPG_SAMPLE_RATE")     // e.g. "50"
profile.getParam("PPG_SAMPLE_RATE_LIST")// e.g. "50|100|200"
```

### Async API

Every blocking operation exists in three shapes: callback listener,
`CompletableFuture` (`connectAsync()` / `disconnectAsync()`), and Kotlin
`suspend` extension (`connect()`, `disconnect()`, `init()`,
`startDataNotification()`, `stopDataNotification()`, `setParam()`,
`getParam()`, `getBatteryLevel()`, `fetchDeviceInfo()`,
`SensorController.scan()`, `parseBinToCsvAsync()`).

## Bin file recording and replay

Every session's raw BLE capture can be exported as a `.bin` file (see
`DEBUG_BLE_DATA_PATH`) and replayed offline through the normal parsing
pipeline; parsed results arrive via `onData`, same as live data.

```kotlin
val info = controller.getBinFileInfo(path)   // BinFileInfo: mac, deviceName,
                                             // durationSec, deviceInfo, valid

// Replay (refused while the device's live profile is linked: Connected/Ready)
val replay = controller.replayBinFile(path, info.mac, true, 5000)
// deviceMac is required ("" is rejected); take it from
// getBinFileInfo(path).mac or pass the mac of an existing profile with
// listeners registered. A shorter overload replayBinFile(path, deviceMac,
// realtime) uses the SDK default delegate-wait timeout.

controller.pauseBinReplay(mac)     // "OK" or an error string
controller.resumeBinReplay(mac)
controller.stopBinReplay(mac)

// Synchronized group replay: every member capture starts on one shared
// clock anchored at the earliest data record, so concurrently recorded
// captures keep their original relative offsets. Each member is designated
// by a SensorProfile (e.g. from controller.requireSensor(mac)); the replay
// rebinds it to the replaying profile. The returned list is input-order
// aligned with null entries for members that failed validation.
val group = controller.multiReplayBinFile(listOf(path1, path2),
                                          listOf(profile1, profile2),
                                          true, 5000)

// Offline conversion to CSV (listener async; the result string carries the
// CSV file path on success)
controller.parseBinToCsv(binPath, csvPath) { result, _ -> ... }
// or the suspend bridge: val csvPath = controller.parseBinToCsvAsync(binPath, csvPath)
```

## Logging controls

```kotlin
controller.setParam("DEBUG_ENABLED", "True", null)  // opens the controller log in the log dir
controller.setParam("LOG_PATH", dir, null)          // redirect; dir is created if missing
controller.setParam("LOG_PATH", "True", null)       // back to the default
                                                    // (/sdcard/Documents/sensorsdklog)
controller.setParam("LOG_PATH", "False", null)      // file output off
controller.setParam("DATA_LOG_ENABLED", "True", null) // raw data records on
```

The controller-level `setParam` / `getParam` take an optional trailing
`OnParamListener` (`(result, errorMsg)`); `null` = fire-and-forget.

Ordering: `LOG_PATH` before `DEBUG_ENABLED=True`, and both before
`registerBleBridge`, so the whole session lands in one directory.

Applications can write their own entries into the same timeline:

```kotlin
controller.log("User clicked start", "I")   // controller log
profile.log("User toggled filter 50Hz", "I") // profile log when enabled
```

`level` accepts `"D"` / `"I"` / `"W"` / `"E"`; `"D"` follows the
`DEBUG_ENABLED` switch, the others are always emitted. Entries are tagged
`[App]`.

## Demo app

The bundled app (`com.oymotion.sensorcapiktdemo`, demo version 0.1.50) has
**Device**, **Bio**, and **IMU** pages:

- **Device**: continuous scan with an RSSI-sorted list, multi-device
  connections, and a selected current device. Start/Stop controls a single
  stream; Multi Start/Stop synchronizes all connected devices. Auto Reconnect,
  Clone Data, SDK debug log/bin toggles, Translate IMU (on by default, meaning
  split ACC/GYRO/EULER/QUAT streams), eight NTF switches, FILTER switches, and
  EEG/EMG/IMU/PPG rate controls follow the current device. The title shows the
  demo and SDK versions.
- **Bio**: eight slots with EMG, paged EEG/ECG/BRTH, or the fixed PPG plot set.
  An Angle stream adds a fixed 0-180 degree row. Supported rows include
  per-stream FFT spectra and impedance side values; the Live Filter provides
  Off and delta/theta/alpha/beta/gamma bands.
- **IMU**: ACC/GYRO/MAG/EULER/QUAT waveforms, FFT spectra, real-time values,
  and a 3D quaternion cube. MAG displays MAG-X/Y/Z in uT.
- **BIN tools**: Replay Bin opens the Android file picker. Multi Replay Bin
  accepts a `;`-separated path list and synchronizes group replay with
  group-wide Pause/Resume and per-member Stop. Analyze Bin writes a `.csv`
  beside the source file. Replay EOF is reported through
  `setOnDataTransferStateChangeListener`. The text field defaults to
  `<session log dir>/test.bin`.

Build and install:

```sh
./gradlew assembleDebug
# Windows: gradlew.bat assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

The demo enables `arm64-v8a` and `x86`; the x86 build supports UI and offline
BIN workflows on a 32-bit emulator, while physical BLE requires a compatible
device. On Android 11+, scoped storage prevents arbitrary `/sdcard` reads.
Push test captures into the app-specific directory, then select them through
the file picker:

```sh
adb push <bin> /sdcard/Android/data/com.oymotion.sensorcapiktdemo/files/
```

Writing logs and exports to shared `/sdcard/Documents/sensorsdklog` also needs
the all-files-access permission described in Permissions.

## License

MIT
