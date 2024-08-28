# Synchroni SDK + kotlin demo

## Brief
Synchroni SDK is the software development kit for developers to access OYMotion Synchroni products.
## 1. Permission 

Application will obtain bluetooth permission by itself. 

## 2. Import SDK

```kotlin
dependencies {
    implementation files('../sdk/sensor.jar')
}

import com.sensor.*
```

## 3. Initalize
request bluetooth permissions
```kotlin
//MainActivity.kt
PermissionX.init(this)
.permissions(permissions.asList())
.request { allGranted, grantedList, deniedList ->
    if (allGranted) {
        //setup scan delegate and scan
        SensorController.getInstance().startScan(6000);
        if (SensorController.getInstance().delegate == null){
            SensorController.getInstance().delegate =
                SensorController.SensorControllerDelegate { bleDevices ->
                }
        }
    }
}

```

## 4. Start scan

```kotlin
SensorController.getInstance().startScan(periodInMS);
//returns array of BLEDevice
SensorController.SensorControllerDelegate { bleDevices ->

    for (device in bleDevices) {
        if (!(device.name.startsWith("SYNC") || device.name.startsWith("OB"))) continue
        Log.d("DEMO", "found device: " + device.name);
        val sensor = SensorController.getInstance().getSensor(device.mac);
    }
}

```

## 5. Stop scan

```kotlin
    SensorController.getInstance().stopScan();
```


## 6. Connect device


```kotlin
SensorProfile.connect()
```

## 7. Disconnect

```kotlin
SensorProfile.disconnect()
```


## 8. Device status

### 8.1 Get device status
```kotlin
SensorProfile.deviceState;
```

Please send command in 'BLEState.ready'

```kotlin
public enum State {Disconnected, Connecting, Connected, Ready, Disconnecting, Invalid}
```

### 8.2 Get device status change 
```kotlin
fun onStateChange(profile: SensorProfile, newState: BLEDevice.State) {
    Log.d("DEMO",
        "device : " + profile.device.name + " => " + newState.name
    )
}
```
    
## 9. DataNotify

### 9.1 init data notify

```kotlin
SensorProfile.initAll(PACKAGE_COUNT, timeoutInMS: TIMEOUT)
```

### 9.2 Start data transfer

For start data transfer, use `void startDataNotification(Callback cb)` to start. Process data in onSensorNotifyData.

```kotlin
SensorProfile.startDataNotification()

override fun onSensorNotifyData(profile: SensorProfile, rawData: SensorData) {
    Log.d("DEMO",
        profile.device.name + " got data type: " + rawData.dataType + " | " + rawData.channelSamples[0][0].sampleIndex
    )
    if (rawData.dataType == SensorData.NTF_EEG){

    }

}

```

data type：

```kotlin
typedef NS_ENUM(NSInteger, NotifyDataType)  {
    NTF_ACC_DATA,
    NTF_GYO_DATA,
    NTF_EEG,
    NTF_ECG,
    NTF_BRTH,
};
```

### 9.3 Stop data transfer

```kotlin
    sensor.stopDataNotification()
```
