package com.of.mecanumcontroller

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.ui.graphics.vector.ImageVector
import com.of.mecanumcontroller.ui.theme.MecanumControllerTheme
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BUILD_LABEL = "Build ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) BLE"
private const val BT_TAG = "MecanumBT"
private const val BLE_SCAN_TIMEOUT_MS = 10_000L
private const val BLE_COMMAND_INTERVAL_MS = 140L
private const val BLE_WRITE_RETRY_MS = 120L
private const val BLE_UNNAMED_LOG_LIMIT = 3
private const val MIN_CONTROL_COMMAND_MS = 150L
private const val CONTROL_HAPTIC_DURATION_MS = 36L
private const val CONTROL_HAPTIC_AMPLITUDE = 180
private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

class MainActivity : ComponentActivity() {
    private var bleGatt: BluetoothGatt? = null
    private var bleWriteCharacteristic: BluetoothGattCharacteristic? = null
    private var bleScanCallback: ScanCallback? = null
    private var bleScanToken = 0L
    private var senderThread: Thread? = null
    private val bleHandler = Handler(Looper.getMainLooper())
    private val bleWriteLock = Any()
    private var bleWriteInFlight = false
    private var pendingBleCommand: String? = null
    private var bleRetryScheduled = false
    private var bleWriteToken = 0L
    private var lastBleBusyLogMs = 0L
    private var unnamedBleLogCount = 0
    private val discoveredBleAddresses = mutableSetOf<String>()

    @Volatile
    private var connected = false

    @Volatile
    private var selectedSpeed = 160

    @Volatile
    private var sonarEnabled = true

