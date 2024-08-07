package com.prongbang.biometricsignature.signature

import java.security.Signature

interface KeyStoreSignature {
    fun getSignature(invalidatedByBiometricEnrollment: Boolean): Signature
}