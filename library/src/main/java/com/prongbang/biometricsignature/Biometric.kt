package com.prongbang.biometricsignature

data class Biometric(
    val verify: Boolean = false,
    val signature: Signature? = null,
    val keyPair: KeyPair? = null,
    val status: Status,
    val error: String? = null,
) {

    data class Signature(
        val signature: String = "",
        val payload: String = "",
    )

    data class KeyPair(
        val publicKey: String = "",
        val privateKey: String = "",
    )

    data class PromptInfo(
        val title: String = "",
        val subtitle: String = "",
        val description: String = "",
        val negativeButton: String = "",
        val invalidatedByBiometricEnrollment: Boolean = false
    )

    enum class Status {
        SUCCEEDED,
        ERROR,
        LOCKOUT,
        LOCKOUT_PERMANENT,
        CANCEL
    }
}