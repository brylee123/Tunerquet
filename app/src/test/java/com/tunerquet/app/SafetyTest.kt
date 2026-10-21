package com.tunerquet.app

import org.junit.Assert.*
import org.junit.Test

class SafetyTest {
    @Test fun protectsCriticalPackages() {
        listOf("com.google.android.gms", "com.android.systemui", "com.vendor.hdmi.service", "com.foo.setupwizard").forEach {
            assertEquals(Risk.PROTECTED, Safety.classify(it))
        }
    }
    @Test fun failsClosedOnUnrecognizedPackage() { assertEquals(Risk.UNKNOWN, Safety.classify("com.vendor.promotions")) }
    @Test fun neverDisablesProtectedPackages() {
        assertFalse(Safety.allowDisable(TvPackage("com.android.systemui", "System UI", true, Risk.LOW), true))
    }
    @Test fun requiresApproval() {
        assertFalse(Safety.allowDisable(TvPackage("com.vendor.ads", "Ads", true, Risk.LOW), false))
    }
}