    @Volatile
    private var activeCommand = command(
        throttle = 0,
        steer = 0,
        speed = 160,
        sonarEnabled = true
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MecanumControllerTheme {
                RobotControllerScreen(
                    scanBleDevices = ::scanBleDevices,
                    connectBle = ::connectBleToDevice,
                    disconnect = ::disconnect,
                    setMotion = ::setMotion,
                    setSpeed = ::setSpeed,
                    setSonarEnabled = ::setSonarEnabled,
                    isConnected = { connected }
                )
            }
        }
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) {
            disconnect()
        }
        super.onDestroy()
    }

    private fun setMotion(
        throttle: Int,
        steer: Int,
        rotLeft: Boolean = false,
        rotRight: Boolean = false,
        speedOverride: Int? = null
    ) {
        val commandSpeed = speedOverride ?: selectedSpeed
        activeCommand = command(
            throttle,
            steer,
            commandSpeed,
            rotLeft,
            rotRight,
            sonarEnabled
        )
        Log.d(BT_TAG, "Motion command: ${activeCommand.trim()}")
        sendActiveCommand()
    }

    private fun setSpeed(speed: Int) {
        selectedSpeed = speed
        val parsed = parseCommand(activeCommand)
        activeCommand = command(
            throttle = parsed.throttle,
            steer = parsed.steer,
            speed = speed,
            rotLeft = parsed.rotLeft,
            rotRight = parsed.rotRight,
            sonarEnabled = sonarEnabled
        )
        Log.d(BT_TAG, "Speed command: ${activeCommand.trim()}")
        sendActiveCommand()
    }

    private fun setSonarEnabled(enabled: Boolean) {
        sonarEnabled = enabled
        val parsed = parseCommand(activeCommand)
        activeCommand = command(
            throttle = parsed.throttle,
            steer = parsed.steer,
            speed = parsed.speed,
            rotLeft = parsed.rotLeft,
            rotRight = parsed.rotRight,
            sonarEnabled = enabled
        )
        Log.d(BT_TAG, "Sonic auto-stop enabled=$enabled")
        sendActiveCommand()
    }

    @SuppressLint("MissingPermission")
    private fun scanBleDevices(
        onDeviceFound: (DiscoveredBleDevice) -> Unit,
        onStatus: (String) -> Unit
    ) {
        val scanner = getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner
        if (scanner == null) {
            onStatus("BLE scanner not available")
            return
        }

        stopBleScan(logStopped = false)
        bleScanToken += 1
        val scanToken = bleScanToken
        unnamedBleLogCount = 0
        discoveredBleAddresses.clear()
        onStatus("BLE scanning...")
        Log.d(BT_TAG, "BLE scan started")

        bleScanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val displayName = result.displayName()

                if (displayName == null) {
                    if (unnamedBleLogCount < BLE_UNNAMED_LOG_LIMIT) {
                        unnamedBleLogCount += 1
                        Log.d(BT_TAG, "BLE_FOUND_UNNAMED address=${device.address} rssi=${result.rssi}")
                    }
                    return
                }

                if (!discoveredBleAddresses.add(device.address)) {
                    return
                }

                Log.d(
                    BT_TAG,
                    "BLE_FOUND name=$displayName address=${device.address} rssi=${result.rssi} services=${result.scanRecord?.serviceUuids?.joinToString { it.uuid.toString() } ?: "(none)"}"
                )
                onDeviceFound(DiscoveredBleDevice(device = device, name = displayName))
            }

            override fun onScanFailed(errorCode: Int) {
                Log.e(BT_TAG, "BLE scan failed code=$errorCode")
                onStatus("BLE scan failed: $errorCode")
            }
        }

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanner.startScan(null, settings, bleScanCallback)
        } catch (error: SecurityException) {
            bleScanCallback = null
            Log.e(BT_TAG, "BLE scan permission missing", error)
            onStatus("BLE scan permission missing")
            return
        } catch (error: Exception) {
            bleScanCallback = null
            Log.e(BT_TAG, "BLE scan start failed", error)
            onStatus("BLE scan start failed: ${error.message}")
            return
        }

        bleHandler.postDelayed(
            {
                if (bleScanToken == scanToken && bleScanCallback != null) {
                    stopBleScan()
                    onStatus("BLE scan finished")
                }
            },
            BLE_SCAN_TIMEOUT_MS
        )
    }

    @SuppressLint("MissingPermission")
    private fun connectBleToDevice(
        device: BluetoothDevice,
        onStatus: (String) -> Unit,
        onConnectedChanged: (Boolean) -> Unit,
        onTelemetry: (SonarTelemetry) -> Unit
    ) {
        if (connected) disconnect()

        stopBleScan()
        Log.d(BT_TAG, "connectBleToDevice name=${device.safeName()} address=${device.address}")
        onStatus("BLE connecting to ${device.safeName()}...")

        val callback = object : BluetoothGattCallback() {
            private val incoming = StringBuilder()
            private var readyStarted = false

            private fun markReady(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
                if (readyStarted) return
                readyStarted = true

                bleGatt = gatt
                bleWriteCharacteristic = characteristic
                connected = true
                activeCommand = command(
                    throttle = 0,
                    steer = 0,
                    speed = selectedSpeed,
                    sonarEnabled = sonarEnabled
                )
                startSender()
                sendActiveCommand()

                Log.d(BT_TAG, "BLE ready writeChar=${characteristic.uuid} writeType=${characteristic.writeType}")
                runOnUiThread {
                    onConnectedChanged(true)
                    onStatus("BLE connected: ${characteristic.uuid.shortName()}")
                }
            }

            private fun handleIncoming(bytes: ByteArray) {
                val raw = bytes.toString(Charsets.UTF_8)
                Log.d(BT_TAG, "BLE notify bytes=${bytes.size} raw=${raw.trim()}")
                val lines = mutableListOf<String>()
                synchronized(incoming) {
                    incoming.append(raw)
                    if (incoming.length > 512) {
                        incoming.delete(0, incoming.length - 256)
                    }

                    var newline = incoming.indexOf("\n")
                    while (newline >= 0) {
                        lines += incoming.substring(0, newline).trimEnd('\r')
                        incoming.delete(0, newline + 1)
                        newline = incoming.indexOf("\n")
                    }
                }

                lines.forEach { line ->
                    parseSonarTelemetry(line)?.let { telemetry ->
                        runOnUiThread { onTelemetry(telemetry) }
                    }
                }
            }

            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                Log.d(BT_TAG, "BLE state ${newState.connectionStateName()} status=$status")

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(BT_TAG, "BLE connection failed status=$status")
                    gatt.close()
                    runOnUiThread {
                        connected = false
                        onConnectedChanged(false)
                        onStatus("BLE connect failed: $status")
                    }
                    return
                }

                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        bleGatt = gatt
                        runOnUiThread { onStatus("BLE connected, discovering services...") }
                        gatt.discoverServices()
                    }

                    BluetoothProfile.STATE_DISCONNECTED -> {
                        connected = false
                        bleWriteCharacteristic = null
                        resetBleWriteQueue(invalidatePendingCallbacks = true)
                        gatt.close()
                        runOnUiThread {
                            onConnectedChanged(false)
                            onStatus("BLE disconnected")
                        }
                    }
                }
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                Log.d(BT_TAG, "BLE services discovered status=$status")

                if (status != BluetoothGatt.GATT_SUCCESS) {
                    runOnUiThread { onStatus("BLE service discovery failed: $status") }
                    gatt.disconnect()
                    return
                }

                logGattServices(gatt)
                val characteristic = findWritableCharacteristic(gatt.services)

                if (characteristic == null) {
                    runOnUiThread { onStatus("BLE writable characteristic not found") }
                    gatt.disconnect()
                    return
                }

                characteristic.writeType = if (
                    characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                ) {
                    BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                } else {
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                }

                bleGatt = gatt
                bleWriteCharacteristic = characteristic
                val notifyCharacteristic = findNotifiableCharacteristic(
                    gatt.services,
                    preferredUuid = characteristic.uuid
                )

                if (notifyCharacteristic != null &&
                    enableNotifications(gatt, notifyCharacteristic)
                ) {
                    runOnUiThread { onStatus("BLE connected, enabling Sonic telemetry...") }
                    bleHandler.postDelayed(
                        { markReady(gatt, characteristic) },
                        1_000L
                    )
                } else {
                    markReady(gatt, characteristic)
                }
            }

            override fun onDescriptorWrite(
                gatt: BluetoothGatt,
                descriptor: BluetoothGattDescriptor,
                status: Int
            ) {
                Log.d(BT_TAG, "BLE notification descriptor status=$status")
                bleWriteCharacteristic?.let { markReady(gatt, it) }
            }

            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic
            ) {
                @Suppress("DEPRECATION")
                handleIncoming(characteristic.value ?: return)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray
            ) {
                handleIncoming(value)
            }

            override fun onCharacteristicWrite(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                status: Int
            ) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    Log.e(BT_TAG, "BLE write completed with error status=$status")
                }
                completeBleWrite(token = null)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            device.connectGatt(applicationContext, false, callback, BluetoothDevice.TRANSPORT_LE)
        } else {
            device.connectGatt(applicationContext, false, callback)
        }
    }

    private fun disconnect() {
        Log.d(BT_TAG, "Disconnect requested")
        activeCommand = command(
            throttle = 0,
            steer = 0,
            speed = selectedSpeed,
            sonarEnabled = sonarEnabled
        )
        sendActiveCommand()
        connected = false
        bleWriteCharacteristic = null
        resetBleWriteQueue(invalidatePendingCallbacks = true)

        try {
            bleGatt?.disconnect()
            bleGatt?.close()
        } catch (_: Exception) {
        }
        bleGatt = null
        stopBleScan()
    }

    private fun startSender() {
        if (senderThread?.isAlive == true) return

        senderThread = thread(name = "robot-command-sender") {
            while (connected) {
                sendActiveCommand()
                Thread.sleep(BLE_COMMAND_INTERVAL_MS)
            }
        }
    }

    private fun sendActiveCommand() {
        queueBleCommand(activeCommand)
    }

    private fun resetBleWriteQueue(invalidatePendingCallbacks: Boolean) {
        synchronized(bleWriteLock) {
            bleWriteInFlight = false
            pendingBleCommand = null
            bleRetryScheduled = false
            if (invalidatePendingCallbacks) {
                bleWriteToken += 1
            }
        }
    }

    private data class BleWriteJob(
        val gatt: BluetoothGatt,
        val characteristic: BluetoothGattCharacteristic,
        val value: String,
        val token: Long
    )

    private fun queueBleCommand(value: String) {
        val job = synchronized(bleWriteLock) {
            val gatt = bleGatt ?: return
            val characteristic = bleWriteCharacteristic ?: return

            if (bleWriteInFlight) {
                pendingBleCommand = value
                return
            }

            bleWriteInFlight = true
            bleWriteToken += 1
            BleWriteJob(gatt, characteristic, value, bleWriteToken)
        }

        val queued = try {
            writeBleCommand(job.gatt, job.characteristic, job.value)
        } catch (error: Exception) {
            Log.e(BT_TAG, "BLE send failed", error)
            connected = false
            false
        }

        if (queued) {
            // Some write-no-response UART modules do not always invoke onCharacteristicWrite.
            if (job.characteristic.writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                bleHandler.postDelayed({ completeBleWrite(job.token) }, BLE_WRITE_RETRY_MS)
            }
        } else {
            synchronized(bleWriteLock) {
                if (bleWriteToken == job.token) {
                    bleWriteInFlight = false
                    pendingBleCommand = job.value
                }
            }
            schedulePendingBleCommandDrain()
        }
    }

    private fun completeBleWrite(token: Long?) {
        synchronized(bleWriteLock) {
            if (token != null && token != bleWriteToken) {
                return
            }

            bleWriteInFlight = false
        }

        drainPendingBleCommand()
    }

    private fun schedulePendingBleCommandDrain() {
        val shouldSchedule = synchronized(bleWriteLock) {
            if (bleRetryScheduled) {
                false
            } else {
                bleRetryScheduled = true
                true
            }
        }

        if (!shouldSchedule) {
            return
        }

        bleHandler.postDelayed(
            {
                synchronized(bleWriteLock) {
                    bleRetryScheduled = false
                }
                drainPendingBleCommand()
            },
            BLE_WRITE_RETRY_MS
        )
    }

    private fun drainPendingBleCommand() {
        val next = synchronized(bleWriteLock) {
            pendingBleCommand.also { pendingBleCommand = null }
        }

        if (connected && next != null) {
            queueBleCommand(next)
        }
    }

    @SuppressLint("MissingPermission")
    private fun writeBleCommand(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: String
    ): Boolean {
        val bytes = value.toByteArray(Charsets.UTF_8)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val result = gatt.writeCharacteristic(characteristic, bytes, characteristic.writeType)
            if (result == 201) {
                val now = System.currentTimeMillis()
                if (now - lastBleBusyLogMs > 1_000L) {
                    lastBleBusyLogMs = now
                    Log.d(BT_TAG, "BLE write busy; latest command queued")
                }
            } else if (result != BluetoothStatusCodes.SUCCESS) {
                Log.e(BT_TAG, "BLE write failed status=$result")
            }
            result == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            val queued = gatt.writeCharacteristic(characteristic)
            if (!queued) {
                Log.e(BT_TAG, "BLE write queue failed")
            }
            queued
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan(logStopped: Boolean = true) {
        val callback = bleScanCallback ?: return
        val scanner = getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner

        try {
            scanner?.stopScan(callback)
            if (logStopped) {
                Log.d(BT_TAG, "BLE scan stopped")
            }
        } catch (error: Exception) {
            Log.e(BT_TAG, "BLE stopScan failed", error)
        }
        bleScanCallback = null
        bleScanToken += 1
    }
}

