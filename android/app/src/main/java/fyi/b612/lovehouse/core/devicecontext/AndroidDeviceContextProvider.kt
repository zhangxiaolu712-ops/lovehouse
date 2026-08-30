package fyi.b612.lovehouse.core.devicecontext

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat

class AndroidDeviceContextProvider(
    context: Context,
    private val isScreenObserverActive: () -> Boolean,
    private val currentTimeMillis: () -> Long = System::currentTimeMillis,
) : DeviceContextProvider {
    private val appContext = context.applicationContext

    override fun getCurrentDeviceContext(): DeviceContextSnapshot = DeviceContextSnapshot(
        capturedAtEpochMillis = currentTimeMillis(),
        battery = readBatteryContext(),
        network = readNetworkContext(),
        bluetooth = readBluetoothContext(),
        screenObserver = ScreenObserverContext(isActive = isScreenObserverActive()),
    )

    private fun readBatteryContext(): BatteryContext {
        val batteryManager = appContext.getSystemService(BatteryManager::class.java)
        val batteryIntent = appContext.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
        )
        val intentLevel = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val intentScale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val levelPercent = if (intentLevel >= 0 && intentScale > 0) {
            ((intentLevel * 100f) / intentScale).toInt().coerceIn(0, 100)
        } else {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?.takeIf { it in 0..100 }
        }
        val status = batteryIntent?.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN,
        ) ?: BatteryManager.BATTERY_STATUS_UNKNOWN
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            (status == BatteryManager.BATTERY_STATUS_UNKNOWN && batteryManager?.isCharging == true)
        val plugged = batteryIntent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val source = when {
            plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> ChargingSource.Usb
            plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> ChargingSource.Ac
            plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> ChargingSource.Wireless
            else -> ChargingSource.Unknown
        }
        val isLow = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            batteryIntent?.hasExtra(BatteryManager.EXTRA_BATTERY_LOW) == true
        ) {
            batteryIntent.getBooleanExtra(BatteryManager.EXTRA_BATTERY_LOW, false)
        } else {
            levelPercent != null && levelPercent <= LowBatteryFallbackPercent
        }

        return BatteryContext(
            levelPercent = levelPercent,
            isCharging = isCharging,
            chargingSource = source,
            isLow = isLow,
        )
    }

    private fun readNetworkContext(): NetworkContext {
        val connectivityManager = appContext.getSystemService(ConnectivityManager::class.java)
        val activeNetwork = connectivityManager?.activeNetwork
        val capabilities = activeNetwork?.let(connectivityManager::getNetworkCapabilities)
        if (capabilities == null) {
            return NetworkContext(
                hasNetwork = false,
                isValidated = false,
                transport = NetworkTransport.None,
                isMetered = false,
                isVpnActive = false,
            )
        }

        return NetworkContext(
            hasNetwork = true,
            isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            transport = capabilities.toPrimaryTransport(),
            isMetered = connectivityManager.isActiveNetworkMetered,
            isVpnActive = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
        )
    }

    @SuppressLint("MissingPermission")
    private fun readBluetoothContext(): BluetoothContext {
        val permissionsGranted = requiredBlePermissions().all { appContext.hasPermission(it) }
        val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
        val connectPermissionGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.BLUETOOTH_CONNECT,
            ) == PackageManager.PERMISSION_GRANTED
        val isEnabled = when {
            adapter == null -> null
            !connectPermissionGranted -> null
            else -> runCatching { adapter.isEnabled }.getOrNull()
        }

        return BluetoothContext(
            isSupported = adapter != null,
            isEnabled = isEnabled,
            blePermissionsGranted = permissionsGranted,
        )
    }

    private fun requiredBlePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }

    private fun Context.hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun NetworkCapabilities.toPrimaryTransport(): NetworkTransport = when {
        hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkTransport.Wifi
        hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkTransport.Cellular
        hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkTransport.Ethernet
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> NetworkTransport.Vpn
        else -> NetworkTransport.Other
    }

    private companion object {
        const val LowBatteryFallbackPercent = 15
    }
}
