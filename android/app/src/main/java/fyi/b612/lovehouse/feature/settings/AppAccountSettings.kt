package fyi.b612.lovehouse.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.b612.lovehouse.core.designsystem.LoveHouseGlass
import kotlinx.coroutines.launch

@Composable
internal fun AppAccountSettings(repository: AppAccountRepository) {
    val state by repository.state.collectAsState()
    var registerMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(repository) { repository.refresh() }

    ProductPanel {
        Text("LoveHouse App Account", color = LoveHouseGlass.Ink, fontSize = 14.sp)
        Text("账号由 App Backend 管理，与 Owner Session / Supabase Auth 分离。", color = LoveHouseGlass.MutedInk, fontSize = 9.sp)
        when (val current = state) {
            AppAccountState.Checking -> Text("正在确认登录状态…", color = LoveHouseGlass.MutedInk, fontSize = 10.sp)
            AppAccountState.SignedOut -> AppAccountAuthForm(
                registerMode = registerMode,
                email = email,
                password = password,
                confirmation = confirmation,
                error = localError,
                onEmailChange = { email = it; localError = null },
                onPasswordChange = { password = it; localError = null },
                onConfirmationChange = { confirmation = it; localError = null },
                onSubmit = {
                    if (registerMode && password != confirmation) {
                        localError = "两次输入的密码不一致"
                    } else {
                        scope.launch {
                            runCatching {
                                if (registerMode) repository.register(email, password) else repository.login(email, password)
                            }.onFailure { localError = it.message }
                            password = ""
                            confirmation = ""
                        }
                    }
                },
                onToggleMode = {
                    registerMode = !registerMode
                    password = ""
                    confirmation = ""
                    localError = null
                },
            )
            is AppAccountState.SignedIn -> SignedInAccount(current.email, onLogout = { scope.launch { repository.logout() } })
            is AppAccountState.Error -> {
                current.signedInEmail?.let { SignedInAccount(it, onLogout = { scope.launch { repository.logout() } }) }
                Text(current.message, color = Color(0xFF9B4F55), fontSize = 9.sp)
                OutlinedButton(onClick = { scope.launch { repository.refresh() } }) { Text("重试", fontSize = 9.sp) }
                if (current.signedInEmail == null) {
                    AppAccountAuthForm(
                        registerMode = registerMode,
                        email = email,
                        password = password,
                        confirmation = confirmation,
                        error = localError,
                        onEmailChange = { email = it; localError = null },
                        onPasswordChange = { password = it; localError = null },
                        onConfirmationChange = { confirmation = it; localError = null },
                        onSubmit = {
                            if (registerMode && password != confirmation) localError = "两次输入的密码不一致"
                            else scope.launch {
                                runCatching { if (registerMode) repository.register(email, password) else repository.login(email, password) }
                                    .onFailure { localError = it.message }
                                password = ""
                                confirmation = ""
                            }
                        },
                        onToggleMode = { registerMode = !registerMode; password = ""; confirmation = ""; localError = null },
                    )
                }
            }
        }
    }
}
@Composable
private fun AppAccountAuthForm(
    registerMode: Boolean,
    email: String,
    password: String,
    confirmation: String,
    error: String?,
    onEmailChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onConfirmationChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onToggleMode: () -> Unit,
) {
    AccountField("邮箱", email, false, onEmailChange)
    AccountField("密码", password, true, onPasswordChange)
    if (registerMode) AccountField("确认密码", confirmation, true, onConfirmationChange)
    error?.let { Text(it, color = Color(0xFF9B4F55), fontSize = 9.sp) }
    Button(
        enabled = email.isNotBlank() && password.isNotBlank() && (!registerMode || confirmation.isNotBlank()),
        onClick = onSubmit,
    ) { Text(if (registerMode) "注册" else "登录", fontSize = 10.sp) }
    TextButton(onClick = onToggleMode) {
        Text(if (registerMode) "已有账号？登录" else "没有账号？注册", fontSize = 9.sp)
    }
}

@Composable
private fun SignedInAccount(email: String, onLogout: () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column {
                Text("当前邮箱", color = LoveHouseGlass.MutedInk, fontSize = 9.sp)
                Text(email, color = LoveHouseGlass.Ink, fontSize = 12.sp)
            }
            Text("已登录 ✓", color = Color(0xFF466F63), fontSize = 10.sp)
        }
        OutlinedButton(onClick = onLogout) { Text("退出登录", fontSize = 9.sp) }
    }
}

@Composable
private fun AccountField(label: String, value: String, secret: Boolean, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label, color = LoveHouseGlass.MutedInk, fontSize = 9.sp)
        Surface(
            Modifier.fillMaxWidth(),
            RoundedCornerShape(11.dp),
            Color.White.copy(.34f),
            border = BorderStroke(.6.dp, Color.White.copy(.55f)),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = LoveHouseGlass.Ink, fontSize = 11.sp),
                singleLine = true,
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            )
        }
    }
}
