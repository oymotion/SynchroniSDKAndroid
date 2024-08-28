package com.sensor.synchronisdkandroid

import android.os.Build
import android.os.Bundle
import android.util.Log
import com.google.android.material.snackbar.Snackbar
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import com.permissionx.guolindev.PermissionX
import com.sensor.*
import com.sensor.synchronisdkandroid.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        binding.fab.setOnClickListener { view ->
            var permissions = arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION)
            val osVersion = Build.VERSION.SDK_INT
            if (osVersion >= 31){
                permissions = arrayOf(android.Manifest.permission.BLUETOOTH_SCAN,
                    android.Manifest.permission.BLUETOOTH_CONNECT)
            }
            //request permission
            PermissionX.init(this)
                .permissions(permissions.asList())
                .request { allGranted, grantedList, deniedList ->
                    if (allGranted) {
                        //setup scan delegate and scan
                        SensorController.getInstance().startScan(6000);
                        if (SensorController.getInstance().delegate == null){
                            SensorController.getInstance().delegate =
                                SensorController.SensorControllerDelegate { bleDevices ->
                                    SensorController.getInstance().stopScan();
                                    for (device in bleDevices) {
                                        if (!(device.name.startsWith("SYNC") || device.name.startsWith("OB"))) continue
                                        Log.d("DEMO", "found device: " + device.name);
                                        val sensor = SensorController.getInstance().getSensor(device.mac);
                                        if (sensor.deviceState == BLEDevice.State.Disconnected) {
                                            sensor.connect()
                                        }
                                        if (sensor.delegate == null){
                                            sensor.delegate = object :
                                                SensorProfile.SensorProfileDelegate {
                                                override fun onErrorCallback(profile: SensorProfile, errorMsg: String) {
                                                    Log.d("DEMO", profile.device.name + " got error: $errorMsg");
                                                }

                                                override fun onStateChange(profile: SensorProfile, newState: BLEDevice.State) {
                                                    Log.d("DEMO",
                                                        "device : " + profile.device.name + " => " + newState.name
                                                    )
                                                    if (newState == BLEDevice.State.Ready){
                                                        if (!profile.hasInit()){
                                                            sensor.initALL(5, 6000, SensorProfile.Callback { result, errorMsg ->
                                                                if (result < 0){
                                                                    Log.d("DEMO",
                                                                        profile.device.name + " Init fail: $errorMsg"
                                                                    )
                                                                }else{
                                                                    sensor.getBatteryLevel(6000) { result2, _ ->
                                                                        Log.d("DEMO",profile.device.name + " device info: " + sensor.deviceInfo.firmwareVersion + " Power is: $result2")
                                                                        sensor.startDataNotification(SensorProfile.Callback { _, _ ->
                                                                            Log.d("DEMO", profile.device.name + " Data started")
                                                                        })
                                                                    }
                                                                }
                                                            })
                                                        }
                                                    }else if (newState == BLEDevice.State.Disconnected){
                                                        Log.d("DEMO", "Please purge your cache data")
                                                    }
                                                }

                                                override fun onSensorNotifyData(profile: SensorProfile, rawData: SensorData) {
                                                    Log.d("DEMO",
                                                        profile.device.name + " got data type: " + rawData.dataType + " | " + rawData.channelSamples[0][0].sampleIndex
                                                    )
                                                    if (rawData.dataType == SensorData.NTF_EEG){

                                                    }
                                                    sensor.stopDataNotification(SensorProfile.Callback { _, _ ->
                                                        sensor.disconnect()
                                                    })
                                                }
                                            }
                                        }
                                    }
                                }
                        }
                    } else {
                        Toast.makeText(this, "Please grant all permissions", Toast.LENGTH_SHORT).show()
                    }
                }

        }


    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        return when (item.itemId) {
            R.id.action_settings -> true
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration)
                || super.onSupportNavigateUp()
    }
}