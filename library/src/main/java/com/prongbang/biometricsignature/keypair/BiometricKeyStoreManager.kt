package com.prongbang.biometricsignature.keypair

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.annotation.RequiresApi
import com.prongbang.biometricsignature.exception.GenerateKeyPairException
import com.prongbang.biometricsignature.exception.KeyPairPermanentlyInvalidatedException
import com.prongbang.biometricsignature.exception.PrivateKeyNotFoundException
import com.prongbang.biometricsignature.exception.PublicKeyNotFoundException
import java.security.*
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject

/**
 * Reference: https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec#example:-nist-p-256-ec-key-pair-for-signingverification-using-ecdsa
 */
class BiometricKeyStoreManager @Inject constructor() : KeyStoreManager {

    @RequiresApi(Build.VERSION_CODES.N)
    override fun getPublicKey(key: String): PublicKey {
        return try {
            val keyStore = getKeyStore()
            val publicKey = keyStore.getCertificate(key).publicKey
            publicKey
        } catch (e: Exception) {
            Log.e("BiometricKeyStoreManager", e.message ?: "")
            throw PublicKeyNotFoundException(message = e.cause?.message)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    override fun getPrivateKey(key: String, invalidatedByBiometricEnrollment: Boolean): PrivateKey {
        return try {
            val keyStore = getKeyStore()
            val privateKey = keyStore.getKey(key, null) as? PrivateKey
            privateKey ?: let {
                generateKeyPair(key, invalidatedByBiometricEnrollment)
                val keyStore2 = getKeyStore()
                val privateKey2 = keyStore2.getKey(key, null) as PrivateKey
                privateKey2
            }
        } catch (e: Exception) {
            Log.e("BiometricKeyStoreManager", e.message ?: "")
            throw PrivateKeyNotFoundException(message = e.cause?.message)
        }
    }

    /**
     * How to use:
     *  val keyPair = getKeyPair(key)
     *  val publicKey = keyPair.public
     *  val privateKey = keyPair.private
     */
    @RequiresApi(Build.VERSION_CODES.N)
    override fun getKeyPair(key: String, invalidatedByBiometricEnrollment: Boolean): KeyPair {
        val privateKey = getPrivateKey(key, invalidatedByBiometricEnrollment)
        val publicKey = getPublicKey(key)
        return KeyPair(publicKey, privateKey)
    }

    override fun generateKeyPair(key: String, invalidatedByBiometricEnrollment: Boolean): KeyPair {
        return try {
            // Delete keypair
            deleteKeyPair(key)

            // Generate keypair
            val purposes = KeyProperties.PURPOSE_SIGN
            val builder = KeyGenParameterSpec.Builder(key, purposes).apply {
                setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                setDigests(
                    KeyProperties.DIGEST_SHA256,
                    KeyProperties.DIGEST_SHA384,
                    KeyProperties.DIGEST_SHA512
                )
                setUserAuthenticationRequired(true)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    setInvalidatedByBiometricEnrollment(invalidatedByBiometricEnrollment)
                }
            }

            val keyPairGenerator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC,
                ANDROID_KEY_STORE
            )
            keyPairGenerator.initialize(builder.build())
            keyPairGenerator.generateKeyPair()
        } catch (e: KeyPermanentlyInvalidatedException) {
            throw KeyPairPermanentlyInvalidatedException(message = e.cause?.message)
        } catch (e: Exception) {
            throw GenerateKeyPairException(message = e.cause?.message)
        }
    }

    override fun deleteKeyPair(key: String): Boolean {
        try {
            // Load the Android Keystore
            val keyStore = getKeyStore()

            // Check if the keypair with the specified alias exists
            if (keyStore.containsAlias(key)) {
                keyStore.deleteEntry(key)
                Log.i("BiometricKeyStoreManager", "Keypair with alias deleted successfully.")
            } else {
                Log.i("BiometricKeyStoreManager", "Keypair with alias does not exist.")
            }
            return true
        } catch (e: KeyStoreException) {
            Log.e("BiometricKeyStoreManager", e.message ?: "")
        }
        return false
    }

    override fun getKeyStore(): KeyStore {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE)
        keyStore.load(null)

        return keyStore
    }

    companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
    }
}