package com.example.powerstation

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

data class BleDeviceUi(
    val name: String,
    val address: String,
    val rssi: Int,
    val device: BluetoothDevice,
    val lastSeenMs: Long
)

data class StationSettings(
    val type: String,
    val apiVersion: Int,
    val lowCutVoltageV: Double?,
    val fullVoltageV: Double?,
    val fullCurrentA: Double?,
    val chargeEfficiency: Double?,
    val lowSocPercent: Double?,
    val learningCorrectionAlpha: Double?,
    val powerLimitW: Double?,
    val etaAveragingSeconds: Double?,
    val etaIdleHoldSeconds: Double?,
    val smallScreenTimeoutSec: Double?,
    val mainScreenTimeoutSec: Double?,
    val fanMinPercent: Double?,
    val fanStartPercent: Double?,
    val fanStartBoostMs: Double?,
    val fanOffTemperatureC: Double?,
    val fanOnTemperatureC: Double?,
    val fanFullTemperatureC: Double?
)

data class PowerStatus(
    val type: String,
    val apiVersion: Int,
    val firmwareVersion: String,
    val systemState: String,
    val powerState: String,
    val socPercent: Double,
    val voltageV: Double,
    val currentA: Double,
    val powerW: Double,
    val averagedPowerW: Double,
    val currentStoredWh: Double,
    val learnedCapacityWh: Double,
    val estimatedTimeHours: Double,
    val learningActive: Boolean,
    val learningDischargeWh: Double,
    val learnedCycles: Int,
    val tempPowerC: Double?,
    val tempAirC: Double?,
    val fanPercent: Int,
    val thermalFault: Boolean,
    val mosfetEnabled: Boolean,
    val bluetoothEnabled: Boolean,
    val bluetoothConnected: Boolean
)

enum class MainTab {
    Dashboard,
    Controls,
    Settings
}

class MainActivity : ComponentActivity() {

    companion object {
        private const val SUPPORTED_API_VERSION = 7
        private const val SCAN_TIMEOUT_MS = 12_000L
        private const val TELEMETRY_TIMEOUT_MS = 5_000L
        private const val TELEMETRY_CHECK_INTERVAL_MS = 1_000L

        private val SERVICE_UUID: UUID =
            UUID.fromString("6f2a0001-5a3d-4e2c-9a73-1b21d9b00001")
        private val STATUS_CHAR_UUID: UUID =
            UUID.fromString("6f2a0002-5a3d-4e2c-9a73-1b21d9b00001")
        private val COMMAND_CHAR_UUID: UUID =
            UUID.fromString("6f2a0003-5a3d-4e2c-9a73-1b21d9b00001")
        private val SETTINGS_CHAR_UUID: UUID =
            UUID.fromString("6f2a0004-5a3d-4e2c-9a73-1b21d9b00001")
        private val CLIENT_CONFIG_DESCRIPTOR_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val devices = mutableStateListOf<BleDeviceUi>()
    private val hasPermissionsState = mutableStateOf(false)
    private val isScanningState = mutableStateOf(false)
    private val isConnectedState = mutableStateOf(false)
    private val statusTextState = mutableStateOf("Не подключено")
    private val errorMessageState = mutableStateOf<String?>(null)
    private val boundDeviceNameState = mutableStateOf<String?>(null)
    private val boundDeviceAddressState = mutableStateOf<String?>(null)
    private val connectedDeviceAddressState = mutableStateOf<String?>(null)
    private val powerStatusState = mutableStateOf<PowerStatus?>(null)
    private val stationSettingsState = mutableStateOf<StationSettings?>(null)
    private val selectedTabState = mutableStateOf(MainTab.Dashboard)

    private val handler = Handler(Looper.getMainLooper())
    private var scanCallback: ScanCallback? = null
    private var bluetoothGatt: BluetoothGatt? = null
    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var settingsCharacteristic: BluetoothGattCharacteristic? = null
    private var autoConnectStarted = false
    private var scanTargetAddress: String? = null
    private var manualDisconnectRequested = false
    private var lastStatusReceivedMs = 0L

    private val telemetryWatchdog = object : Runnable {
        override fun run() {
            if (!isConnectedState.value) {
                return
            }

            val elapsed = System.currentTimeMillis() - lastStatusReceivedMs
            if (lastStatusReceivedMs > 0L && elapsed > TELEMETRY_TIMEOUT_MS) {
                handleConnectionLost("Станция перестала передавать данные", reconnect = true)
                return
            }

            handler.postDelayed(this, TELEMETRY_CHECK_INTERVAL_MS)
        }
    }

    private val prefs by lazy {
        getSharedPreferences("power_station_prefs", MODE_PRIVATE)
    }

    private val requiredBlePermissions: Array<String>
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        loadBoundDevice()
        refreshPermissionState()

        val permissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) {
            refreshPermissionState()
            if (hasPermissionsState.value && boundDeviceAddressState.value != null) {
                startAutoConnectToBoundDevice()
            }
        }

        setContent {
            PowerStationScreen(
                hasPermissions = hasPermissionsState.value,
                isScanning = isScanningState.value,
                isConnected = isConnectedState.value,
                statusText = statusTextState.value,
                errorMessage = errorMessageState.value,
                boundDeviceName = boundDeviceNameState.value,
                boundDeviceAddress = boundDeviceAddressState.value,
                connectedDeviceAddress = connectedDeviceAddressState.value,
                devices = devices,
                powerStatus = powerStatusState.value,
                stationSettings = stationSettingsState.value,
                selectedTab = selectedTabState.value,
                onTabSelected = { selectedTabState.value = it },
                onRequestPermissions = { permissionLauncher.launch(requiredBlePermissions) },
                onScanClick = { startManualBindingScan() },
                onRetryConnect = {
                    manualDisconnectRequested = false
                    startAutoConnectToBoundDevice()
                },
                onDisconnectClick = { disconnectFromDevice(manual = true) },
                onUnbindClick = { unbindDevice() },
                onDeviceClick = { device ->
                    connectToDevice(
                        device = device.device,
                        displayName = cleanDeviceName(device.name),
                        saveAsBound = true
                    )
                },
                onSetSetting = { key, value -> sendSetting(key, value) },
                onServiceCommand = { command -> sendServiceCommand(command) },
                onDismissError = { errorMessageState.value = null }
            )
        }

