package br.geracaoai.nuvem.ui

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import br.geracaoai.nuvem.data.SecretLock

fun canUseBiometric(activity: FragmentActivity): Boolean {
    val m = BiometricManager.from(activity)
    return m.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS
}

fun promptBiometric(activity: FragmentActivity, onOk: () -> Unit, onPin: () -> Unit) {
    val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { onOk() }
        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) { onPin() }
        override fun onAuthenticationFailed() {}
    })
    prompt.authenticate(
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("Nuvem secreto")
            .setSubtitle("Desbloqueie pra ver as pastas secretas")
            .setNegativeButtonText("Usar PIN")
            .build()
    )
}

@Composable
fun SecretPinDialog(setup: Boolean, onUnlocked: () -> Unit, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (setup) "Definir PIN do secreto" else "Desbloquear secreto") },
        text = {
            Column {
                Text(if (setup) "Esse PIN protege as pastas secretas no app. Escolha pelo menos 4 dígitos." else "Digite o PIN, ou use a digital se aparecer a opção.")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pin, { pin = it.filter { ch -> ch.isDigit() }.take(8); err = null }, label = { Text("PIN") },
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true)
                if (setup) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(pin2, { pin2 = it.filter { ch -> ch.isDigit() }.take(8); err = null }, label = { Text("Repetir PIN") },
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), singleLine = true)
                }
                err?.let { Spacer(Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button({
                if (setup) {
                    if (pin.length < 4) { err = "Mínimo de 4 dígitos"; return@Button }
                    if (pin != pin2) { err = "Os PINs não são iguais"; return@Button }
                    SecretLock.setPin(pin); onUnlocked()
                } else {
                    if (SecretLock.check(pin)) { SecretLock.unlocked = true; onUnlocked() } else err = "PIN incorreto"
                }
            }) { Text(if (setup) "Salvar" else "Desbloquear") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } }
    )
}

@Composable
fun rememberUnlockSecret(onUnlocked: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    var showPin by remember { mutableStateOf(false) }
    var setup by remember { mutableStateOf(false) }
    val go: () -> Unit = {
        if (SecretLock.unlocked) onUnlocked()
        else if (!SecretLock.hasPin()) { setup = true; showPin = true }
        else {
            val act = ctx as? FragmentActivity
            if (act != null && canUseBiometric(act)) {
                promptBiometric(act, onOk = { SecretLock.unlocked = true; onUnlocked() }, onPin = { setup = false; showPin = true })
            } else { setup = false; showPin = true }
        }
    }
    if (showPin) SecretPinDialog(setup, onUnlocked = { showPin = false; onUnlocked() }, onDismiss = { showPin = false })
    return go
}
