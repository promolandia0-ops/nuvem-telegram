package br.geracaoai.nuvem.data

/** PIN do canal secreto (hash local). O desbloqueio da sessão vive só na memória. */
object SecretLock {
    @Volatile var unlocked: Boolean = false

    fun pinHash(pin: String) = Hash.sha256(("nuvem-pin|$pin").byteInputStream())
    fun hasPin() = Prefs.secretPinHash.isNotBlank()
    fun check(pin: String) = pin.length >= 4 && pinHash(pin) == Prefs.secretPinHash
    fun setPin(pin: String) { Prefs.secretPinHash = pinHash(pin); unlocked = true }
    fun clearPin() { Prefs.secretPinHash = ""; unlocked = false }
    fun lock() { unlocked = false }
}