        if (hasPermissionsState.value && boundDeviceAddressState.value != null) {
            startAutoConnectToBoundDevice()
        }
    }

    private fun hasAllBlePermissions(): Boolean = requiredBlePermissions.all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun refreshPermissionState() {
        hasPermissionsState.value = hasAllBlePermissions()
    }

    private fun loadBoundDevice() {
        boundDeviceNameState.value = prefs.getString("bound_device_name", null)
        boundDeviceAddressState.value = prefs.getString("bound_device_address", null)
    }

    private fun saveBoundDevice(name: String, address: String) {
        prefs.edit()
            .putString("bound_device_name", name)
            .putString("bound_device_address", address)
            .apply()

        boundDeviceNameState.value = name
        boundDeviceAddressState.value = address
    }

    private fun unbindDevice() {
        disconnectFromDevice(manual = true)

        prefs.edit()
            .remove("bound_device_name")
            .remove("bound_device_address")
            .apply()

        boundDeviceNameState.value = null
        boundDeviceAddressState.value = null
        connectedDeviceAddressState.value = null
        devices.clear()
        autoConnectStarted = false
        scanTargetAddress = null
        selectedTabState.value = MainTab.Dashboard
        setStatusText("Станция отвязана")
    }

    private fun startAutoConnectToBoundDevice() {
        val address = boundDeviceAddressState.value ?: return
        if (!hasAllBlePermissions()) {
            setStatusText("Нужны разрешения Bluetooth")
            return
        }
        if (autoConnectStarted || isConnectedState.value || isScanningState.value) {
            return
        }

        manualDisconnectRequested = false
        autoConnectStarted = true
        scanTargetAddress = address
        setStatusText("Поиск привязанной станции...")
        startBleScan(targetAddress = address)
    }

    private fun startManualBindingScan() {
        manualDisconnectRequested = false
        autoConnectStarted = false
        scanTargetAddress = null
        setStatusText("Поиск устройства...")
        startBleScan(targetAddress = null)
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan(targetAddress: String?) {
        if (!hasAllBlePermissions()) {
            setStatusText("Нужны разрешения Bluetooth")
            return
        }

        val bluetoothAdapter = getSystemService(BluetoothManager::class.java).adapter
        if (bluetoothAdapter == null) {
            setStatusText("Bluetooth не поддерживается")
            return
        }
        if (!bluetoothAdapter.isEnabled) {
            setStatusText("Bluetooth выключен")
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        if (scanner == null) {
            setStatusText("BLE-сканер недоступен")
            return
        }

        stopBleScan(clearTarget = false)
        if (targetAddress == null) {
            devices.clear()
        }
        scanTargetAddress = targetAddress

        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val device = result.device
                val address = device.address ?: return
                val name = result.scanRecord?.deviceName ?: device.name ?: "Unknown"
                val foundDevice = BleDeviceUi(
                    name = name,
                    address = address,
                    rssi = result.rssi,
                    device = device,
                    lastSeenMs = System.currentTimeMillis()
                )

                runOnUiThread {
                    if (targetAddress == null && cleanDeviceName(name) == "PowerBank") {
                        addOrUpdateDevice(foundDevice)
                    }

                    val target = scanTargetAddress
                    if (target != null && address.equals(target, ignoreCase = true)) {
                        scanTargetAddress = null
                        stopBleScan(clearTarget = false)
                        connectToDevice(
                            device = foundDevice.device,
                            displayName = cleanDeviceName(foundDevice.name),
                            saveAsBound = false
                        )
                    }
                }
            }

            override fun onScanFailed(errorCode: Int) {
                runOnUiThread {
                    isScanningState.value = false
                    scanCallback = null
                    autoConnectStarted = false
                    setStatusText("Ошибка поиска: $errorCode")
                }
            }
        }

        scanCallback = callback
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, callback)
        isScanningState.value = true
        setStatusText(
            if (targetAddress == null) "Идёт поиск..." else "Поиск привязанной станции..."
        )

        handler.postDelayed({
            if (isScanningState.value && scanCallback == callback) {
                val wasAutoConnect = scanTargetAddress != null
                stopBleScan(clearTarget = true)
                autoConnectStarted = false
                setStatusText(
                    if (wasAutoConnect) "Привязанная станция не найдена" else "Поиск завершён"
                )
            }
        }, SCAN_TIMEOUT_MS)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan(clearTarget: Boolean = true) {
        val callback = scanCallback
        if (callback != null && hasAllBlePermissions()) {
            val scanner = getSystemService(BluetoothManager::class.java)
                .adapter
                ?.bluetoothLeScanner
            scanner?.stopScan(callback)
        }
        scanCallback = null
        isScanningState.value = false
        if (clearTarget) {
            scanTargetAddress = null
        }
    }

    @SuppressLint("MissingPermission")
    private fun connectToDevice(
        device: BluetoothDevice,
        displayName: String,
        saveAsBound: Boolean
    ) {
        if (!hasAllBlePermissions()) {
            setStatusText("Нужны разрешения Bluetooth")
            return
        }

        stopBleScan(clearTarget = true)
        setStatusText("Подключение к $displayName...")
        closeCurrentGatt()
        clearStationData()
        manualDisconnectRequested = false

        bluetoothGatt = device.connectGatt(
            this,
            false,
            object : BluetoothGattCallback() {
                override fun onConnectionStateChange(
                    gatt: BluetoothGatt,
                    status: Int,
                    newState: Int
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        handleConnectionLost("Ошибка подключения: $status", reconnect = true, gatt = gatt)
                        return
                    }

                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            setStatusText("Настройка соединения...")
                            if (!gatt.requestMtu(517)) {
                                gatt.discoverServices()
                            }
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> {
                            handleConnectionLost(
                                message = "Соединение со станцией разорвано",
                                reconnect = !manualDisconnectRequested,
                                gatt = gatt
                            )
                        }
                    }
                }

                override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        showError("Не удалось установить MTU 517: $status")
                    }
                    gatt.discoverServices()
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        handleConnectionLost("Ошибка поиска BLE-сервисов: $status", true, gatt)
                        return
                    }

                    val service = gatt.getService(SERVICE_UUID)
                    if (service == null) {
                        handleConnectionLost("Устройство не поддерживает API PowerStation", false, gatt)
                        return
                    }

                    val statusChar = service.getCharacteristic(STATUS_CHAR_UUID)
                    val commandChar = service.getCharacteristic(COMMAND_CHAR_UUID)
                    val settingsChar = service.getCharacteristic(SETTINGS_CHAR_UUID)
                    if (statusChar == null || commandChar == null || settingsChar == null) {
                        handleConnectionLost("BLE-характеристики API v7 не найдены", false, gatt)
                        return
                    }

                    statusCharacteristic = statusChar
                    commandCharacteristic = commandChar
                    settingsCharacteristic = settingsChar

                    if (saveAsBound) {
                        saveBoundDevice(
                            name = displayName.ifBlank { "PowerBank" },
                            address = device.address
                        )
                    }

                    autoConnectStarted = false
                    selectedTabState.value = MainTab.Dashboard
                    setStatusText("Подписка на данные станции...")
                    subscribeToNotifications(gatt, statusChar)
                }

                override fun onDescriptorWrite(
                    gatt: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int
                ) {
                    if (descriptor.uuid != CLIENT_CONFIG_DESCRIPTOR_UUID) {
                        return
                    }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        handleConnectionLost("Ошибка подписки на BLE-данные: $status", true, gatt)
                        return
                    }

                    when (descriptor.characteristic.uuid) {
                        STATUS_CHAR_UUID -> {
                            val settingsChar = settingsCharacteristic
                            if (settingsChar == null) {
                                handleConnectionLost("Канал настроек недоступен", true, gatt)
                            } else {
                                subscribeToNotifications(gatt, settingsChar)
                            }
                        }

                        SETTINGS_CHAR_UUID -> {
                            setConnectedState(true, device.address)
                            setStatusText("Online")
                            lastStatusReceivedMs = System.currentTimeMillis()
                            startTelemetryWatchdog()
                            sendCommand("get all")
                        }
                    }
                }

                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray
                ) {
                    when (characteristic.uuid) {
                        STATUS_CHAR_UUID -> handleStatusBytes(value)
                        SETTINGS_CHAR_UUID -> handleSettingsBytes(value)
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    when (characteristic.uuid) {
                        STATUS_CHAR_UUID -> handleStatusBytes(characteristic.value)
                        SETTINGS_CHAR_UUID -> handleSettingsBytes(characteristic.value)
                    }
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        showError("Команда не отправлена: BLE-код $status")
                    }
                }
            },
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @SuppressLint("MissingPermission")
    private fun disconnectFromDevice(manual: Boolean) {
        manualDisconnectRequested = manual
        stopTelemetryWatchdog()
        stopBleScan()
        clearStationData()
        setConnectedState(false, null)
        autoConnectStarted = false

        val gatt = bluetoothGatt
        if (gatt != null) {
            gatt.disconnect()
            handler.postDelayed({
                if (bluetoothGatt == gatt) {
                    closeGatt(gatt)
                }
            }, 500)
        }

        setStatusText(if (manual) "Отключено" else "Соединение потеряно")
    }

    private fun handleConnectionLost(
        message: String,
        reconnect: Boolean,
        gatt: BluetoothGatt? = bluetoothGatt
    ) {
        runOnUiThread {
            stopTelemetryWatchdog()
            if (gatt != null) {
                closeGatt(gatt)
            } else {
                closeCurrentGatt()
            }
            clearStationData()
            setConnectedState(false, null)
            selectedTabState.value = MainTab.Dashboard
            autoConnectStarted = false
            setStatusText(message)

            if (reconnect && !manualDisconnectRequested && boundDeviceAddressState.value != null) {
                handler.postDelayed({ startAutoConnectToBoundDevice() }, 1_000)
            }
        }
    }

    private fun startTelemetryWatchdog() {
        handler.removeCallbacks(telemetryWatchdog)
        handler.postDelayed(telemetryWatchdog, TELEMETRY_CHECK_INTERVAL_MS)
    }

    private fun stopTelemetryWatchdog() {
        handler.removeCallbacks(telemetryWatchdog)
        lastStatusReceivedMs = 0L
    }

    private fun clearStationData() {
        powerStatusState.value = null
        stationSettingsState.value = null
    }

    private fun closeCurrentGatt() {
        bluetoothGatt?.let { closeGatt(it) }
    }

    private fun closeGatt(gatt: BluetoothGatt) {
        gatt.close()
        if (bluetoothGatt == gatt) {
            bluetoothGatt = null
        }
        statusCharacteristic = null
        commandCharacteristic = null
        settingsCharacteristic = null
    }

    private fun setConnectedState(connected: Boolean, address: String?) {
        runOnUiThread {
            isConnectedState.value = connected
            connectedDeviceAddressState.value = address
        }
    }

    private fun setStatusText(text: String) {
        runOnUiThread { statusTextState.value = text }
    }

    private fun showError(text: String) {
        runOnUiThread { errorMessageState.value = text }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeToNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            handleConnectionLost("Не удалось включить BLE-уведомления", true, gatt)
            return
        }

        val descriptor = characteristic.getDescriptor(CLIENT_CONFIG_DESCRIPTOR_UUID)
        if (descriptor == null) {
            handleConnectionLost("BLE descriptor 0x2902 не найден", true, gatt)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun sendServiceCommand(command: String) {
        if (!isConnectedState.value) {
            showError("Станция не подключена")
            return
        }
        sendCommand(command)
    }

    private fun sendSetting(key: String, value: Double) {
        if (!isConnectedState.value) {
            showError("Станция не подключена")
            return
        }

        val validationError = validateSetting(key, value)
        if (validationError != null) {
            showError(validationError)
            return
        }

        sendCommand("set $key=${formatCommandNumber(value)}")
    }

    private fun validateSetting(key: String, value: Double): String? {
        val status = powerStatusState.value
        val settings = stationSettingsState.value

        fun range(min: Double, max: Double): String? =
            if (value < min || value > max) {
                "Значение должно быть от ${formatRangeValue(min)} до ${formatRangeValue(max)}"
            } else {
                null
            }

        when (key) {
            "smallScreenTimeoutSec", "mainScreenTimeoutSec" -> {
                if (value % 1.0 != 0.0) return "Тайм-аут должен быть целым числом"
                if (value !in listOf(10.0, 30.0, 60.0, 300.0, 900.0)) {
                    return "Допустимы только 10, 30, 60, 300 или 900 секунд"
                }
            }

            "lowSocPercent" -> return range(5.0, 50.0)
            "powerLimitW" -> return range(20.0, 300.0)
            "lowCutVoltageV" -> return range(8.0, 12.0)
            "currentStoredWh" -> {
                val max = status?.learnedCapacityWh?.coerceAtMost(500.0) ?: 500.0
                return range(0.0, max)
            }

            "fullVoltageV" -> return range(13.6, 14.8)
            "fullCurrentA" -> return range(0.05, 2.0)
            "etaAveragingSeconds" -> {
                if (value !in listOf(15.0, 30.0, 45.0, 60.0, 120.0)) {
                    return "Допустимы только 15, 30, 45, 60 или 120 секунд"
                }
            }

            "etaIdleHoldSeconds" -> return range(0.0, 120.0)
            "learnedCapacityWh" -> return range(150.0, 500.0)
            "learningCorrectionAlpha" -> return range(0.05, 0.50)
            "fanMinPercent" -> {
                val error = range(20.0, 100.0)
                if (error != null) return error
                val start = settings?.fanStartPercent
                if (start != null && value > start) {
                    return "Минимальная скорость не может быть выше стартовой"
                }
            }

            "fanStartPercent" -> {
                val error = range(40.0, 100.0)
                if (error != null) return error
                val min = settings?.fanMinPercent
                if (min != null && value < min) {
                    return "Стартовая скорость не может быть ниже минимальной"
                }
            }

            "fanStartBoostMs" -> {
                if (value % 1.0 != 0.0) return "Длительность импульса должна быть целым числом"
                return range(100.0, 5000.0)
            }

            "fanOffTemperatureC" -> {
                val error = range(0.0, 100.0)
                if (error != null) return error
                val on = settings?.fanOnTemperatureC
                if (on != null && value >= on) {
                    return "Температура выключения должна быть ниже температуры включения"
                }
            }

            "fanOnTemperatureC" -> {
                val error = range(0.0, 100.0)
                if (error != null) return error
                val off = settings?.fanOffTemperatureC
                val full = settings?.fanFullTemperatureC
                if (off != null && value <= off) {
                    return "Температура включения должна быть выше температуры выключения"
                }
                if (full != null && value >= full) {
                    return "Температура включения должна быть ниже температуры максимальной скорости"
                }
            }

            "fanFullTemperatureC" -> {
                val error = range(0.0, 100.0)
                if (error != null) return error
                val on = settings?.fanOnTemperatureC
                if (on != null && value <= on) {
                    return "Температура максимальной скорости должна быть выше температуры включения"
                }
            }
        }

        return null
    }

    @SuppressLint("MissingPermission")
    private fun sendCommand(command: String) {
        val gatt = bluetoothGatt
        val characteristic = commandCharacteristic
        if (gatt == null || characteristic == null || !isConnectedState.value) {
            showError("Канал команд не готов")
            return
        }

        val data = command.toByteArray(Charsets.UTF_8)
        val accepted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = data
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }

        if (!accepted) {
            showError("Android не принял BLE-команду к отправке")
        }
    }

    private fun handleStatusBytes(value: ByteArray) {
        val obj = parseJson(value, "статуса") ?: return
        when (obj.optString("type", "status")) {
            "result" -> handleCommandResult(obj)
            "status" -> {
                val parsed = parsePowerStatus(obj)
                if (parsed == null) {
                    showError("Ошибка разбора статуса станции")
                    return
                }
                runOnUiThread {
                    lastStatusReceivedMs = System.currentTimeMillis()
                    powerStatusState.value = parsed
                    statusTextState.value = "Online"
                }
            }
        }
    }

    private fun handleSettingsBytes(value: ByteArray) {
        val obj = parseJson(value, "настроек") ?: return
        if (obj.optString("type") == "result") {
            handleCommandResult(obj)
            return
        }
        if (obj.optString("type") != "settings") {
            return
        }

        val parsed = parseStationSettings(obj)
        if (parsed == null) {
            showError("Ошибка разбора настроек станции")
            return
        }
        runOnUiThread { stationSettingsState.value = parsed }
    }

    private fun parseJson(value: ByteArray, source: String): JSONObject? = try {
        JSONObject(value.toString(Charsets.UTF_8))
    } catch (_: Exception) {
        showError("Получен некорректный JSON $source")
        null
    }

    private fun handleCommandResult(obj: JSONObject) {
        val ok = obj.optBoolean("ok", false)
        if (!ok) {
            showError(errorDescription(obj.optString("error", "unknown_error")))
            return
        }

        handler.postDelayed({
            if (isConnectedState.value) {
                sendCommand("get all")
            }
        }, 250)
    }

    private fun errorDescription(code: String): String = when (code) {
        "empty_command" -> "Отправлена пустая команда"
        "unknown_command" -> "Прошивка не знает эту команду"
        "expected_key_equals_value" -> "Неверный формат параметра"
        "empty_value" -> "Значение параметра отсутствует"
        "invalid_number" -> "Значение не является числом"
        "integer_required" -> "Требуется целое число"
        "value_out_of_range" -> "Значение вне допустимого диапазона"
        "value_not_allowed" -> "Значение отсутствует в списке допустимых"
        "invalid_setting_relation" -> "Нарушено отношение между связанными параметрами"
        "unknown_setting" -> "Прошивка не знает этот параметр"
        "read_only_auto_setting" -> "Этот параметр рассчитывается автоматически и недоступен для изменения"
        "response_too_large" -> "Ответ станции превысил допустимый размер"
        else -> "Ошибка команды: $code"
    }

    private fun parsePowerStatus(obj: JSONObject): PowerStatus? = try {
        PowerStatus(
            type = obj.optString("type", "status"),
            apiVersion = obj.optInt("apiVersion", 0),
            firmwareVersion = obj.optString("firmwareVersion", "-"),
            systemState = obj.optString("systemState", "-"),
            powerState = obj.optString("powerState", "-"),
            socPercent = obj.optDouble("socPercent", 0.0),
            voltageV = obj.optDouble("voltageV", 0.0),
            currentA = obj.optDouble("currentA", 0.0),
            powerW = obj.optDouble("powerW", 0.0),
            averagedPowerW = obj.optDouble("averagedPowerW", obj.optDouble("powerW", 0.0)),
            currentStoredWh = obj.optDouble("currentStoredWh", 0.0),
            learnedCapacityWh = obj.optDouble("learnedCapacityWh", 0.0),
            estimatedTimeHours = obj.optDouble("estimatedTimeHours", -1.0),
            learningActive = obj.optBoolean("learningActive", false),
            learningDischargeWh = obj.optDouble("learningDischargeWh", 0.0),
            learnedCycles = obj.optInt("learnedCycles", 0),
            tempPowerC = obj.optNullableDouble("tempPowerC"),
            tempAirC = obj.optNullableDouble("tempAirC"),
            fanPercent = obj.optInt("fanPercent", 0),
            thermalFault = obj.optBoolean("thermalFault", false),
            mosfetEnabled = obj.optBoolean("mosfetEnabled", false),
            bluetoothEnabled = obj.optBoolean("bluetoothEnabled", false),
            bluetoothConnected = obj.optBoolean("bluetoothConnected", false)
        )
    } catch (_: Exception) {
        null
    }

    private fun parseStationSettings(obj: JSONObject): StationSettings? = try {
        StationSettings(
            type = obj.optString("type", "settings"),
            apiVersion = obj.optInt("apiVersion", 0),
            lowCutVoltageV = obj.optNullableDouble("lowCutVoltageV"),
            fullVoltageV = obj.optNullableDouble("fullVoltageV"),
            fullCurrentA = obj.optNullableDouble("fullCurrentA"),
            chargeEfficiency = obj.optNullableDouble("chargeEfficiency"),
            lowSocPercent = obj.optNullableDouble("lowSocPercent"),
            learningCorrectionAlpha = obj.optNullableDouble("learningCorrectionAlpha"),
            powerLimitW = obj.optNullableDouble("powerLimitW"),
            etaAveragingSeconds = obj.optNullableDouble("etaAveragingSeconds"),
            etaIdleHoldSeconds = obj.optNullableDouble("etaIdleHoldSeconds"),
            smallScreenTimeoutSec = obj.optNullableDouble("smallScreenTimeoutSec"),
            mainScreenTimeoutSec = obj.optNullableDouble("mainScreenTimeoutSec"),
            fanMinPercent = obj.optNullableDouble("fanMinPercent"),
            fanStartPercent = obj.optNullableDouble("fanStartPercent"),
            fanStartBoostMs = obj.optNullableDouble("fanStartBoostMs"),
            fanOffTemperatureC = obj.optNullableDouble("fanOffTemperatureC"),
            fanOnTemperatureC = obj.optNullableDouble("fanOnTemperatureC"),
            fanFullTemperatureC = obj.optNullableDouble("fanFullTemperatureC")
        )
    } catch (_: Exception) {
        null
    }

    private fun addOrUpdateDevice(device: BleDeviceUi) {
        val index = devices.indexOfFirst { it.address == device.address }
        if (index < 0) {
            devices.add(device)
            return
        }

        val old = devices[index]
        val newRssi = if (device.lastSeenMs - old.lastSeenMs >= 1_000L) {
            ((old.rssi * 0.8) + (device.rssi * 0.2)).roundToInt()
        } else {
            old.rssi
        }
        devices[index] = old.copy(
            name = if (cleanDeviceName(old.name) == "Unknown") device.name else old.name,
            rssi = newRssi,
            device = device.device,
            lastSeenMs = device.lastSeenMs
        )
    }

    override fun onDestroy() {
        stopTelemetryWatchdog()
        stopBleScan()
        closeCurrentGatt()
        super.onDestroy()
    }
}

