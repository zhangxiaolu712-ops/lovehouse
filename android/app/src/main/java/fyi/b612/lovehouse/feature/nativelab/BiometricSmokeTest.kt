package fyi.b612.lovehouse.feature.nativelab

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity

internal const val BiometricAuthenticators =
    BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL

internal fun biometricAvailabilityMessage(result: Int): String? = when (result) {
    BiometricManager.BIOMETRIC_SUCCESS -> null
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> "这台设备没有可用的生物识别硬件。"
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> "生物识别硬件暂时不可用，请稍后重试。"
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> "设备尚未录入生物识别或屏幕锁。"
    BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED -> "设备需要安全更新后才能使用生物识别。"
    BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED -> "当前设备不支持这组生物识别方式。"
    else -> "当前无法使用生物识别（状态码 $result）。"
}

internal fun biometricErrorMessage(errorCode: Int): String = when (errorCode) {
    BiometricPrompt.ERROR_USER_CANCELED,
    BiometricPrompt.ERROR_CANCELED,
    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
    -> "生物识别已取消。"

    BiometricPrompt.ERROR_LOCKOUT -> "尝试次数过多，请稍后再试。"
    BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> "生物识别已锁定，请先使用设备凭据解锁。"
    BiometricPrompt.ERROR_NO_BIOMETRICS -> "设备尚未录入生物识别或屏幕锁。"
    BiometricPrompt.ERROR_HW_NOT_PRESENT -> "这台设备没有可用的生物识别硬件。"
    BiometricPrompt.ERROR_HW_UNAVAILABLE -> "生物识别硬件暂时不可用，请稍后重试。"
    else -> "生物识别失败（错误码 $errorCode）。"
}

internal tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

internal fun openBiometricSettings(context: Context) {
    val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Intent(Settings.ACTION_BIOMETRIC_ENROLL).putExtra(
            Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
            BiometricAuthenticators,
        )
    } else {
        Intent(Settings.ACTION_SECURITY_SETTINGS)
    }
    context.startActivity(intent)
}
