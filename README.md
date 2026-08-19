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

Supported ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64` (this demo
enables `arm64-v8a` and `x86`). minSdk 24.

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

// Optional: redirect SDK logs (must be called BEFORE setDebugEnabled(true),
// and both must precede registerBleBridge)
controller.setLogPath(true, "/sdcard/Documents/sensorsdklog/<session>")
controller.setDebugEnabled(true)

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
the previous `setParam` values -> `startDataNotification()`. Explicit user
calls (`connect()`, `disconnect()`, `stopDataNotification()`) cancel a
pending resume.

To customize recovery, return `true` from `onAutoReconnect(profile,
hasLastSession)` and handle it yourself; return `false` to fall back to the
default flow.

### 9. Device info

```kotlin
val info = profile.getDeviceInfo()   // valid after READY + successful init
// deviceName, modelName, hardwareVersion, firmwareVersion, mtuSize,
// plus a channelCount / sampleRate pair per modality:
// emg/eeg/ecg/brth/acc/gyro/magAngle/euler/quat/ppg/spo2/impe, the
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
val power = profile.getBatteryLevel()   // suspend extension; 0-100,
                                        // -1 = no valid reading yet
```

### 13. setParam / getParam

`setParam(key, value)` (suspend; callback style also available) configures
the profile after `READY`. Changing an `NTF_*` or `FILTER_*` key while
streaming restarts the data notification so the setting takes effect.
Returns `"OK"` on success or a string starting with `"Error"`.

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
profile.setParam("NTF_IMU", "ON")      // master switch of the four
                                       // NTF_GFORCE_{ACC,GYRO,EULER,QUAT} streams

// Firmware filter toggles
profile.setParam("FILTER_50HZ", "ON")  // 50 Hz notch
profile.setParam("FILTER_60HZ", "ON")  // 60 Hz notch
profile.setParam("FILTER_HPF", "ON")   // 0.5 Hz high-pass
profile.setParam("FILTER_LPF", "ON")   // 80 Hz low-pass

// EEG/ECG sample rate (bound together on devices that have both); validated
// against getParam("EEG_SAMPLE_RATE_LIST")
profile.setParam("EEG_SAMPLE_RATE", "500")

// Bin export: "True" exports the session capture on stop/disconnect into
// the SDK log directory; an absolute path exports there; "False"/"" disables
profile.setParam("DEBUG_BLE_DATA_PATH", "True")

// Per-profile log file in the SDK log directory ("True"), or an absolute
// custom path; "False"/"" disables
profile.setParam("DEBUG_LOG_PATH", "True")
```

Aggregate queries via `getParam(key)`:

```kotlin
profile.getParam("FILTER")              // "FILTER_50HZ|ON|FILTER_60HZ|ON|..."
profile.getParam("NTF")                 // "NTF_BRTH|ON|NTF_ECG|ON|..."
profile.getParam("EEG_SAMPLE_RATE")     // e.g. "250"
profile.getParam("EEG_SAMPLE_RATE_LIST")// e.g. "250|500"
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

// Replay (refused while the target profile is streaming live data)
val replay = controller.replayBinFile(path, deviceMac = "", realtime = true,
                                      timeoutMs = 5000)
// deviceMac "" lets the SDK create the profile from the bin config record;
// or pass the mac of an existing profile with listeners registered.

controller.pauseBinReplay(mac)     // "OK" or an error string
controller.resumeBinReplay(mac)
controller.stopBinReplay(mac)

// Offline conversion to CSV; returns the CSV file path
val csvPath = controller.parseBinToCsv(binPath, csvPath)
```

## Logging controls

```kotlin
controller.setDebugEnabled(true)   // opens the controller log in the log dir
controller.setLogPath(true, dir)   // redirect; dir is created if missing
controller.setLogPath(true, null)  // back to the default
                                   // (/sdcard/Documents/sensorsdklog)
controller.setLogPath(false, null) // file output off
```

Ordering: `setLogPath` before `setDebugEnabled(true)`, and both before
`registerBleBridge`, so the whole session lands in one directory.

Applications can write their own entries into the same timeline:

```kotlin
controller.log("User clicked start", "I")   // controller log
profile.log("User toggled filter 50Hz", "I") // profile log when enabled
```

`level` accepts `"D"` / `"I"` / `"W"` / `"E"`; `"D"` follows the
`setDebugEnabled` switch, the others are always emitted. Entries are tagged
`[App]`.

## Demo app

The bundled app (`com.oymotion.sensorcapiktdemo`) exercises the whole API:
multi-device scan/connect with per-device state, three pages (Device / Bio /
IMU), bio waveforms with a live band-pass filter picker, IMU waveforms with
FFT spectra and a 3D quaternion cube, NTF/FILTER/sample-rate controls,
battery display, auto reconnect, SDK debug log and bin export toggles, and
bin replay / parse-to-CSV.

Build:

```sh
./gradlew assembleDebug
```

## License

MIT