@Composable
fun PowerStationScreen(
    hasPermissions: Boolean,
    isScanning: Boolean,
    isConnected: Boolean,
    statusText: String,
    errorMessage: String?,
    boundDeviceName: String?,
    boundDeviceAddress: String?,
    connectedDeviceAddress: String?,
    devices: List<BleDeviceUi>,
    powerStatus: PowerStatus?,
    stationSettings: StationSettings?,
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    onRequestPermissions: () -> Unit,
    onScanClick: () -> Unit,
    onRetryConnect: () -> Unit,
    onDisconnectClick: () -> Unit,
    onUnbindClick: () -> Unit,
    onDeviceClick: (BleDeviceUi) -> Unit,
    onSetSetting: (String, Double) -> Unit,
    onServiceCommand: (String) -> Unit,
    onDismissError: () -> Unit
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            when {
                !hasPermissions -> PermissionScreen(onRequestPermissions)
                boundDeviceAddress == null -> BindingScreen(
                    isScanning = isScanning,
                    statusText = statusText,
                    devices = devices,
                    onScanClick = onScanClick,
                    onDeviceClick = onDeviceClick
                )
                !isConnected -> BoundStationConnectionScreen(
                    stationName = boundDeviceName ?: "PowerBank",
                    statusText = statusText,
                    isScanning = isScanning,
                    onRetryConnect = onRetryConnect,
                    onUnbindClick = onUnbindClick
                )
                else -> StationMainScreen(
                    boundDeviceName = boundDeviceName,
                    connectedDeviceAddress = connectedDeviceAddress,
                    powerStatus = powerStatus,
                    stationSettings = stationSettings,
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                    onDisconnectClick = onDisconnectClick,
                    onUnbindClick = onUnbindClick,
                    onSetSetting = onSetSetting,
                    onServiceCommand = onServiceCommand
                )
            }
        }

        if (errorMessage != null) {
            AlertDialog(
                onDismissRequest = onDismissError,
                title = { Text("Ошибка") },
                text = { Text(errorMessage) },
                confirmButton = {
                    TextButton(onClick = onDismissError) { Text("Закрыть") }
                }
            )
        }
    }
}

