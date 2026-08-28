package fyi.b612.lovehouse.feature.nativelab

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal const val BleScanDurationMillis = 10_000L

internal data class BleDeviceItem(
    val address: String,
    val name: String,
    val rssi: Int,
)

internal data class BleGattServiceItem(
    val uuid: String,
    val characteristics: List<String>,
)

internal data class BleUiState(
    val isSupported: Boolean,
    val isBluetoothEnabled: Boolean,
    val isScanning: Boolean = false,
    val devices: List<BleDeviceItem> = emptyList(),
    val connectedAddress: String? = null,
    val connectedName: String? = null,
    val services: List<BleGattServiceItem> = emptyList(),
    val message: String,
)

internal fun requiredBleRuntimePermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

internal fun formatGattProperties(properties: Int): String = buildList {
    if (properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("读")
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("写")
    if (properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("无响应写")
    if (properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("通知")
    if (properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("指示")
}.joinToString(" / ").ifEmpty { "无公开属性" }

internal class BleCapabilityController(context: Context) {
    private val appContext = context.applicationContext
    private val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(snapshot("点击开始扫描；扫描会在 10 秒后自动停止。"))
    val state: StateFlow<BleUiState> = mutableState.asStateFlow()

    private var activeGatt: BluetoothGatt? = null
    private val scanTimeout = Runnable { stopScan("扫描已自动停止。可再次点击开始扫描。") }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!hasConnectPermission()) return
            val device = BleDeviceItem(
                address = result.device.address,
                name = result.scanRecord?.deviceName ?: result.device.name ?: "未命名设备",
                rssi = result.rssi,
            )
            mutableState.update { current ->
                val devices = (current.devices.filterNot { it.address == device.address } + device)
                    .sortedByDescending(BleDeviceItem::rssi)
                current.copy(devices = devices, message = "正在扫描，已发现 ${devices.size} 台 BLE 设备。")
            }
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            handler.removeCallbacks(scanTimeout)
            mutableState.update { it.copy(isScanning = false, message = "BLE 扫描失败，错误码：$errorCode。") }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== activeGatt) {
                gatt.close()
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    mutableState.update { it.copy(message = "BLE 已连接，正在读取 GATT 服务…") }
                    if (hasConnectPermission()) {
                        runCatching { gatt.discoverServices() }
                            .onFailure { disconnectWithMessage("已连接，但无法读取 GATT 服务。") }
                    } else {
                        disconnectWithMessage("蓝牙连接权限已失效，连接已释放。")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> closeGatt(
                    if (status == BluetoothGatt.GATT_SUCCESS) "BLE 已断开。" else "BLE 连接已断开，状态码：$status。",
                )
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== activeGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS || !hasConnectPermission()) {
                mutableState.update { it.copy(message = "GATT 服务读取失败，状态码：$status。") }
                return
            }
            val services = gatt.services.map(BluetoothGattService::toUiItem)
            mutableState.update {
                it.copy(
                    services = services,
                    message = "GATT 服务读取完成：${services.size} 个服务。",
                )
            }
        }
    }

    fun refreshBluetoothState() {
        mutableState.update { current ->
            snapshot(
                when {
                    adapter == null -> "这台设备不支持 BLE。"
                    !hasConnectPermission() -> "等待蓝牙权限。"
                    !adapter.isEnabled -> "蓝牙未开启，请点击后使用系统面板开启。"
                    else -> current.message
                },
                current,
            )
        }
    }

    fun startScan() {
        refreshBluetoothState()
        if (adapter == null) return
        if (!hasScanPermission() || !hasConnectPermission()) {
            mutableState.update { it.copy(message = "蓝牙权限未授予，无法扫描。") }
            return
        }
        if (!adapter.isEnabled) {
            mutableState.update { it.copy(message = "蓝牙未开启。") }
            return
        }
        val scanner = adapter.bluetoothLeScanner ?: run {
            mutableState.update { it.copy(message = "当前无法取得 BLE 扫描器。") }
            return
        }
        stopScan(null)
        mutableState.update {
            it.copy(isScanning = true, devices = emptyList(), message = "正在扫描附近 BLE 设备…")
        }
        handler.postDelayed(scanTimeout, BleScanDurationMillis)
        runCatching { scanner.startScan(scanCallback) }
            .onFailure {
                handler.removeCallbacks(scanTimeout)
                mutableState.update { state -> state.copy(isScanning = false, message = "BLE 扫描启动失败。") }
            }
    }

    fun stopScan(message: String? = "扫描已停止。") {
        handler.removeCallbacks(scanTimeout)
        if (mutableState.value.isScanning && hasScanPermission()) {
            runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        }
        mutableState.update { current ->
            current.copy(isScanning = false, message = message ?: current.message)
        }
    }

    fun connect(device: BleDeviceItem) {
        stopScan()
        if (!hasConnectPermission()) {
            mutableState.update { it.copy(message = "蓝牙连接权限未授予。") }
            return
        }
        closeGatt(null)
        val bluetoothDevice = runCatching { adapter?.getRemoteDevice(device.address) }.getOrNull()
        if (bluetoothDevice == null) {
            mutableState.update { it.copy(message = "无法找到所选 BLE 设备。") }
            return
        }
        mutableState.update {
            it.copy(
                connectedAddress = device.address,
                connectedName = device.name,
                services = emptyList(),
                message = "正在连接 ${device.name}…",
            )
        }
        activeGatt = runCatching {
            bluetoothDevice.connectGatt(appContext, false, gattCallback, android.bluetooth.BluetoothDevice.TRANSPORT_LE)
        }.getOrNull()
        if (activeGatt == null) closeGatt("BLE 连接启动失败。")
    }

    fun disconnect() {
        disconnectWithMessage("BLE 已主动断开。")
    }

    fun release() {
        stopScan(null)
        closeGatt(null)
        handler.removeCallbacksAndMessages(null)
    }

    private fun disconnectWithMessage(message: String) {
        val gatt = activeGatt
        activeGatt = null
        if (hasConnectPermission()) runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        mutableState.update {
            it.copy(connectedAddress = null, connectedName = null, services = emptyList(), message = message)
        }
    }

    private fun closeGatt(message: String?) {
        val gatt = activeGatt
        activeGatt = null
        runCatching { gatt?.close() }
        mutableState.update {
            it.copy(
                connectedAddress = null,
                connectedName = null,
                services = emptyList(),
                message = message ?: it.message,
            )
        }
    }

    private fun hasScanPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun snapshot(message: String, previous: BleUiState? = null): BleUiState {
        val supported = adapter != null && appContext.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        val enabled = supported && hasConnectPermission() && runCatching { adapter?.isEnabled == true }.getOrDefault(false)
        return BleUiState(
            isSupported = supported,
            isBluetoothEnabled = enabled,
            isScanning = previous?.isScanning ?: false,
            devices = previous?.devices.orEmpty(),
            connectedAddress = previous?.connectedAddress,
            connectedName = previous?.connectedName,
            services = previous?.services.orEmpty(),
            message = message,
        )
    }
}

private fun BluetoothGattService.toUiItem() = BleGattServiceItem(
    uuid = uuid.toString(),
    characteristics = characteristics.map { characteristic ->
        "${characteristic.uuid} · ${formatGattProperties(characteristic.properties)}"
    },
)