private data class DiscoveredBleDevice(
    val device: BluetoothDevice,
    val name: String
)

private data class ParsedCommand(
    val throttle: Int,
    val steer: Int,
    val speed: Int,
    val rotLeft: Boolean,
    val rotRight: Boolean,
    val sonarEnabled: Boolean
)

private data class SonarTelemetry(
    val distanceCm: Int?,
    val blocked: Boolean
)

private fun command(
    throttle: Int,
    steer: Int,
    speed: Int,
    rotLeft: Boolean = false,
    rotRight: Boolean = false,
    sonarEnabled: Boolean = true
): String {
    val left = if (rotLeft) 1 else 0
    val right = if (rotRight) 1 else 0
    val sonar = if (sonarEnabled) 1 else 0
    return "throttle:$throttle,steer:$steer,rot_left:$left,rot_right:$right,speed:$speed,sonar:$sonar\n"
}

private fun parseCommand(command: String): ParsedCommand {
    val values = command
        .trim()
        .split(",")
        .mapNotNull {
            val parts = it.split(":")
            if (parts.size == 2) parts[0] to parts[1].toIntOrNull() else null
        }
        .toMap()

    return ParsedCommand(
        throttle = values["throttle"] ?: 0,
        steer = values["steer"] ?: 0,
        speed = values["speed"] ?: 160,
        rotLeft = (values["rot_left"] ?: 0) != 0,
        rotRight = (values["rot_right"] ?: 0) != 0,
        sonarEnabled = (values["sonar"] ?: 1) != 0
    )
}