@Composable
fun PermissionScreen(onRequestPermissions: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("PowerStation", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            "Для подключения к станции нужны разрешения Bluetooth.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(22.dp))
        Button(modifier = Modifier.fillMaxWidth(), onClick = onRequestPermissions) {
            Text("Разрешить Bluetooth")
        }
    }
}

@Composable
fun BindingScreen(
    isScanning: Boolean,
    statusText: String,
    devices: List<BleDeviceUi>,
    onScanClick: () -> Unit,
    onDeviceClick: (BleDeviceUi) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(28.dp))
        Text("PowerStation", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Подключение устройства", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))

        Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Первичная настройка", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(
                    "Включи режим подключения на станции и запусти поиск. После выбора станция будет подключаться автоматически.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(18.dp))
                Button(modifier = Modifier.fillMaxWidth(), enabled = !isScanning, onClick = onScanClick) {
                    Text(if (isScanning) "Идёт поиск..." else "Найти станцию")
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        if (isScanning) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(12.dp))
                Text(statusText)
            }
        } else {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = statusText,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (devices.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = "Доступные станции",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            devices.forEach { device ->
                DeviceCard(device = device, onClick = { onDeviceClick(device) })
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
fun BoundStationConnectionScreen(
    stationName: String,
    statusText: String,
    isScanning: Boolean,
    onRetryConnect: () -> Unit,
    onUnbindClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize().padding(22.dp)) {
        Text(stationName, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isScanning) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(18.dp))
                    }
                    Text(statusText, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(18.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isScanning,
                        onClick = onRetryConnect
                    ) {
                        Text(if (isScanning) "Поиск..." else "Повторить подключение")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onUnbindClick) {
                        Text("Отвязать станцию")
                    }
                }
            }
        }
    }
}

