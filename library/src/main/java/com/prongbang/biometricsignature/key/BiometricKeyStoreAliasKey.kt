package com.prongbang.biometricsignature.key

import javax.inject.Inject

class BiometricKeyStoreAliasKey @Inject constructor() : KeyStoreAliasKey {
    override fun key(): String = "com.prongbang.biometriccram.key"
}