private enum class AppSection(val title: String) {
    Controller("Controller"),
    Devices("Devices"),
    Logs("Logs")
}

private enum class ControlIcon {
    Forward,
    Back,
    Left,
    Right,
    RotateLeft,
    RotateRight
}

private data class MotionControl(
    val key: String,
    val icon: ControlIcon,
    val throttle: Int,
    val steer: Int,
    val rotLeft: Boolean = false,
    val rotRight: Boolean = false,
    val speedOverride: Int? = null
)

@Composable
private fun RobotControllerScreen(
    scanBleDevices: ((DiscoveredBleDevice) -> Unit, (String) -> Unit) -> Unit,
    connectBle: (
        BluetoothDevice,
        (String) -> Unit,
        (Boolean) -> Unit,
        (SonarTelemetry) -> Unit
    ) -> Unit,
    disconnect: () -> Unit,
    setMotion: (Int, Int, Boolean, Boolean, Int?) -> Unit,
    setSpeed: (Int) -> Unit,
    setSonarEnabled: (Boolean) -> Unit,
    isConnected: () -> Boolean
) {
    val context = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("Scan for BLE robot module") }
    var connectionLog by remember { mutableStateOf(listOf(BUILD_LABEL)) }
    var devices by remember { mutableStateOf<List<DiscoveredBleDevice>>(emptyList()) }
    var selectedDevice by remember { mutableStateOf<DiscoveredBleDevice?>(null) }
    var connected by remember { mutableStateOf(isConnected()) }
    var speed by remember { mutableFloatStateOf(160f) }
    var sonarTelemetry by remember { mutableStateOf(SonarTelemetry(null, false)) }
    var sonarEnabled by remember { mutableStateOf(true) }
    var section by remember { mutableStateOf(AppSection.Controller) }
    val pressedControls = remember { mutableStateListOf<MotionControl>() }
    val isLandscapeController = section == AppSection.Controller

    fun updateStatus(message: String) {
        status = message
        connectionLog = (connectionLog + message).takeLast(8)
    }

    fun startBleScan() {
        devices = emptyList()
        selectedDevice = null
        scanBleDevices(
            { found ->
                devices = (devices + found).distinctBy { it.device.address }
                status = "Found ${found.name}"
                connectionLog = (connectionLog + "Found ${found.name} ${found.device.address}").takeLast(8)
            },
            ::updateStatus
        )
    }

    fun applyMotionControl(control: MotionControl?) {
        if (control == null) {
            setMotion(0, 0, false, false, null)
        } else {
            setMotion(
                control.throttle,
                control.steer,
                control.rotLeft,
                control.rotRight,
                control.speedOverride
            )
        }
    }

    fun pressControl(control: MotionControl) {
        pressedControls.removeAll { it.key == control.key }
        pressedControls.add(control)
        applyMotionControl(control)
    }

    fun releaseControl(control: MotionControl) {
        pressedControls.removeAll { it.key == control.key }
        applyMotionControl(pressedControls.lastOrNull())
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = requiredBluetoothPermissions().all { permission ->
            result[permission] == true || context.hasPermission(permission)
        }

        if (granted) {
            updateStatus("Bluetooth permissions granted")
            startBleScan()
        } else {
            updateStatus("Bluetooth permissions denied")
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Mecanum",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(text = BUILD_LABEL, style = MaterialTheme.typography.bodySmall)
                    AppSection.entries.forEach { item ->
                        NavigationDrawerItem(
                            label = { Text(item.title) },
                            selected = section == item,
                            onClick = {
                                section = item
                                scope.launch { drawerState.close() }
                            }
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                color = MaterialTheme.colorScheme.background
            ) {
                if (isLandscapeController) {
                    LandscapeControllerSection(
                        connected = connected,
                        speed = speed,
                        sonarTelemetry = sonarTelemetry,
                        sonarEnabled = sonarEnabled,
                        onSonarEnabledChange = { enabled ->
                            sonarEnabled = enabled
                            setSonarEnabled(enabled)
                        },
                        onSpeedSelect = {
                            speed = it.toFloat()
                            setSpeed(it)
                            applyMotionControl(pressedControls.lastOrNull())
                        },
                        onControlPress = ::pressControl,
                        onControlRelease = ::releaseControl,
                        onMenuClick = { scope.launch { drawerState.open() } }
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        AppHeader(
                            title = section.title,
                            onMenuClick = { scope.launch { drawerState.open() } }
                        )

                        when (section) {
                            AppSection.Controller -> PortraitControllerSection(
                                connected = connected,
                                speed = speed,
                                onSpeedChange = {
                                    speed = it
                                    setSpeed(it.roundToInt())
                                    applyMotionControl(pressedControls.lastOrNull())
                                },
                                onControlPress = ::pressControl,
                                onControlRelease = ::releaseControl
                            )

                            AppSection.Devices -> DevicesSection(
                                status = status,
                                devices = devices,
                                selectedDevice = selectedDevice,
                                connected = connected,
                                onScanClick = {
                                    if (hasRequiredBluetoothPermissions(context)) {
                                        startBleScan()
                                    } else {
                                        updateStatus("Requesting Bluetooth permissions...")
                                        permissionLauncher.launch(requiredBluetoothPermissions())
                                    }
                                },
                            onDisconnectClick = {
                                disconnect()
                                connected = false
                                sonarTelemetry = SonarTelemetry(null, false)
                                updateStatus("Disconnected")
                                },
                            onDeviceSelect = { device ->
                                selectedDevice = device
                                updateStatus("Selected ${device.name}")
                            },
                            onConnectDevice = { device ->
                                selectedDevice = device
                                connectBle(
                                    device.device,
                                    ::updateStatus,
                                    { isConnected ->
                                        connected = isConnected
                                        if (!isConnected) {
                                            sonarTelemetry = SonarTelemetry(null, false)
                                        }
                                    },
                                    { telemetry -> sonarTelemetry = telemetry }
                                )
                            }
                        )

                            AppSection.Logs -> LogsSection(
                                logs = connectionLog,
                                onClearLogs = {
                                    connectionLog = listOf(BUILD_LABEL)
                                    status = "Logs cleared"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(title: String, onMenuClick: () -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        OutlinedButton(
            onClick = {
                context.performControlHaptic()
                onMenuClick()
            }
        ) {
            Text("Menu")
        }
    }
}

@Composable
private fun PortraitControllerSection(
    connected: Boolean,
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    onControlPress: (MotionControl) -> Unit,
    onControlRelease: (MotionControl) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(
            text = if (connected) "Robot connected" else "Robot disconnected",
            style = MaterialTheme.typography.bodyLarge
        )
        SpeedControl(speed = speed, onSpeedChange = onSpeedChange)
        PortraitMovementPad(
            enabled = connected,
            onControlPress = onControlPress,
            onControlRelease = onControlRelease
        )
    }
}

@Composable
private fun LandscapeControllerSection(
    connected: Boolean,
    speed: Float,
    sonarTelemetry: SonarTelemetry,
    sonarEnabled: Boolean,
    onSonarEnabledChange: (Boolean) -> Unit,
    onSpeedSelect: (Int) -> Unit,
    onControlPress: (MotionControl) -> Unit,
    onControlRelease: (MotionControl) -> Unit,
    onMenuClick: () -> Unit
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedButton(
                onClick = {
                    context.performControlHaptic()
                    onMenuClick()
                }
            ) {
                Text("Menu")
            }
            Text(
                text = if (connected) "Connected" else "Disconnected",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            SpeedButtons(
                selectedSpeed = speed.roundToInt(),
                onSpeedSelect = onSpeedSelect
            )
        }

        LandscapeMovementPad(
            enabled = connected,
            sonarTelemetry = sonarTelemetry,
            sonarEnabled = sonarEnabled,
            onSonarEnabledChange = onSonarEnabledChange,
            onControlPress = onControlPress,
            onControlRelease = onControlRelease,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DevicesSection(
    status: String,
    devices: List<DiscoveredBleDevice>,
    selectedDevice: DiscoveredBleDevice?,
    connected: Boolean,
    onScanClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onDeviceSelect: (DiscoveredBleDevice) -> Unit,
    onConnectDevice: (DiscoveredBleDevice) -> Unit
) {
    Column(
        modifier = Modifier.widthIn(max = 720.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = if (connected) "Connected" else "Not connected",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(text = status, style = MaterialTheme.typography.bodyMedium)

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onScanClick) {
                        Text("Scan")
                    }
                    OutlinedButton(
                        enabled = connected,
                        onClick = onDisconnectClick
                    ) {
                        Text("Disconnect")
                    }
                }
            }
        }

        Text("Devices", fontWeight = FontWeight.SemiBold)

        if (devices.isEmpty()) {
            Text(
                text = "No devices yet. Tap Scan to search for your HC-06 module.",
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            devices.forEach { device ->
                DeviceRow(
                    device = device,
                    selected = selectedDevice?.device?.address == device.device.address,
                    connected = connected && selectedDevice?.device?.address == device.device.address,
                    onSelect = { onDeviceSelect(device) },
                    onConnect = { onConnectDevice(device) }
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(
    device: DiscoveredBleDevice,
    selected: Boolean,
    connected: Boolean,
    onSelect: () -> Unit,
    onConnect: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        onClick = onSelect
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (selected) "${device.name}  selected" else device.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(text = device.device.address, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                enabled = !connected,
                onClick = onConnect
            ) {
                Text(if (connected) "Connected" else "Connect")
            }
        }
    }
}

@Composable
private fun LogsSection(logs: List<String>, onClearLogs: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Connection log", fontWeight = FontWeight.SemiBold)
            OutlinedButton(onClick = onClearLogs) {
                Text("Clear")
            }
        }

        logs.forEach { line ->
            Text(text = line, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SpeedControl(speed: Float, onSpeedChange: (Float) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Speed ${speed.roundToInt()}", fontWeight = FontWeight.SemiBold)
        Slider(
            value = speed,
            onValueChange = onSpeedChange,
            valueRange = 90f..230f
        )
    }
}

@Composable
private fun SpeedButtons(selectedSpeed: Int, onSpeedSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpeedButton("Slow", 120, selectedSpeed, onSpeedSelect)
        SpeedButton("Normal", 170, selectedSpeed, onSpeedSelect)
        SpeedButton("Turbo", 230, selectedSpeed, onSpeedSelect)
    }
}

@Composable
private fun SpeedButton(
    label: String,
    speed: Int,
    selectedSpeed: Int,
    onSpeedSelect: (Int) -> Unit
) {
    val context = LocalContext.current
    val selected = selectedSpeed == speed
    if (selected) {
        Button(
            modifier = Modifier
                .width(96.dp)
                .height(44.dp),
            onClick = {
                context.performControlHaptic()
                onSpeedSelect(speed)
            }
        ) {
            Text(label)
        }
    } else {
        OutlinedButton(
            modifier = Modifier
                .width(96.dp)
                .height(44.dp),
            onClick = {
                context.performControlHaptic()
                onSpeedSelect(speed)
            }
        ) {
            Text(label)
        }
    }
}

@Composable
private fun PortraitMovementPad(
    enabled: Boolean,
    onControlPress: (MotionControl) -> Unit,
    onControlRelease: (MotionControl) -> Unit
) {
    val forward = remember { MotionControl("forward", ControlIcon.Forward, throttle = 100, steer = 0) }
    val left = remember { MotionControl("left", ControlIcon.Left, throttle = 0, steer = 100) }
    val right = remember { MotionControl("right", ControlIcon.Right, throttle = 0, steer = -100) }
    val back = remember { MotionControl("back", ControlIcon.Back, throttle = -100, steer = 0) }
    val rotateLeft = remember {
        MotionControl("rotate_left", ControlIcon.RotateLeft, throttle = 0, steer = 0, rotLeft = true)
    }
    val rotateRight = remember {
        MotionControl("rotate_right", ControlIcon.RotateRight, throttle = 0, steer = 0, rotRight = true)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ControlButton("Forward", forward, enabled, onControlPress, onControlRelease)

        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ControlButton("Left", left, enabled, onControlPress, onControlRelease)
            ControlButton("Right", right, enabled, onControlPress, onControlRelease)
        }

        ControlButton("Back", back, enabled, onControlPress, onControlRelease)

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ControlButton("Rotate L", rotateLeft, enabled, onControlPress, onControlRelease)
            ControlButton("Rotate R", rotateRight, enabled, onControlPress, onControlRelease)
        }
    }
}

@Composable
private fun LandscapeMovementPad(
    enabled: Boolean,
    sonarTelemetry: SonarTelemetry,
    sonarEnabled: Boolean,
    onSonarEnabledChange: (Boolean) -> Unit,
    onControlPress: (MotionControl) -> Unit,
    onControlRelease: (MotionControl) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        RotatePad(
            enabled = enabled,
            onControlPress = onControlPress,
            onControlRelease = onControlRelease
        )
        SonarDisplay(
            connected = enabled,
            telemetry = sonarTelemetry,
            sonarEnabled = sonarEnabled,
            onSonarEnabledChange = onSonarEnabledChange
        )
        DrivePad(
            enabled = enabled,
            onControlPress = onControlPress,
            onControlRelease = onControlRelease
        )
    }
}

@Composable
private fun SonarDisplay(
    connected: Boolean,
    telemetry: SonarTelemetry,
    sonarEnabled: Boolean,
    onSonarEnabledChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val hasReading = connected && telemetry.distanceCm != null
    val blocked = sonarEnabled && hasReading && telemetry.blocked
    val containerColor = when {
        blocked -> MaterialTheme.colorScheme.errorContainer
        hasReading -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
    val contentColor = when {
        blocked -> MaterialTheme.colorScheme.onErrorContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Card(
            modifier = Modifier
                .width(140.dp)
                .height(100.dp),
            colors = CardDefaults.cardColors(
                containerColor = containerColor,
                contentColor = contentColor
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "SONIC",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = telemetry.distanceCm?.takeIf { connected }?.let { "$it cm" } ?: "-- cm",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = when {
                        !sonarEnabled -> "AUTO STOP OFF"
                        !connected -> "DISCONNECTED"
                        telemetry.distanceCm == null -> "NO DATA"
                        telemetry.blocked -> "BLOCKED"
                        else -> "CLEAR"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (sonarEnabled) {
            Button(
                modifier = Modifier
                    .width(140.dp)
                    .height(40.dp),
                onClick = {
                    context.performControlHaptic()
                    onSonarEnabledChange(false)
                }
            ) {
                Text("AUTO STOP ON")
            }
        } else {
            OutlinedButton(
                modifier = Modifier
                    .width(140.dp)
                    .height(40.dp),
                onClick = {
                    context.performControlHaptic()
                    onSonarEnabledChange(true)
                }
            ) {
                Text("AUTO STOP OFF")
            }
        }
    }
}

@Composable
private fun RotatePad(
    enabled: Boolean,
    onControlPress: (MotionControl) -> Unit,
    onControlRelease: (MotionControl) -> Unit,
    modifier: Modifier = Modifier
) {
    val rotateLeft = remember {
        MotionControl("rotate_left", ControlIcon.RotateLeft, throttle = 0, steer = 0, rotLeft = true)
    }
    val rotateRight = remember {
        MotionControl("rotate_right", ControlIcon.RotateRight, throttle = 0, steer = 0, rotRight = true)
    }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            ControlButton("Rotate L", rotateLeft, enabled, onControlPress, onControlRelease, compact = true)
            ControlButton("Rotate R", rotateRight, enabled, onControlPress, onControlRelease, compact = true)
        }
    }
}

@Composable
private fun DrivePad(
    enabled: Boolean,
    onControlPress: (MotionControl) -> Unit,
    onControlRelease: (MotionControl) -> Unit,
    modifier: Modifier = Modifier
) {
    val forward = remember { MotionControl("forward", ControlIcon.Forward, throttle = 100, steer = 0) }
    val left = remember { MotionControl("left", ControlIcon.Left, throttle = 0, steer = 100) }
    val right = remember { MotionControl("right", ControlIcon.Right, throttle = 0, steer = -100) }
    val back = remember { MotionControl("back", ControlIcon.Back, throttle = -100, steer = 0) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        ControlButton("Forward", forward, enabled, onControlPress, onControlRelease, compact = true)

        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            ControlButton("Left", left, enabled, onControlPress, onControlRelease, compact = true)
            ControlButton("Right", right, enabled, onControlPress, onControlRelease, compact = true)
        }

        ControlButton("Back", back, enabled, onControlPress, onControlRelease, compact = true)
    }
}

@Composable
private fun ControlButton(
    label: String,
    control: MotionControl,
    enabled: Boolean,
    onControlPress: (MotionControl) -> Unit,
    onControlRelease: (MotionControl) -> Unit,
    compact: Boolean = false,
) {
    val context = LocalContext.current
    val currentOnControlPress by rememberUpdatedState(onControlPress)
    val currentOnControlRelease by rememberUpdatedState(onControlRelease)
    val buttonSize = if (compact) 84.dp else 78.dp
    val touchTargetSize = if (compact) 92.dp else 88.dp
    val iconSize = if (compact) 40.dp else 38.dp
    val backgroundColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }

    Box(
        modifier = Modifier
            .size(touchTargetSize)
            .semantics { contentDescription = label }
            .pointerInput(enabled, control.key) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) {
                            return@detectTapGestures
                        }

                        val pressedAt = SystemClock.elapsedRealtime()
                        context.performControlHaptic()
                        currentOnControlPress(control)
                        try {
                            tryAwaitRelease()
                        } finally {
                            val remaining = MIN_CONTROL_COMMAND_MS -
                                (SystemClock.elapsedRealtime() - pressedAt)
                            if (remaining > 0) {
                                withContext(NonCancellable) {
                                    delay(remaining)
                                }
                            }
                            currentOnControlRelease(control)
                        }
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.size(buttonSize),
            shape = CircleShape,
            color = backgroundColor,
            shadowElevation = if (enabled) 6.dp else 0.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                ControlGlyph(
                    icon = control.icon,
                    enabled = enabled,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}

@Composable
private fun ControlGlyph(icon: ControlIcon, enabled: Boolean, modifier: Modifier = Modifier) {
    val color = if (enabled) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }

    Icon(
        imageVector = icon.imageVector(),
        contentDescription = null,
        tint = color,
        modifier = modifier
    )
}

private fun ControlIcon.imageVector(): ImageVector {
    return when (this) {
        ControlIcon.Forward -> Icons.Filled.ArrowUpward
        ControlIcon.Back -> Icons.Filled.ArrowDownward
        ControlIcon.Left -> Icons.AutoMirrored.Filled.ArrowBack
        ControlIcon.Right -> Icons.AutoMirrored.Filled.ArrowForward
        ControlIcon.RotateLeft -> Icons.AutoMirrored.Filled.RotateLeft
        ControlIcon.RotateRight -> Icons.AutoMirrored.Filled.RotateRight
    }
}

private fun Context.performControlHaptic() {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Vibrator::class.java)
    }

    if (!vibrator.hasVibrator()) return

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(
            VibrationEffect.createOneShot(
                CONTROL_HAPTIC_DURATION_MS,
                CONTROL_HAPTIC_AMPLITUDE
            )
        )
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(CONTROL_HAPTIC_DURATION_MS)
    }
}

private fun requiredBluetoothPermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}

private fun hasRequiredBluetoothPermissions(context: Context): Boolean {
    return requiredBluetoothPermissions().all { permission ->
        context.hasPermission(permission)
    }
}

private fun Context.hasPermission(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

@SuppressLint("MissingPermission")
private fun BluetoothDevice.safeName(): String = name ?: "Unknown device"

@SuppressLint("MissingPermission")
private fun ScanResult.displayName(): String? {
    val candidate = scanRecord?.deviceName ?: device.name
    return candidate?.takeIf { it.isNotBlank() && it != "Unknown device" }
}

private fun logGattServices(gatt: BluetoothGatt) {
    gatt.services.forEach { service ->
        Log.d(BT_TAG, "BLE service ${service.uuid}")
        service.characteristics.forEach { characteristic ->
            Log.d(BT_TAG, "BLE char ${characteristic.uuid} properties=${characteristic.properties}")
        }
    }
}

private fun findWritableCharacteristic(services: List<BluetoothGattService>): BluetoothGattCharacteristic? {
    val writable = services
        .flatMap { it.characteristics }
        .filter { characteristic ->
            val properties = characteristic.properties
            properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0 ||
                properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        }

    return writable.maxByOrNull { characteristic ->
        val uuid = characteristic.uuid.toString().lowercase()
        when {
            uuid.contains("0000ffe1") -> 100
            uuid.contains("6e400002") -> 90
            characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 -> 20
            else -> 10
        }
    }
}

private fun findNotifiableCharacteristic(
    services: List<BluetoothGattService>,
    preferredUuid: UUID
): BluetoothGattCharacteristic? {
    return services
        .flatMap { it.characteristics }
        .filter { characteristic ->
            val properties = characteristic.properties
            properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 ||
                properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        }
        .maxByOrNull { characteristic ->
            when {
                characteristic.uuid == preferredUuid -> 100
                characteristic.uuid.toString().lowercase().contains("0000ffe1") -> 90
                else -> 10
            }
        }
}

@SuppressLint("MissingPermission")
private fun enableNotifications(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic
): Boolean {
    if (!gatt.setCharacteristicNotification(characteristic, true)) return false

    val descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID)
        ?: return false
    val value = if (
        characteristic.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
    ) {
        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
    } else {
        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
    }

    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
    } else {
        @Suppress("DEPRECATION")
        descriptor.value = value
        @Suppress("DEPRECATION")
        gatt.writeDescriptor(descriptor)
    }
}

private fun parseSonarTelemetry(line: String): SonarTelemetry? {
    val values = line
        .split(",")
        .mapNotNull { item ->
            val parts = item.split(":", limit = 2)
            if (parts.size == 2) parts[0] to parts[1].toIntOrNull() else null
        }
        .toMap()

    val rawDistance = values["distance"] ?: return null
    return SonarTelemetry(
        distanceCm = rawDistance.takeIf { it >= 0 },
        blocked = values["blocked"] == 1
    )
}

private fun UUID.shortName(): String {
    return if (toString().lowercase().contains("0000ffe1")) {
        "FFE1"
    } else {
        toString().take(8)
    }
}

private fun Int.connectionStateName(): String {
    return when (this) {
        BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
        BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
        BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
        BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
        else -> "UNKNOWN($this)"
    }
}