@Composable
fun StationMainScreen(
    boundDeviceName: String?,
    connectedDeviceAddress: String?,
    powerStatus: PowerStatus?,
    stationSettings: StationSettings?,
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    onDisconnectClick: () -> Unit,
    onUnbindClick: () -> Unit,
    onSetSetting: (String, Double) -> Unit,
    onServiceCommand: (String) -> Unit
) {
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == MainTab.Dashboard,
                    onClick = { onTabSelected(MainTab.Dashboard) },
                    icon = { Text("⚡") },
                    label = { Text("Станция") }
                )
                NavigationBarItem(
                    selected = selectedTab == MainTab.Controls,
                    onClick = { onTabSelected(MainTab.Controls) },
                    icon = { Text("🎚") },
                    label = { Text("Параметры") }
                )
                NavigationBarItem(
                    selected = selectedTab == MainTab.Settings,
                    onClick = { onTabSelected(MainTab.Settings) },
                    icon = { Text("⚙") },
                    label = { Text("Приложение") }
                )
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            MainTab.Dashboard -> DashboardScreen(
                contentPadding = innerPadding,
                stationName = boundDeviceName ?: "PowerBank",
                status = powerStatus
            )
            MainTab.Controls -> StationControlsScreen(
                contentPadding = innerPadding,
                status = powerStatus,
                settings = stationSettings,
                onSetSetting = onSetSetting,
                onServiceCommand = onServiceCommand
            )
            MainTab.Settings -> SettingsScreen(
                contentPadding = innerPadding,
                boundDeviceName = boundDeviceName,
                connectedDeviceAddress = connectedDeviceAddress,
                onDisconnectClick = onDisconnectClick,
                onUnbindClick = onUnbindClick
            )
        }
    }
}

