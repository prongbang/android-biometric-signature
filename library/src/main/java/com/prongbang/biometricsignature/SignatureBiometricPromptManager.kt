package com.prongbang.biometricsignature

import android.os.Build
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.util.Base64
import android.util.Log
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import com.prongbang.biometricsignature.executor.ExecutorCreator
import com.prongbang.biometricsignature.executor.MainExecutorCreator
import com.prongbang.biometricsignature.extensions.toBase64
import com.prongbang.biometricsignature.key.BiometricKeyStoreAliasKey
import com.prongbang.biometricsignature.key.KeyStoreAliasKey
import com.prongbang.biometricsignature.keypair.BiometricKeyStoreManager
import com.prongbang.biometricsignature.keypair.KeyStoreManager
import com.prongbang.biometricsignature.signature.BiometricKeyStoreSignature
import com.prongbang.biometricsignature.signature.BiometricSignature
import com.prongbang.biometricsignature.signature.KeyStoreSignature
import java.security.KeyStore
import java.security.Signature
import java.security.UnrecoverableKeyException
import javax.inject.Inject

class SignatureBiometricPromptManager @Inject constructor(
    private val activity: FragmentActivity,
    private val biometricSignature: BiometricSignature?,
    private val keyStoreManager: KeyStoreManager,
    private val keyStoreAliasKey: KeyStoreAliasKey,
    private val executorCreator: ExecutorCreator,
    private val keyStoreSignature: KeyStoreSignature,
    private val biometricPromptInfoBuilder: BiometricPromptInfoBuilder,
) : SignatureBiometricManager {

    interface Result {
        fun callback(biometric: Biometric)
    }

    private val biometricManager = BiometricManager.from(activity.applicationContext)

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.N)
    override fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N

    override fun isAvailable(): Boolean {
        return biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK) == BiometricManager.BIOMETRIC_SUCCESS && isSupported()
    }

    override fun isUnavailable(): Boolean {
        val canAuthentication =
            biometricManager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
        return canAuthentication == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
                || canAuthentication == BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
    }

    override fun isBiometricChanged(): Boolean {
        return try {
            val keyStore = keyStoreManager.getKeyStore()
            val keyAlias = keyStoreAliasKey.key()
            val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.PrivateKeyEntry
            if (entry == null) {
                true // Key doesn't exist, assume biometric has changed
            } else {
                val signature = Signature.getInstance("SHA256withECDSA")
                signature.initSign(entry.privateKey)

                // If we get here, the key is still valid
                false
            }
        } catch (e: Exception) {
            when (e) {
                is KeyPermanentlyInvalidatedException,
                is UnrecoverableKeyException -> {
                    // These exceptions indicate that the key is no longer valid,
                    // likely due to biometric changes
                    true
                }

                else -> {
                    // Other exceptions might not be related to biometric changes
                    // You might want to log these for debugging
                    false
                }
            }
        }
    }

    override fun createKeyPair(info: Biometric.PromptInfo, onResult: Result) {
        try {
            // Generate keyPair
            keyStoreManager.generateKeyPair(
                keyStoreAliasKey.key(),
                info.invalidatedByBiometricEnrollment
            )

            // Authenticate
            val bioPromptCrypto = BiometricPrompt.CryptoObject(
                keyStoreSignature.getSignature(info.invalidatedByBiometricEnrollment)
            )
            val promptInfo = biometricPromptInfoBuilder.build(info)

            val keyPairBiometricPrompt = BiometricPrompt(
                activity,
                executorCreator.create(activity.applicationContext),
                KeyPairBiometricAuthenticationHandler(keyStoreManager, keyStoreAliasKey, onResult),
            )
            keyPairBiometricPrompt.authenticate(promptInfo, bioPromptCrypto)
        } catch (e: Exception) {
            onResult.callback(Biometric(status = Biometric.Status.ERROR, error = e.message))
        }
    }

    override fun sign(info: Biometric.PromptInfo, onResult: Result) {
        try {
            val bioPromptCrypto = BiometricPrompt.CryptoObject(
                keyStoreSignature.getSignature(info.invalidatedByBiometricEnrollment)
            )
            val promptInfo = biometricPromptInfoBuilder.build(info)

            val signBiometricPrompt = BiometricPrompt(
                activity,
                executorCreator.create(activity.applicationContext),
                SignBiometricAuthenticationHandler(biometricSignature, onResult),
            )
            signBiometricPrompt.authenticate(promptInfo, bioPromptCrypto)
        } catch (e: Exception) {
            onResult.callback(Biometric(status = Biometric.Status.ERROR, error = e.message))
        }
    }

    override fun verify(info: Biometric.PromptInfo, onResult: Result) {
        try {
            val bioPromptCrypto = BiometricPrompt.CryptoObject(
                keyStoreSignature.getSignature(info.invalidatedByBiometricEnrollment)
            )
            val promptInfo = biometricPromptInfoBuilder.build(info)

            val verifyBiometricPrompt = BiometricPrompt(
                activity,
                executorCreator.create(activity.applicationContext),
                VerifyBiometricAuthenticationHandler(
                    biometricSignature,
                    keyStoreManager,
                    keyStoreAliasKey,
                    onResult
                ),
            )
            verifyBiometricPrompt.authenticate(promptInfo, bioPromptCrypto)
        } catch (e: Exception) {
            onResult.callback(Biometric(status = Biometric.Status.ERROR, error = e.message))
        }
    }

    inner class KeyPairBiometricAuthenticationHandler(
        private val keyStoreManager: KeyStoreManager,
        private val keyStoreAliasKey: KeyStoreAliasKey,
        private var onKeyPairResult: Result,
    ) : BiometricPrompt.AuthenticationCallback() {

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            val result = getAuthenticationError(errorCode)
            onKeyPairResult.callback(Biometric(status = result, error = errString.toString()))
        }

        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            try {
                // Generate KeyPair
                val publicKey = keyStoreManager.getPublicKey(keyStoreAliasKey.key())
                val keyPair = Biometric.KeyPair(publicKey = publicKey.toBase64())

                onKeyPairResult.callback(
                    Biometric(keyPair = keyPair, status = Biometric.Status.SUCCEEDED)
                )
            } catch (e: Exception) {
                onKeyPairResult.callback(
                    Biometric(
                        status = Biometric.Status.ERROR,
                        error = e.message
                    )
                )
            }
        }
    }

    inner class SignBiometricAuthenticationHandler(
        private val biometricSignature: BiometricSignature?,
        private val onSignResult: Result,
    ) : BiometricPrompt.AuthenticationCallback() {

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            val result = getAuthenticationError(errorCode)
            onSignResult.callback(Biometric(status = result, error = errString.toString()))
        }

        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            try {
                // Sign payload
                val cryptoObject = result.cryptoObject
                val signature = cryptoObject?.signature
                val payload = biometricSignature?.payload() ?: ""
                signature?.update(payload.toByteArray(Charsets.UTF_8))
                val signatureBytes = signature?.sign()
                val signed = Base64.encodeToString(signatureBytes, Base64.DEFAULT)
                    .replace("\r", "")
                    .replace("\n", "")

                onSignResult.callback(
                    Biometric(
                        signature = Biometric.Signature(
                            signature = signed,
                            payload = payload,
                        ),
                        status = Biometric.Status.SUCCEEDED
                    )
                )
            } catch (e: Exception) {
                onSignResult.callback(Biometric(status = Biometric.Status.ERROR, error = e.message))
            }
        }
    }

    inner class VerifyBiometricAuthenticationHandler(
        private val biometricSignature: BiometricSignature?,
        private val keyStoreManager: KeyStoreManager,
        private val keyStoreAliasKey: KeyStoreAliasKey,
        private val onVerifyResult: Result,
    ) : BiometricPrompt.AuthenticationCallback() {

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            val result = getAuthenticationError(errorCode)
            onVerifyResult.callback(Biometric(status = result, error = errString.toString()))
        }

        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            try {
                // Verify signature
                val publicKey = keyStoreManager.getPublicKey(keyStoreAliasKey.key())
                val sign = Signature.getInstance("SHA256withECDSA")
                val signature = biometricSignature?.signature() ?: ""
                val textToSign = biometricSignature?.payload() ?: ""
                sign.initVerify(publicKey)
                sign.update(textToSign.toByteArray(Charsets.UTF_8))
                val respByte = Base64.decode(signature, Base64.DEFAULT)
                val verify = sign.verify(respByte)

                onVerifyResult.callback(
                    Biometric(
                        verify = verify,
                        status = Biometric.Status.SUCCEEDED
                    )
                )
            } catch (e: Exception) {
                Log.i("SignatureBiometricPromptManager", "${e.message}")
                onVerifyResult.callback(
                    Biometric(
                        status = Biometric.Status.ERROR,
                        error = e.message
                    )
                )
            }
        }
    }

    private fun getAuthenticationError(errorCode: Int): Biometric.Status {
        val result = when (errorCode) {
            BiometricPrompt.ERROR_CANCELED,
            BiometricPrompt.ERROR_NEGATIVE_BUTTON,
            BiometricPrompt.ERROR_USER_CANCELED -> Biometric.Status.CANCEL

            BiometricPrompt.ERROR_LOCKOUT -> Biometric.Status.LOCKOUT
            BiometricPrompt.ERROR_LOCKOUT_PERMANENT -> Biometric.Status.LOCKOUT_PERMANENT
            else -> Biometric.Status.ERROR
        }
        return result
    }

    companion object {
        fun newInstance(
            fragmentActivity: FragmentActivity,
            biometricSignature: BiometricSignature? = null,
            keyStoreAliasKey: KeyStoreAliasKey? = null,
        ): SignatureBiometricManager {
            val keyStoreManager = BiometricKeyStoreManager()
            val keyStoreAlias = keyStoreAliasKey ?: BiometricKeyStoreAliasKey()
            val keyStoreCipher = BiometricKeyStoreSignature(keyStoreAlias, keyStoreManager)
            val biometricPromptInfoBuilder = BiometricPromptInfoBuilderImpl()
            val mainExecutorCreator = MainExecutorCreator()
            return SignatureBiometricPromptManager(
                activity = fragmentActivity,
                keyStoreSignature = keyStoreCipher,
                biometricSignature = biometricSignature,
                keyStoreManager = keyStoreManager,
                keyStoreAliasKey = keyStoreAlias,
                executorCreator = mainExecutorCreator,
                biometricPromptInfoBuilder = biometricPromptInfoBuilder,
            )
        }
    }
}