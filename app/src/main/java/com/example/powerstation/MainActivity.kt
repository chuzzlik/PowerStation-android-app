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
import com.example.powerstation.ble.PowerStationBleProtocol
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
    val mainScreenTimeoutSec: Double?
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

    private val devices = mutableStateListOf<BleDeviceUi>()

    private val hasPermissionsState = mutableStateOf(false)
    private val isScanningState = mutableStateOf(false)
    private val isConnectedState = mutableStateOf(false)

    private val statusTextState = mutableStateOf("Не подключено")
    private val commandResultState = mutableStateOf<String?>(null)

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

    private val prefs by lazy {
        getSharedPreferences("power_station_prefs", MODE_PRIVATE)
    }

    private val clientConfigDescriptorUuid: UUID =
        UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    private val requiredBlePermissions: Array<String>
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            } else {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
            }
        }

    private fun hasAllBlePermissions(): Boolean {
        return requiredBlePermissions.all { permission ->
            ContextCompat.checkSelfPermission(
                this,
                permission
            ) == PackageManager.PERMISSION_GRANTED
        }
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
                commandResult = commandResultState.value,
                boundDeviceName = boundDeviceNameState.value,
                boundDeviceAddress = boundDeviceAddressState.value,
                connectedDeviceAddress = connectedDeviceAddressState.value,
                devices = devices,
                powerStatus = powerStatusState.value,
                stationSettings = stationSettingsState.value,
                selectedTab = selectedTabState.value,
                onTabSelected = {
                    selectedTabState.value = it
                },
                onRequestPermissions = {
                    permissionLauncher.launch(requiredBlePermissions)
                },
                onScanClick = {
                    startManualBindingScan()
                },
                onRefreshClick = {
                    if (isConnectedState.value) {
                        sendCommand("get all")
                    } else {
                        startAutoConnectToBoundDevice()
                    }
                },
                onDisconnectClick = {
                    disconnectFromDevice()
                },
                onUnbindClick = {
                    unbindDevice()
                },
                onDeviceClick = { device ->
                    connectToDevice(
                        device = device.device,
                        displayName = cleanDeviceName(device.name),
                        saveAsBound = true
                    )
                },
                onSetSetting = { key, value ->
                    sendSetting(key, value)
                },
                onServiceCommand = { command ->
                    sendServiceCommand(command)
                }
            )
        }

        if (hasPermissionsState.value && boundDeviceAddressState.value != null) {
            startAutoConnectToBoundDevice()
        }
    }

    private fun refreshPermissionState() {
        hasPermissionsState.value = hasAllBlePermissions()
    }

    private fun loadBoundDevice() {
        boundDeviceNameState.value = prefs.getString("bound_device_name", null)
        boundDeviceAddressState.value = prefs.getString("bound_device_address", null)
    }

    private fun saveBoundDevice(
        name: String,
        address: String
    ) {
        prefs.edit()
            .putString("bound_device_name", name)
            .putString("bound_device_address", address)
            .apply()

        boundDeviceNameState.value = name
        boundDeviceAddressState.value = address
    }

    private fun unbindDevice() {
        disconnectFromDevice()

        prefs.edit()
            .remove("bound_device_name")
            .remove("bound_device_address")
            .apply()

        boundDeviceNameState.value = null
        boundDeviceAddressState.value = null
        connectedDeviceAddressState.value = null
        powerStatusState.value = null
        stationSettingsState.value = null
        commandResultState.value = null

        devices.clear()

        autoConnectStarted = false
        scanTargetAddress = null

        selectedTabState.value = MainTab.Dashboard
        setStatusText("Станция отвязана")
    }

    private fun startAutoConnectToBoundDevice() {
        val address = boundDeviceAddressState.value ?: return

        if (autoConnectStarted || isConnectedState.value) {
            return
        }

        autoConnectStarted = true
        scanTargetAddress = address

        setStatusText("Поиск привязанной станции...")
        startBleScan(targetAddress = address)
    }

    private fun startManualBindingScan() {
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

        val bluetoothManager = getSystemService(BluetoothManager::class.java)
        val bluetoothAdapter = bluetoothManager.adapter

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
            override fun onScanResult(
                callbackType: Int,
                result: ScanResult
            ) {
                val device = result.device
                val address = device.address ?: return

                val name = result.scanRecord?.deviceName
                    ?: device.name
                    ?: "Unknown"

                val foundDevice = BleDeviceUi(
                    name = name,
                    address = address,
                    rssi = result.rssi,
                    device = device,
                    lastSeenMs = System.currentTimeMillis()
                )

                runOnUiThread {
                    addOrUpdateDevice(foundDevice)

                    val target = scanTargetAddress

                    if (
                        target != null &&
                        address.equals(target, ignoreCase = true)
                    ) {
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

        if (targetAddress == null) {
            setStatusText("Идёт поиск...")
        } else {
            setStatusText("Поиск привязанной станции...")
        }

        handler.postDelayed({
            if (isScanningState.value) {
                val wasAutoConnect = scanTargetAddress != null

                stopBleScan(clearTarget = true)

                if (wasAutoConnect) {
                    autoConnectStarted = false
                    setStatusText("Привязанная станция не найдена")
                } else {
                    setStatusText("Поиск завершён")
                }
            }
        }, 12_000)
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan(clearTarget: Boolean = true) {
        val callback = scanCallback

        if (callback != null && hasAllBlePermissions()) {
            val bluetoothManager = getSystemService(BluetoothManager::class.java)
            val bluetoothAdapter = bluetoothManager.adapter
            val scanner = bluetoothAdapter?.bluetoothLeScanner

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

        bluetoothGatt?.close()
        bluetoothGatt = null

        statusCharacteristic = null
        commandCharacteristic = null
        settingsCharacteristic = null

        powerStatusState.value = null
        stationSettingsState.value = null
        commandResultState.value = null
        isConnectedState.value = false
        connectedDeviceAddressState.value = null

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
                        setStatusText("Ошибка подключения: $status")
                        closeGatt(gatt)
                        autoConnectStarted = false
                        return
                    }

                    when (newState) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            setStatusText("Подключено. Настройка соединения...")

                            val mtuRequested = gatt.requestMtu(517)

                            if (!mtuRequested) {
                                setStatusText("MTU не запрошен. Поиск сервисов...")
                                gatt.discoverServices()
                            }
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> {
                            setStatusText("Отключено")
                            setConnectedState(false, null)
                            closeGatt(gatt)
                            autoConnectStarted = false
                        }
                    }
                }

                override fun onMtuChanged(
                    gatt: BluetoothGatt,
                    mtu: Int,
                    status: Int
                ) {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        setStatusText("Соединение настроено")
                    } else {
                        setStatusText("MTU ошибка: $status")
                    }

                    gatt.discoverServices()
                }

                override fun onServicesDiscovered(
                    gatt: BluetoothGatt,
                    status: Int
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        setStatusText("Ошибка поиска сервисов: $status")
                        return
                    }

                    val service = gatt.getService(PowerStationBleProtocol.SERVICE_UUID)

                    if (service == null) {
                        setStatusText("Это не зарядная станция")
                        return
                    }

                    val statusChar = service.getCharacteristic(
                        PowerStationBleProtocol.STATUS_CHAR_UUID
                    )
                    val commandChar = service.getCharacteristic(
                        PowerStationBleProtocol.COMMAND_CHAR_UUID
                    )
                    val settingsChar = service.getCharacteristic(
                        PowerStationBleProtocol.SETTINGS_CHAR_UUID
                    )

                    if (statusChar == null || commandChar == null || settingsChar == null) {
                        setStatusText("BLE-характеристики API v5 не найдены")
                        return
                    }

                    statusCharacteristic = statusChar
                    commandCharacteristic = commandChar
                    settingsCharacteristic = settingsChar

                    if (saveAsBound) {
                        saveBoundDevice(
                            name = displayName,
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
                    if (descriptor.uuid != clientConfigDescriptorUuid) {
                        return
                    }

                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        setStatusText("Ошибка подписки: $status")
                        return
                    }

                    when (descriptor.characteristic.uuid) {
                        PowerStationBleProtocol.STATUS_CHAR_UUID -> {
                            val settingsChar = settingsCharacteristic
                            if (settingsChar == null) {
                                setStatusText("Характеристика настроек недоступна")
                                return
                            }
                            subscribeToNotifications(gatt, settingsChar)
                        }

                        PowerStationBleProtocol.SETTINGS_CHAR_UUID -> {
                            setConnectedState(true, device.address)
                            setStatusText("Станция подключена")
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
                        PowerStationBleProtocol.STATUS_CHAR_UUID -> handleStatusBytes(value)
                        PowerStationBleProtocol.SETTINGS_CHAR_UUID -> handleSettingsBytes(value)
                    }
                }

                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic
                ) {
                    when (characteristic.uuid) {
                        PowerStationBleProtocol.STATUS_CHAR_UUID -> handleStatusBytes(characteristic.value)
                        PowerStationBleProtocol.SETTINGS_CHAR_UUID -> handleSettingsBytes(characteristic.value)
                    }
                }

                override fun onCharacteristicWrite(
                    gatt: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int
                ) {
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        setStatusText("Команда не отправлена: $status")
                    }
                }
            },
            BluetoothDevice.TRANSPORT_LE
        )
    }

    @SuppressLint("MissingPermission")
    private fun disconnectFromDevice() {
        val gatt = bluetoothGatt

        if (gatt == null) {
            setConnectedState(false, null)
            return
        }

        setStatusText("Отключение...")
        gatt.disconnect()
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

    private fun setConnectedState(
        connected: Boolean,
        address: String?
    ) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            isConnectedState.value = connected
            connectedDeviceAddressState.value = address
        } else {
            runOnUiThread {
                isConnectedState.value = connected
                connectedDeviceAddressState.value = address
            }
        }
    }

    private fun setStatusText(text: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            statusTextState.value = text
        } else {
            runOnUiThread {
                statusTextState.value = text
            }
        }
    }

    private fun setCommandResult(text: String?) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            commandResultState.value = text
        } else {
            runOnUiThread {
                commandResultState.value = text
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun subscribeToNotifications(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        val localNotificationEnabled = gatt.setCharacteristicNotification(
            characteristic,
            true
        )

        if (!localNotificationEnabled) {
            setStatusText("Не удалось включить уведомления")
            return
        }

        val descriptor = characteristic.getDescriptor(clientConfigDescriptorUuid)

        if (descriptor == null) {
            setStatusText("BLE2902 descriptor не найден")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(
                descriptor,
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            )
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
    }

    private fun sendServiceCommand(command: String) {
        if (!isConnectedState.value) {
            setCommandResult("Станция не подключена")
            return
        }

        setCommandResult("Отправлено: $command")
        sendCommand(command)
    }

    private fun sendSetting(
        key: String,
        value: Double
    ) {
        if (!isConnectedState.value) {
            setCommandResult("Станция не подключена")
            return
        }

        val validationError = validateSetting(key, value)

        if (validationError != null) {
            setCommandResult(validationError)
            return
        }

        val commandValue = formatCommandNumber(value)
        val command = "set $key=$commandValue"

        setCommandResult("Отправлено: $command")
        sendCommand(command)
    }

    private fun validateSetting(
        key: String,
        value: Double
    ): String? {
        val status = powerStatusState.value
        val settings = stationSettingsState.value

        fun range(min: Double, max: Double): String? {
            return if (value < min || value > max) {
                "Значение должно быть от ${formatRangeValue(min)} до ${formatRangeValue(max)}"
            } else {
                null
            }
        }

        when (key) {
            "smallScreenTimeoutSec",
            "mainScreenTimeoutSec" -> {
                if (value !in listOf(10.0, 30.0, 60.0, 300.0, 900.0)) {
                    return "Выбери одно из доступных значений тайм-аута"
                }
            }

            "lowSocPercent" -> return range(5.0, 50.0)
            "powerLimitW" -> return range(20.0, 300.0)

            "lowCutVoltageV" -> {
                val base = range(8.0, 12.0)
                if (base != null) return base

                val full = settings?.fullVoltageV
                if (full != null && value >= full) {
                    return "Нижняя отсечка должна быть ниже напряжения полного заряда"
                }
            }

            "currentStoredWh" -> {
                val max = status?.learnedCapacityWh
                    ?.takeIf { it > 0.0 }
                    ?.coerceAtMost(500.0)
                    ?: 500.0
                return range(0.0, max)
            }

            "fullVoltageV" -> {
                val base = range(13.6, 14.8)
                if (base != null) return base

                val lowCut = settings?.lowCutVoltageV
                if (lowCut != null && value <= lowCut) {
                    return "Напряжение полного заряда должно быть выше нижней отсечки"
                }
            }

            "fullCurrentA" -> return range(0.05, 2.0)
            "chargeEfficiency" -> return range(0.80, 1.00)

            "etaAveragingSeconds" -> {
                if (value !in listOf(15.0, 30.0, 45.0, 60.0, 120.0)) {
                    return "Выбери одно из доступных значений усреднения"
                }
            }

            "etaIdleHoldSeconds" -> return range(0.0, 120.0)
            "learnedCapacityWh" -> return range(250.0, 500.0)
            "learningCorrectionAlpha" -> return range(0.05, 0.50)
        }

        return null
    }
    
    @SuppressLint("MissingPermission")
    private fun sendCommand(command: String) {
        val gatt = bluetoothGatt

        if (gatt == null) {
            setStatusText("Станция не подключена")
            return
        }

        val characteristic = commandCharacteristic

        if (characteristic == null) {
            setStatusText("Канал команд не готов")
            return
        }

        val data = command.toByteArray(Charsets.UTF_8)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic,
                data,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

            @Suppress("DEPRECATION")
            characteristic.value = data

            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
    }

    private fun handleStatusBytes(value: ByteArray) {
        val json = value.toString(Charsets.UTF_8)

        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            setStatusText("Ошибка JSON статуса")
            return
        }

        when (obj.optString("type", "status")) {
            "result" -> handleCommandResult(obj)
            "status" -> {
                val parsed = parsePowerStatus(obj)
                runOnUiThread {
                    if (parsed != null) {
                        powerStatusState.value = parsed
                        statusTextState.value = "Данные обновлены"
                    } else {
                        statusTextState.value = "Ошибка разбора статуса"
                    }
                }
            }
        }
    }

    private fun handleSettingsBytes(value: ByteArray) {
        val json = value.toString(Charsets.UTF_8)

        val obj = try {
            JSONObject(json)
        } catch (e: Exception) {
            setStatusText("Ошибка JSON настроек")
            return
        }

        if (obj.optString("type") != "settings") {
            return
        }

        val parsed = parseStationSettings(obj)

        runOnUiThread {
            if (parsed != null) {
                stationSettingsState.value = parsed
                statusTextState.value = "Настройки обновлены"
            } else {
                statusTextState.value = "Ошибка разбора настроек"
            }
        }
    }

    private fun handleCommandResult(obj: JSONObject) {
        val ok = obj.optBoolean("ok", false)
        val command = obj.optString("command", "")
        val error = obj.optString("error", "")

        runOnUiThread {
            commandResultState.value = if (ok) {
                if (command.isBlank()) {
                    "Команда выполнена"
                } else {
                    "Выполнено: $command"
                }
            } else {
                if (error.isBlank()) {
                    "Команда отклонена"
                } else {
                    "Ошибка: $error"
                }
            }
        }

        if (ok) {
            handler.postDelayed({
                sendCommand("get all")
            }, 400)
        }
    }

    private fun parsePowerStatus(obj: JSONObject): PowerStatus? {
        return try {
            PowerStatus(
                type = obj.optString("type", "status"),
                apiVersion = obj.optInt("apiVersion", 1),
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
                mosfetEnabled = obj.optBoolean("mosfetEnabled", false),
                bluetoothEnabled = obj.optBoolean("bluetoothEnabled", false),
                bluetoothConnected = obj.optBoolean("bluetoothConnected", false)
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseStationSettings(obj: JSONObject): StationSettings? {
        return try {
            StationSettings(
                type = obj.optString("type", "settings"),
                apiVersion = obj.optInt("apiVersion", 1),
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
                mainScreenTimeoutSec = obj.optNullableDouble("mainScreenTimeoutSec")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun addOrUpdateDevice(device: BleDeviceUi) {
        val index = devices.indexOfFirst { it.address == device.address }

        if (index < 0) {
            devices.add(device)
            return
        }

        val old = devices[index]

        val oldNameIsUnknown = old.name.isBlank() ||
                old.name == "Unknown device" ||
                old.name == "Unknown"

        val newNameIsKnown = device.name.isNotBlank() &&
                device.name != "Unknown device" &&
                device.name != "Unknown"

        val shouldUpdateName = oldNameIsUnknown && newNameIsKnown
        val shouldUpdateSignal = device.lastSeenMs - old.lastSeenMs >= 1_000L

        if (!shouldUpdateName && !shouldUpdateSignal) {
            return
        }

        val newName = if (shouldUpdateName) {
            device.name
        } else {
            old.name
        }

        val newRssi = if (shouldUpdateSignal) {
            ((old.rssi * 0.8) + (device.rssi * 0.2)).roundToInt()
        } else {
            old.rssi
        }

        devices[index] = old.copy(
            name = newName,
            rssi = newRssi,
            device = device.device,
            lastSeenMs = device.lastSeenMs
        )
    }

    override fun onDestroy() {
        super.onDestroy()

        stopBleScan()

        bluetoothGatt?.close()
        bluetoothGatt = null

        statusCharacteristic = null
        commandCharacteristic = null
        settingsCharacteristic = null
    }
}

@Composable
fun PowerStationScreen(
    hasPermissions: Boolean,
    isScanning: Boolean,
    isConnected: Boolean,
    statusText: String,
    commandResult: String?,
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
    onRefreshClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onUnbindClick: () -> Unit,
    onDeviceClick: (BleDeviceUi) -> Unit,
    onSetSetting: (String, Double) -> Unit,
    onServiceCommand: (String) -> Unit
) {
    MaterialTheme {
        Surface(
            modifier = Modifier.fillMaxSize()
        ) {
            if (!hasPermissions) {
                PermissionScreen(
                    onRequestPermissions = onRequestPermissions
                )
            } else if (boundDeviceAddress == null) {
                BindingScreen(
                    isScanning = isScanning,
                    statusText = statusText,
                    devices = devices,
                    onScanClick = onScanClick,
                    onDeviceClick = onDeviceClick
                )
            } else {
                StationMainScreen(
                    isConnected = isConnected,
                    statusText = statusText,
                    commandResult = commandResult,
                    boundDeviceName = boundDeviceName,
                    connectedDeviceAddress = connectedDeviceAddress,
                    powerStatus = powerStatus,
                    stationSettings = stationSettings,
                    selectedTab = selectedTab,
                    onTabSelected = onTabSelected,
                    onRefreshClick = onRefreshClick,
                    onDisconnectClick = onDisconnectClick,
                    onUnbindClick = onUnbindClick,
                    onSetSetting = onSetSetting,
                    onServiceCommand = onServiceCommand
                )
            }
        }
    }
}

@Composable
fun PermissionScreen(
    onRequestPermissions: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "PowerStation",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "Для подключения к зарядной станции нужны разрешения Bluetooth.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(22.dp))

        Button(
            modifier = Modifier.fillMaxWidth(),
            onClick = onRequestPermissions
        ) {
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
    val filteredDevices = devices

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(28.dp))

        Text(
            text = "PowerStation",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Подключение устройства",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(28.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = "Первичная настройка",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Включи режим подключения на станции, затем запусти поиск. После выбора устройство будет сохранено, а следующие подключения будут выполняться автоматически.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isScanning,
                    onClick = onScanClick
                ) {
                    Text(
                        if (isScanning) {
                            "Идёт поиск..."
                        } else {
                            "Найти устройство"
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        if (isScanning) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .width(22.dp)
                        .height(22.dp),
                    strokeWidth = 2.dp
                )

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = statusText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        if (filteredDevices.isNotEmpty()) {
            Text(
                modifier = Modifier.fillMaxWidth(),
                text = "Доступные устройства",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(10.dp))

            filteredDevices.forEach { device ->
                DeviceCard(
                    device = device,
                    isConnected = false,
                    onClick = {
                        onDeviceClick(device)
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@Composable
fun StationMainScreen(
    isConnected: Boolean,
    statusText: String,
    commandResult: String?,
    boundDeviceName: String?,
    connectedDeviceAddress: String?,
    powerStatus: PowerStatus?,
    stationSettings: StationSettings?,
    selectedTab: MainTab,
    onTabSelected: (MainTab) -> Unit,
    onRefreshClick: () -> Unit,
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
                isConnected = isConnected,
                statusText = statusText,
                boundDeviceName = boundDeviceName,
                powerStatus = powerStatus,
                onRefreshClick = onRefreshClick
            )

            MainTab.Controls -> StationControlsScreen(
                contentPadding = innerPadding,
                isConnected = isConnected,
                statusText = statusText,
                commandResult = commandResult,
                powerStatus = powerStatus,
                stationSettings = stationSettings,
                onRefreshClick = onRefreshClick,
                onSetSetting = onSetSetting,
                onServiceCommand = onServiceCommand
            )

            MainTab.Settings -> SettingsScreen(
                contentPadding = innerPadding,
                isConnected = isConnected,
                statusText = statusText,
                boundDeviceName = boundDeviceName,
                connectedDeviceAddress = connectedDeviceAddress,
                onRefreshClick = onRefreshClick,
                onDisconnectClick = onDisconnectClick,
                onUnbindClick = onUnbindClick
            )
        }
    }
}

@Composable
fun DashboardScreen(
    contentPadding: PaddingValues,
    isConnected: Boolean,
    statusText: String,
    boundDeviceName: String?,
    powerStatus: PowerStatus?,
    onRefreshClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        HeaderBlock(
            title = boundDeviceName ?: "PowerStation",
            subtitle = if (isConnected) {
                "Станция подключена"
            } else {
                statusText
            },
            connected = isConnected
        )

        Spacer(modifier = Modifier.height(18.dp))

        if (powerStatus == null) {
            WaitingStatusCard(
                isConnected = isConnected,
                statusText = statusText,
                onRefreshClick = onRefreshClick
            )
        } else {
            PowerStatusDashboard(
                status = powerStatus,
                onRefreshClick = onRefreshClick
            )
        }
    }
}

@Composable
fun StationControlsScreen(
    contentPadding: PaddingValues,
    isConnected: Boolean,
    statusText: String,
    commandResult: String?,
    powerStatus: PowerStatus?,
    stationSettings: StationSettings?,
    onRefreshClick: () -> Unit,
    onSetSetting: (String, Double) -> Unit,
    onServiceCommand: (String) -> Unit
) {
    var pendingServiceCommand by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            text = "Параметры станции",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = if (isConnected) {
                "Текущие значения получаются напрямую из станции"
            } else {
                statusText
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (commandResult != null) {
            Spacer(modifier = Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    modifier = Modifier.padding(14.dp),
                    text = commandResult,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (powerStatus == null || stationSettings == null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        text = "Данные ещё не получены",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Приложению нужны телеметрия и полный набор настроек API v5.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = onRefreshClick
                    ) {
                        Text("Получить данные")
                    }
                }
            }

            return
        }

        val settings = stationSettings
        val storedWhMax = powerStatus.learnedCapacityWh
            .takeIf { it > 0.0 }
            ?.coerceAtMost(500.0)
            ?: 500.0

        SettingsSection(
            title = "Быстрые настройки"
        ) {
            PresetSetting(
                title = "Тайм-аут маленького экрана",
                description = "Через сколько времени узкий экран гаснет в простое",
                key = "smallScreenTimeoutSec",
                value = settings.smallScreenTimeoutSec,
                presets = listOf(
                    10.0 to "10 с",
                    30.0 to "30 с",
                    60.0 to "60 с",
                    300.0 to "5 мин",
                    900.0 to "15 мин"
                ),
                enabled = isConnected,
                onSetSetting = onSetSetting
            )

            PresetSetting(
                title = "Тайм-аут большого экрана",
                description = "Через сколько времени основной экран гаснет",
                key = "mainScreenTimeoutSec",
                value = settings.mainScreenTimeoutSec,
                presets = listOf(
                    10.0 to "10 с",
                    30.0 to "30 с",
                    60.0 to "60 с",
                    300.0 to "5 мин",
                    900.0 to "15 мин"
                ),
                enabled = isConnected,
                onSetSetting = onSetSetting
            )

            SliderSetting(
                title = "Индикация низкого заряда",
                description = "Порог включения предупреждающей индикации. Станцию не выключает",
                key = "lowSocPercent",
                value = settings.lowSocPercent,
                fallbackValue = 15.0,
                unit = "%",
                min = 5.0,
                max = 50.0,
                digits = 0,
                enabled = isConnected,
                onSetSetting = onSetSetting
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(
            title = "Аккумулятор"
        ) {
            EditableNumberSetting(
                title = "Лимит мощности",
                description = "Порог аварийного отключения при перегрузке во время разряда",
                key = "powerLimitW",
                value = settings.powerLimitW,
                unit = "W",
                min = 20.0,
                max = 300.0,
                digits = 0,
                enabled = isConnected,
                onSetSetting = onSetSetting
            )

            EditableNumberSetting(
                title = "Нижний порог напряжения",
                description = "Аварийное отключение при глубоком разряде аккумуляторной сборки",
                key = "lowCutVoltageV",
                value = settings.lowCutVoltageV,
                unit = "V",
                min = 8.0,
                max = 12.0,
                digits = 2,
                enabled = isConnected,
                onSetSetting = onSetSetting
            )

            EditableNumberSetting(
                title = "Текущий запас энергии",
                description = "Сервисная корректировка расчётного количества энергии в аккумуляторе",
                key = "currentStoredWh",
                value = powerStatus.currentStoredWh,
                unit = "Wh",
                min = 0.0,
                max = storedWhMax,
                digits = 1,
                enabled = isConnected,
                onSetSetting = onSetSetting
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(
            title = "Зарядка"
        ) {
            EditableNumberSetting(
                title = "Напряжение полного заряда",
                description = "Минимальное напряжение для определения завершения зарядки",
                key = "fullVoltageV",
                value = settings.fullVoltageV,
                unit = "V",
                min = 13.6,
                max = 14.8,
                digits = 2,
                enabled = isConnected,
                onSetSetting = onSetSetting
            )

            EditableNumberSetting(
                title = "Ток завершения зарядки",
                description = "При меньшем токе и достаточном напряжении аккумулятор считается полным",
                key = "fullCurrentA",
                value = settings.fullCurrentA,
                unit = "A",
                min = 0.05,
                max = 2.0,
                digits = 2,
                enabled = isConnected,
                onSetSetting = onSetSetting
            )

            SliderSetting(
                title = "Эффективность зарядки",
                description = "Коэффициент учёта принятой энергии и расчёта времени до полного заряда",
                key = "chargeEfficiency",
                value = settings.chargeEfficiency,
                fallbackValue = 0.95,
                unit = "",
                min = 0.80,
                max = 1.00,
                digits = 2,
                enabled = isConnected,
                onSetSetting = onSetSetting
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(
            title = "Расчёт времени"
        ) {
            PresetSetting(
                title = "Усреднение мощности",
                description = "Большее значение делает ETA стабильнее, но медленнее реагирует",
                key = "etaAveragingSeconds",
                value = settings.etaAveragingSeconds,
                presets = listOf(
                    15.0 to "15 с",
                    30.0 to "30 с",
                    45.0 to "45 с",
                    60.0 to "60 с",
                    120.0 to "120 с"
                ),
                enabled = isConnected,
                onSetSetting = onSetSetting
            )

            PresetSliderSetting(
                title = "Удержание ETA в простое",
                description = "Сколько сохранять последнее ETA при кратком переходе в IDLE",
                key = "etaIdleHoldSeconds",
                value = settings.etaIdleHoldSeconds,
                presets = listOf(
                    0.0 to "0 с",
                    10.0 to "10 с",
                    15.0 to "15 с",
                    30.0 to "30 с",
                    60.0 to "60 с"
                ),
                unit = "с",
                min = 0.0,
                max = 120.0,
                digits = 0,
                enabled = isConnected,
                onSetSetting = onSetSetting
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(
            title = "Ёмкость и обучение"
        ) {
            EditableNumberSetting(
                title = "Обученная ёмкость",
                description = "Принудительно меняет фактическую ёмкость и напрямую влияет на расчёт заряда",
                key = "learnedCapacityWh",
                value = powerStatus.learnedCapacityWh,
                unit = "Wh",
                min = 250.0,
                max = 500.0,
                digits = 0,
                enabled = isConnected,
                onSetSetting = onSetSetting
            )

            SliderSetting(
                title = "Сила коррекции ёмкости",
                description = "Доля нового измерения при обновлении обученной ёмкости",
                key = "learningCorrectionAlpha",
                value = settings.learningCorrectionAlpha,
                fallbackValue = 0.25,
                unit = "",
                min = 0.05,
                max = 0.50,
                digits = 2,
                enabled = isConnected,
                onSetSetting = onSetSetting
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        SettingsSection(
            title = "Служебные действия"
        ) {
            Text(
                text = "Эти команды меняют внутреннее состояние расчёта ёмкости.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = isConnected,
                onClick = { pendingServiceCommand = "markFull" }
            ) {
                Text("Отметить аккумулятор полным")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = isConnected,
                onClick = { pendingServiceCommand = "resetLearning" }
            ) {
                Text("Сбросить текущее обучение")
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                modifier = Modifier.fillMaxWidth(),
                enabled = isConnected,
                onClick = { onServiceCommand("save") }
            ) {
                Text("Принудительно сохранить")
            }
        }
    }

    val pending = pendingServiceCommand
    if (pending != null) {
        val isMarkFull = pending == "markFull"

        AlertDialog(
            onDismissRequest = { pendingServiceCommand = null },
            title = {
                Text(if (isMarkFull) "Отметить аккумулятор полным?" else "Сбросить обучение?")
            },
            text = {
                Text(
                    if (isMarkFull) {
                        "Текущий запас энергии будет установлен равным обученной ёмкости и запустится цикл обучения."
                    } else {
                        "Текущий цикл обучения будет остановлен, а данные последнего измерения очищены."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingServiceCommand = null
                        onServiceCommand(pending)
                    }
                ) {
                    Text("Подтвердить")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { pendingServiceCommand = null }
                ) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
fun PresetSetting(
    title: String,
    description: String,
    key: String,
    value: Double?,
    presets: List<Pair<Double, String>>,
    enabled: Boolean,
    onSetSetting: (String, Double) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = if (value == null) "Текущее: не получено" else "Текущее: ${formatTimeoutValue(value)}",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        presets.chunked(3).forEach { rowPresets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowPresets.forEach { (presetValue, label) ->
                    val selected = value != null && value.roundToInt() == presetValue.roundToInt()

                    if (selected) {
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = enabled,
                            onClick = { onSetSetting(key, presetValue) }
                        ) {
                            Text(label)
                        }
                    } else {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = enabled,
                            onClick = { onSetSetting(key, presetValue) }
                        ) {
                            Text(label)
                        }
                    }
                }

                repeat(3 - rowPresets.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
fun PresetSliderSetting(
    title: String,
    description: String,
    key: String,
    value: Double?,
    presets: List<Pair<Double, String>>,
    unit: String,
    min: Double,
    max: Double,
    digits: Int,
    enabled: Boolean,
    onSetSetting: (String, Double) -> Unit
) {
    val safeValue = (value ?: min).coerceIn(min, max)
    var sliderValue by remember(key, value) { mutableStateOf(safeValue.toFloat()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = formatCurrentValue(value, digits, unit),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        presets.chunked(3).forEach { rowPresets ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowPresets.forEach { (presetValue, label) ->
                    val selected = value != null && value.roundToInt() == presetValue.roundToInt()
                    if (selected) {
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = enabled,
                            onClick = { onSetSetting(key, presetValue) }
                        ) { Text(label) }
                    } else {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            enabled = enabled,
                            onClick = { onSetSetting(key, presetValue) }
                        ) { Text(label) }
                    }
                }
                repeat(3 - rowPresets.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        Text(
            text = "Другое значение: ${formatNumber(sliderValue.toDouble(), digits)} $unit",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )

        Slider(
            enabled = enabled,
            value = sliderValue,
            onValueChange = { sliderValue = it },
            valueRange = min.toFloat()..max.toFloat()
        )

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            onClick = { onSetSetting(key, sliderValue.toDouble()) }
        ) {
            Text("Применить другое значение")
        }
    }

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

fun formatTimeoutValue(value: Double): String {
    val seconds = value.roundToInt()
    return if (seconds >= 60 && seconds % 60 == 0) {
        "${seconds / 60} мин"
    } else {
        "$seconds с"
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier.padding(18.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(12.dp))

            content()
        }
    }
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
    digits: Int,
    enabled: Boolean,
    onSetSetting: (String, Double) -> Unit
) {
    var text by remember(key, value) {
        mutableStateOf(
            if (value == null) {
                ""
            } else {
                formatNumber(value, digits)
            }
        )
    }

    val normalized = text.replace(',', '.')
    val parsed = normalized.toDoubleOrNull()
    val isValid = parsed != null && parsed >= min && parsed <= max
    val currentText = formatCurrentValue(value, digits, unit)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(2.dp))

        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = currentText,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            OutlinedTextField(
                modifier = Modifier.weight(1f),
                enabled = enabled,
                value = text,
                onValueChange = {
                    text = it
                },
                singleLine = true,
                suffix = {
                    if (unit.isNotBlank()) {
                        Text(unit)
                    }
                },
                isError = text.isNotBlank() && !isValid,
                supportingText = {
                    Text(
                        if (text.isNotBlank() && !isValid) {
                            "Допустимо: ${formatRangeValue(min)}–${formatRangeValue(max)}"
                        } else {
                            "Диапазон: ${formatRangeValue(min)}–${formatRangeValue(max)}"
                        }
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Decimal
                )
            )

            Spacer(modifier = Modifier.width(10.dp))

            Button(
                modifier = Modifier.padding(top = 8.dp),
                enabled = enabled && isValid,
                onClick = {
                    if (parsed != null) {
                        onSetSetting(key, parsed)
                    }
                }
            ) {
                Text("OK")
            }
        }
    }

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
fun SliderSetting(
    title: String,
    description: String,
    key: String,
    value: Double?,
    fallbackValue: Double?,
    unit: String,
    min: Double,
    max: Double,
    digits: Int,
    enabled: Boolean,
    onSetSetting: (String, Double) -> Unit
) {
    val safeValue = (value ?: fallbackValue ?: min).coerceIn(min, max)

    var sliderValue by remember(key, value, fallbackValue) {
        mutableStateOf(safeValue.toFloat())
    }

    val displayValue = sliderValue.toDouble()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = formatCurrentValue(value, digits, unit),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
            }

            Text(
                text = formatNumber(displayValue, digits) + if (unit.isNotBlank()) " $unit" else "",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Slider(
            enabled = enabled,
            value = sliderValue,
            onValueChange = {
                sliderValue = it
            },
            valueRange = min.toFloat()..max.toFloat()
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatRangeValue(min),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Text(
                text = formatRangeValue(max),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            onClick = {
                onSetSetting(key, sliderValue.toDouble())
            }
        ) {
            Text("Применить")
        }
    }

    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
fun HeaderBlock(
    title: String,
    subtitle: String,
    connected: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(
            modifier = Modifier.weight(1f)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AssistChip(
            onClick = {},
            label = {
                Text(
                    if (connected) {
                        "Online"
                    } else {
                        "Offline"
                    }
                )
            }
        )
    }
}

@Composable
fun WaitingStatusCard(
    isConnected: Boolean,
    statusText: String,
    onRefreshClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier.padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (!isConnected) {
                CircularProgressIndicator()
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = statusText,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(14.dp))

            Button(
                onClick = onRefreshClick
            ) {
                Text(
                    if (isConnected) {
                        "Обновить статус"
                    } else {
                        "Повторить подключение"
                    }
                )
            }
        }
    }
}

@Composable
fun PowerStatusDashboard(
    status: PowerStatus,
    onRefreshClick: () -> Unit
) {
    val socProgress = (status.socPercent / 100.0)
        .toFloat()
        .coerceIn(0f, 1f)

    val capacityBase = status.learnedCapacityWh
        .takeIf { it > 0.0 }
        ?: status.currentStoredWh.coerceAtLeast(1.0)

    val capacityProgress = if (capacityBase > 0.0) {
        (status.currentStoredWh / capacityBase)
            .toFloat()
            .coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge
    ) {
        Column(
            modifier = Modifier.padding(22.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = stateTitle(status.powerState),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "${status.systemState} / ${status.powerState}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Text(
                    text = formatNumber(status.socPercent, 1) + "%",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            LinearProgressIndicator(
                progress = { socProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
            )

            Spacer(modifier = Modifier.height(22.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Мощность",
                    value = formatNumber(status.powerW, 1),
                    unit = "W"
                )

                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Средняя",
                    value = formatNumber(status.averagedPowerW, 1),
                    unit = "W"
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Напряжение",
                    value = formatNumber(status.voltageV, 2),
                    unit = "V"
                )

                MetricCard(
                    modifier = Modifier.weight(1f),
                    label = "Время",
                    value = formatEta(status.estimatedTimeHours),
                    unit = ""
                )
            }

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "Энергия",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { capacityProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            MetricRow(
                label = "Осталось",
                value = formatNumber(status.currentStoredWh, 1) + " Wh"
            )

            MetricRow(
                label = "Расчётная ёмкость",
                value = formatNumber(capacityBase, 1) + " Wh"
            )

            Spacer(modifier = Modifier.height(18.dp))

            HorizontalDivider()

            Spacer(modifier = Modifier.height(12.dp))

            MetricRow("MOSFET", if (status.mosfetEnabled) "Включён" else "Выключен")
            MetricRow("Bluetooth", if (status.bluetoothConnected) "Подключён" else "Не подключён")
            MetricRow("Обучение ёмкости", if (status.learningActive) "Активно" else "Выключено")
            MetricRow("Циклов обучения", status.learnedCycles.toString())

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onRefreshClick
            ) {
                Text("Обновить")
            }
        }
    }
}

@Composable
fun MetricCard(
    modifier: Modifier,
    label: String,
    value: String,
    unit: String
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                if (unit.isNotBlank()) {
                    Spacer(modifier = Modifier.width(4.dp))

                    Text(
                        text = unit,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun MetricRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    isConnected: Boolean,
    statusText: String,
    boundDeviceName: String?,
    connectedDeviceAddress: String?,
    onRefreshClick: () -> Unit,
    onDisconnectClick: () -> Unit,
    onUnbindClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text(
            text = "Приложение",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = "Привязанная станция",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(10.dp))

                MetricRow("Имя", boundDeviceName ?: "PowerStation")
                MetricRow("Состояние", if (isConnected) "Подключена" else "Не подключена")
                MetricRow("Статус", statusText)

                if (connectedDeviceAddress != null) {
                    MetricRow("Адрес", connectedDeviceAddress)
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onRefreshClick
                ) {
                    Text(
                        if (isConnected) {
                            "Обновить данные"
                        } else {
                            "Повторить подключение"
                        }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isConnected,
                    onClick = onDisconnectClick
                ) {
                    Text("Отключиться")
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onUnbindClick
                ) {
                    Text("Отвязать станцию")
                }
            }
        }
    }
}

@Composable
fun DeviceCard(
    device: BleDeviceUi,
    isConnected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(
            containerColor = if (isConnected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = cleanDeviceName(device.name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (isConnected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    }
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (isConnected) {
                        "Подключено"
                    } else {
                        "BLE device"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isConnected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            SignalStrengthIndicator(
                rssi = device.rssi
            )
        }
    }
}

@Composable
fun SignalStrengthIndicator(
    rssi: Int
) {
    val level = signalLevel(rssi)

    Row(
        modifier = Modifier.height(22.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        for (bar in 1..4) {
            val active = bar <= level

            val barHeight = when (bar) {
                1 -> 6.dp
                2 -> 10.dp
                3 -> 14.dp
                else -> 18.dp
            }

            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(barHeight)
                    .background(
                        color = if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = MaterialTheme.shapes.extraSmall
                    )
            )
        }
    }
}

fun cleanDeviceName(name: String): String {
    return if (
        name.isBlank() ||
        name == "Unknown device" ||
        name == "Unknown"
    ) {
        "Unknown"
    } else {
        name
    }
}

fun signalLevel(rssi: Int): Int {
    return when {
        rssi >= -55 -> 4
        rssi >= -70 -> 3
        rssi >= -85 -> 2
        else -> 1
    }
}

fun stateTitle(powerState: String): String {
    return when (powerState.uppercase(Locale.US)) {
        "CHARGE", "CHARGING" -> "Зарядка"
        "DISCHARGE", "DISCHARGING" -> "Питание нагрузки"
        "IDLE" -> "Ожидание"
        "OFF" -> "Выключено"
        else -> "Состояние станции"
    }
}


fun formatCurrentValue(
    value: Double?,
    digits: Int,
    unit: String
): String {
    return if (value == null) {
        "Текущее: не получено от станции"
    } else {
        "Текущее: " + formatNumber(value, digits) + if (unit.isNotBlank()) " $unit" else ""
    }
}

fun formatNumber(
    value: Double,
    digits: Int
): String {
    return String.format(
        Locale.US,
        "%.${digits}f",
        value
    )
}

fun formatRangeValue(value: Double): String {
    return if (value % 1.0 == 0.0) {
        String.format(Locale.US, "%.0f", value)
    } else {
        String.format(Locale.US, "%.2f", value)
            .trimEnd('0')
            .trimEnd('.')
    }
}

fun formatCommandNumber(value: Double): String {
    return String.format(Locale.US, "%.4f", value)
        .trimEnd('0')
        .trimEnd('.')
}

fun formatEta(hours: Double): String {
    if (hours <= 0.0 || hours.isNaN() || hours.isInfinite()) {
        return "-"
    }

    val totalMinutes = (hours * 60.0).toInt()
    val days = totalMinutes / (24 * 60)
    val restMinutesAfterDays = totalMinutes % (24 * 60)
    val h = restMinutesAfterDays / 60
    val m = restMinutesAfterDays % 60

    return if (days > 0) {
        "${days}д ${h}ч"
    } else {
        "${h}ч ${m}м"
    }
}

fun JSONObject?.optNullableDouble(key: String): Double? {
    if (this == null || !has(key) || isNull(key)) {
        return null
    }

    val value = optDouble(key, Double.NaN)

    return if (value.isNaN()) {
        null
    } else {
        value
    }
}