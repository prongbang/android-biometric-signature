package com.prongbang.biometricsignature.signature

abstract class BiometricSignature {
    abstract fun payload(): String
    open fun signature(): String = ""
}