@Composable
fun DashboardScreen(
    contentPadding: PaddingValues,
    stationName: String,
    status: PowerStatus?
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        HeaderBlock(title = stationName)
        Spacer(Modifier.height(18.dp))
        if (status == null) {
            WaitingStatusCard()
        } else {
            PowerStatusDashboard(status)
        }
    }
}

@Composable
fun HeaderBlock(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(
            modifier = Modifier.weight(1f),
            text = title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        AssistChip(onClick = {}, label = { Text("Online") })
    }
}

@Composable
fun WaitingStatusCard() {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text("Ожидание данных станции", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun PowerStatusDashboard(status: PowerStatus) {
    val socProgress = (status.socPercent / 100.0).toFloat().coerceIn(0f, 1f)
    val capacityBase = status.learnedCapacityWh.takeIf { it > 0.0 }
        ?: status.currentStoredWh.coerceAtLeast(1.0)
    val capacityProgress = (status.currentStoredWh / capacityBase).toFloat().coerceIn(0f, 1f)

    if (status.apiVersion != 7) {
        WarningCard("Получена версия API ${status.apiVersion}; приложение поддерживает API 7")
        Spacer(Modifier.height(12.dp))
    }
    if (status.thermalFault) {
        WarningCard("Ошибка системы охлаждения. Вентилятор переведён на 100%.")
        Spacer(Modifier.height(12.dp))
    }

    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(modifier = Modifier.padding(22.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stateTitle(status.powerState), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("${status.systemState} / ${status.powerState}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    formatNumber(status.socPercent, 1) + "%",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(18.dp))
            LinearProgressIndicator(
                progress = { socProgress },
                modifier = Modifier.fillMaxWidth().height(14.dp)
            )
            Spacer(Modifier.height(22.dp))

            MetricGrid(
                listOf(
                    Triple("Мощность", formatNumber(status.powerW, 1), "W"),
                    Triple("Средняя", formatNumber(status.averagedPowerW, 1), "W"),
                    Triple("Напряжение", formatNumber(status.voltageV, 2), "V"),
                    Triple("Ток", formatNumber(status.currentA, 2), "A"),
                    Triple("Время", formatEta(status.estimatedTimeHours), "")
                )
            )

            Spacer(Modifier.height(22.dp))
            Text("Энергия", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { capacityProgress },
                modifier = Modifier.fillMaxWidth().height(10.dp)
            )
            Spacer(Modifier.height(8.dp))
            MetricRow("Осталось", formatNumber(status.currentStoredWh, 1) + " Wh")
            MetricRow("Обученная ёмкость", formatNumber(capacityBase, 1) + " Wh")

            Spacer(Modifier.height(18.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            Text("Охлаждение", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            MetricRow("Силовой модуль", formatTemperature(status.tempPowerC))
            MetricRow("Выходящий воздух", formatTemperature(status.tempAirC))
            MetricRow("Вентилятор", "${status.fanPercent}%")
            MetricRow("Состояние", if (status.thermalFault) "Ошибка" else "Норма")

            Spacer(Modifier.height(18.dp))
            HorizontalDivider()
            Spacer(Modifier.height(12.dp))
            MetricRow("Версия прошивки", status.firmwareVersion)
            MetricRow("Силовой выход", if (status.mosfetEnabled) "Включён" else "Выключен")
            MetricRow("Bluetooth", if (status.bluetoothConnected) "Подключён" else "Не подключён")
            MetricRow("Обучение ёмкости", if (status.learningActive) "Активно" else "Выключено")
            MetricRow("Энергия цикла", formatNumber(status.learningDischargeWh, 1) + " Wh")
            MetricRow("Циклов обучения", status.learnedCycles.toString())
        }
    }
}

@Composable
fun WarningCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Text(
            modifier = Modifier.padding(16.dp),
            text = text,
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun MetricGrid(items: List<Triple<String, String, String>>) {
    items.chunked(2).forEach { rowItems ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            rowItems.forEach { item ->
                MetricCard(Modifier.weight(1f), item.first, item.second, item.third)
            }
            if (rowItems.size == 1) Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
fun MetricCard(modifier: Modifier, label: String, value: String, unit: String) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (unit.isNotBlank()) {
                    Spacer(Modifier.width(4.dp))
                    Text(unit, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
fun MetricRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(value, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun StationControlsScreen(
    contentPadding: PaddingValues,
    status: PowerStatus?,
    settings: StationSettings?,
    onSetSetting: (String, Double) -> Unit,
    onServiceCommand: (String) -> Unit
) {
    var pendingServiceCommand by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("Параметры станции", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        if (status == null || settings == null) {
            WaitingStatusCard()
            return@Column
        }

        val compatible = status.apiVersion == 7 && settings.apiVersion == 7
        if (!compatible) {
            WarningCard("Редактирование отключено: приложение поддерживает API 7, станция передала status=${status.apiVersion}, settings=${settings.apiVersion}.")
            return@Column
        }

        val storedWhMax = status.learnedCapacityWh.coerceAtMost(500.0).coerceAtLeast(0.0)

        SettingsSection("Быстрые настройки") {
            PresetSetting(
                "Тайм-аут маленького экрана",
                "Через сколько секунд бездействия выключать маленький экран.",
                "smallScreenTimeoutSec",
                settings.smallScreenTimeoutSec,
                listOf(10.0 to "10 с", 30.0 to "30 с", 60.0 to "60 с", 300.0 to "5 мин", 900.0 to "15 мин"),
                defaultLabel = "30 с",
                onSetSetting = onSetSetting
            )
            PresetSetting(
                "Тайм-аут большого экрана",
                "Через сколько секунд бездействия выключать большой экран.",
                "mainScreenTimeoutSec",
                settings.mainScreenTimeoutSec,
                listOf(10.0 to "10 с", 30.0 to "30 с", 60.0 to "60 с", 300.0 to "5 мин", 900.0 to "15 мин"),
                defaultLabel = "30 с",
                onSetSetting = onSetSetting
            )
            SliderSetting(
                "Индикация низкого заряда",
                "Порог включения предупреждения о низком заряде.",
                "lowSocPercent",
                settings.lowSocPercent,
                defaultValue = 15.0,
                unit = "%",
                min = 5.0,
                max = 50.0,
                digits = 0,
                onSetSetting = onSetSetting
            )
        }

        Spacer(Modifier.height(16.dp))
        SettingsSection("Аккумулятор") {
            EditableNumberSetting("Лимит мощности", "Мощность разряда, при превышении которой отключается выход.", "powerLimitW", settings.powerLimitW, "W", 20.0, 300.0, 100.0, 0, onSetSetting)
            EditableNumberSetting("Нижний порог напряжения", "Напряжение отключения выхода для защиты аккумуляторов.", "lowCutVoltageV", settings.lowCutVoltageV, "V", 8.0, 12.0, 11.0, 2, onSetSetting)
            EditableNumberSetting("Текущий запас энергии", "Ручная коррекция расчётного остатка энергии.", "currentStoredWh", status.currentStoredWh, "Wh", 0.0, storedWhMax, storedWhMax, 1, onSetSetting)
        }

        Spacer(Modifier.height(16.dp))
        SettingsSection("Зарядка") {
            EditableNumberSetting("Напряжение полного заряда", "Минимальное напряжение для распознавания полного заряда.", "fullVoltageV", settings.fullVoltageV, "V", 13.6, 14.8, 14.8, 2, onSetSetting)
            EditableNumberSetting("Ток завершения зарядки", "Максимальный ток, при котором заряд считается завершённым.", "fullCurrentA", settings.fullCurrentA, "A", 0.05, 2.0, 0.2, 2, onSetSetting)
            ReadOnlySetting(
                title = "Коэффициент зарядки",
                description = "Рассчитывается и сохраняется прошивкой автоматически.",
                value = formatCurrentValue(settings.chargeEfficiency, 3, "")
            )
        }

        Spacer(Modifier.height(16.dp))
        SettingsSection("Расчёт времени") {
            PresetSetting(
                "Усреднение мощности",
                "Период сглаживания мощности, используемой при расчёте ETA.",
                "etaAveragingSeconds",
                settings.etaAveragingSeconds,
                listOf(15.0 to "15 с", 30.0 to "30 с", 45.0 to "45 с", 60.0 to "60 с", 120.0 to "120 с"),
                defaultLabel = "30 с",
                onSetSetting = onSetSetting
            )
            SliderSetting(
                "Удержание ETA в простое",
                "Сколько сохранять ETA при кратковременном переходе в простой.",
                "etaIdleHoldSeconds",
                settings.etaIdleHoldSeconds,
                defaultValue = 10.0,
                unit = "с",
                min = 0.0,
                max = 120.0,
                digits = 0,
                onSetSetting = onSetSetting
            )
        }

        Spacer(Modifier.height(16.dp))
        SettingsSection("Ёмкость и обучение") {
            EditableNumberSetting("Обученная ёмкость", "Фактическая ёмкость, используемая для расчёта заряда и ETA.", "learnedCapacityWh", status.learnedCapacityWh, "Wh", 150.0, 500.0, 500.0, 0, onSetSetting)
            SliderSetting("Сила коррекции ёмкости", "Доля результата нового цикла в обновлении обученной ёмкости.", "learningCorrectionAlpha", settings.learningCorrectionAlpha, 0.25, "", 0.05, 0.50, 2, onSetSetting)
        }

        Spacer(Modifier.height(16.dp))
        SettingsSection("Охлаждение") {
            EditableNumberSetting("Минимальная скорость вентилятора", "Минимальная мощность вентилятора после успешного запуска.", "fanMinPercent", settings.fanMinPercent, "%", 20.0, 100.0, 40.0, 0, onSetSetting)
            EditableNumberSetting("Стартовая скорость вентилятора", "Мощность вентилятора во время стартового импульса.", "fanStartPercent", settings.fanStartPercent, "%", 40.0, 100.0, 80.0, 0, onSetSetting)
            EditableNumberSetting("Длительность стартового импульса", "Время повышенной мощности при каждом запуске вентилятора.", "fanStartBoostMs", settings.fanStartBoostMs, "ms", 100.0, 5000.0, 1000.0, 0, onSetSetting)
            EditableNumberSetting("Температура выключения", "Ниже этой температуры вентилятор выключается.", "fanOffTemperatureC", settings.fanOffTemperatureC, "°C", 0.0, 100.0, 38.0, 0, onSetSetting)
            EditableNumberSetting("Температура включения", "При этой температуре начинается охлаждение.", "fanOnTemperatureC", settings.fanOnTemperatureC, "°C", 0.0, 100.0, 42.0, 0, onSetSetting)
            EditableNumberSetting("Температура максимальной скорости", "При этой температуре вентилятор переходит на 100%.", "fanFullTemperatureC", settings.fanFullTemperatureC, "°C", 0.0, 100.0, 60.0, 0, onSetSetting)
        }

        Spacer(Modifier.height(16.dp))
        SettingsSection("Служебные действия") {
            Text("Эти команды меняют внутреннее состояние расчёта ёмкости.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Button(modifier = Modifier.fillMaxWidth(), onClick = { pendingServiceCommand = "markFull" }) {
                Text("Отметить аккумулятор полным")
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { pendingServiceCommand = "resetLearning" }) {
                Text("Сбросить текущее обучение")
            }
        }
    }

    val pending = pendingServiceCommand
    if (pending != null) {
        val markFull = pending == "markFull"
        AlertDialog(
            onDismissRequest = { pendingServiceCommand = null },
            title = { Text(if (markFull) "Отметить аккумулятор полным?" else "Сбросить обучение?") },
            text = {
                Text(
                    if (markFull) {
                        "Текущий запас энергии будет установлен равным обученной ёмкости и запустится цикл обучения."
                    } else {
                        "Текущий цикл обучения будет остановлен, а промежуточный результат очищен."
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingServiceCommand = null
                    onServiceCommand(pending)
                }) { Text("Подтвердить") }
            },
            dismissButton = {
                TextButton(onClick = { pendingServiceCommand = null }) { Text("Отмена") }
            }
        )
    }
}

@Composable
fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
        Column(modifier = Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
fun PresetSetting(
    title: String,
    description: String,
    key: String,
    value: Double?,
    presets: List<Pair<Double, String>>,
    defaultLabel: String,
    onSetSetting: (String, Double) -> Unit
) {
    SettingHeader(title, description, formatCurrentValue(value, 0, "с"), "По умолчанию: $defaultLabel")
    presets.chunked(3).forEach { rowPresets ->
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            rowPresets.forEach { (presetValue, label) ->
                val selected = value != null && value.roundToInt() == presetValue.roundToInt()
                if (selected) {
                    Button(modifier = Modifier.weight(1f), onClick = { onSetSetting(key, presetValue) }) { Text(label) }
                } else {
                    OutlinedButton(modifier = Modifier.weight(1f), onClick = { onSetSetting(key, presetValue) }) { Text(label) }
                }
            }
            repeat(3 - rowPresets.size) { Spacer(Modifier.weight(1f)) }
        }
        Spacer(Modifier.height(8.dp))
    }
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
}

@Composable
fun EditableNumberSetting(
    title: String,
    description: String,
    key: String,
    value: Double?,
    unit: String,
    min: Double,
    max: Double,
    defaultValue: Double,
    digits: Int,
    onSetSetting: (String, Double) -> Unit
) {
    var text by remember(key, value) {
        mutableStateOf(value?.let { formatNumber(it, digits) } ?: "")
    }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val integerRequired = key in setOf("fanStartBoostMs", "smallScreenTimeoutSec", "mainScreenTimeoutSec")
    val isValid = parsed != null && parsed in min..max && (!integerRequired || parsed % 1.0 == 0.0)

    SettingHeader(
        title,
        description,
        formatCurrentValue(value, digits, unit),
        "Диапазон: ${formatRangeValue(min)}–${formatRangeValue(max)} $unit; по умолчанию: ${formatNumber(defaultValue, digits)} $unit"
    )
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            modifier = Modifier.weight(1f),
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            suffix = { if (unit.isNotBlank()) Text(unit) },
            isError = text.isNotBlank() && !isValid,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
        )
        Spacer(Modifier.width(10.dp))
        Button(
            modifier = Modifier.padding(top = 8.dp),
            enabled = isValid,
            onClick = { parsed?.let { onSetSetting(key, it) } }
        ) { Text("OK") }
    }
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
}

@Composable
fun SliderSetting(
    title: String,
    description: String,
    key: String,
    value: Double?,
    defaultValue: Double,
    unit: String,
    min: Double,
    max: Double,
    digits: Int,
    onSetSetting: (String, Double) -> Unit
) {
    val safeValue = (value ?: defaultValue).coerceIn(min, max)
    var sliderValue by remember(key, value) { mutableStateOf(safeValue.toFloat()) }
    val displayValue = sliderValue.toDouble()

    SettingHeader(
        title,
        description,
        formatCurrentValue(value, digits, unit),
        "Диапазон: ${formatRangeValue(min)}–${formatRangeValue(max)} $unit; по умолчанию: ${formatNumber(defaultValue, digits)} $unit"
    )
    Text(
        modifier = Modifier.fillMaxWidth(),
        text = formatNumber(displayValue, digits) + if (unit.isNotBlank()) " $unit" else "",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Slider(value = sliderValue, onValueChange = { sliderValue = it }, valueRange = min.toFloat()..max.toFloat())
    Button(modifier = Modifier.fillMaxWidth(), onClick = { onSetSetting(key, displayValue) }) {
        Text("Применить")
    }
    HorizontalDivider(Modifier.padding(vertical = 10.dp))
}

@Composable
fun ReadOnlySetting(title: String, description: String, value: String) {
    SettingHeader(title, description, value, "Только для чтения")
    HorizontalDivider(Modifier.padding(vertical = 6.dp))
}

@Composable
fun SettingHeader(title: String, description: String, current: String, hint: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(2.dp))
    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    Text(current, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
    Text(hint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(10.dp))
}

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    boundDeviceName: String?,
    connectedDeviceAddress: String?,
    onDisconnectClick: () -> Unit,
    onUnbindClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("Приложение", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("Привязанная станция", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                MetricRow("Имя", boundDeviceName ?: "PowerBank")
                MetricRow("Состояние", "Подключена")
                connectedDeviceAddress?.let { MetricRow("Адрес", it) }
                Spacer(Modifier.height(18.dp))
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onDisconnectClick) {
                    Text("Отключиться")
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onUnbindClick) {
                    Text("Отвязать станцию")
                }
            }
        }
    }
}

@Composable
fun DeviceCard(device: BleDeviceUi, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    cleanDeviceName(device.name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text("PowerStation BLE", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            SignalStrengthIndicator(device.rssi)
        }
    }
}

@Composable
fun SignalStrengthIndicator(rssi: Int) {
    val level = signalLevel(rssi)
    Row(
        modifier = Modifier.height(22.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (bar in 1..4) {
            val height = when (bar) { 1 -> 6.dp; 2 -> 10.dp; 3 -> 14.dp; else -> 18.dp }
            Box(
                Modifier.width(4.dp).height(height).background(
                    if (bar <= level) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    MaterialTheme.shapes.extraSmall
                )
            )
        }
    }
}

fun cleanDeviceName(name: String): String = when {
    name.isBlank() || name == "Unknown device" || name == "Unknown" -> "Unknown"
    else -> name
}

fun signalLevel(rssi: Int): Int = when {
    rssi >= -55 -> 4
    rssi >= -70 -> 3
    rssi >= -85 -> 2
    else -> 1
}

fun stateTitle(powerState: String): String = when (powerState.uppercase(Locale.US)) {
    "CHARGE", "CHARGING" -> "Зарядка"
    "DISCHARGE", "DISCHARGING" -> "Питание нагрузки"
    "IDLE" -> "Ожидание"
    "OFF" -> "Выключено"
    else -> "Состояние станции"
}

fun formatCurrentValue(value: Double?, digits: Int, unit: String): String =
    if (value == null) {
        "Текущее: не получено"
    } else {
        "Текущее: ${formatNumber(value, digits)}" + if (unit.isNotBlank()) " $unit" else ""
    }

fun formatNumber(value: Double, digits: Int): String =
    String.format(Locale.US, "%.${digits}f", value)

fun formatRangeValue(value: Double): String =
    if (value % 1.0 == 0.0) {
        String.format(Locale.US, "%.0f", value)
    } else {
        String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    }

fun formatCommandNumber(value: Double): String =
    String.format(Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')

fun formatEta(hours: Double): String {
    if (hours < 0.0 || hours.isNaN() || hours.isInfinite()) return "-"
    val totalMinutes = (hours * 60.0).toInt()
    val days = totalMinutes / (24 * 60)
    val rest = totalMinutes % (24 * 60)
    val h = rest / 60
    val m = rest % 60
    return if (days > 0) "${days}д ${h}ч" else "${h}ч ${m}м"
}

fun formatTemperature(value: Double?): String =
    value?.let { formatNumber(it, 1) + " °C" } ?: "Ошибка датчика"

fun JSONObject.optNullableDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = optDouble(key, Double.NaN)
    return value.takeUnless { it.isNaN() || it.isInfinite() }
}
