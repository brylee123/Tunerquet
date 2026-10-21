package com.tunerquet.app

import org.junit.Assert.*
import org.junit.Test

class SafetyTest {
    private val optional = "com.vendor.promotions"
    private val candidate = TvPackage(optional, "Promotions", true, Risk.LOW)
    private val verified = DeviceSafetyContext(inventoryVerified = true, verifiedOptionalPackages = setOf(optional))

    @Test fun protectsCriticalPackages() {
        listOf("com.google.android.gms", "com.android.vending", "com.android.systemui", "com.vendor.hdmi.service", "com.foo.setupwizard").forEach {
            assertEquals(Risk.PROTECTED, Safety.classify(it))
        }
    }
    @Test fun failsClosedOnUnrecognizedPackage() {
        assertEquals(Risk.UNKNOWN, Safety.classify(optional))
        assertTrue(Safety.assess(optional).reason.contains("not been verified"))
    }
    @Test fun neverDisablesProtectedPackagesEvenIfAllowlisted() {
        val name = "com.android.systemui"
        val context = DeviceSafetyContext(inventoryVerified = true, verifiedOptionalPackages = setOf(name))
        assertFalse(Safety.allowDisable(TvPackage(name, "System UI", true, Risk.LOW), true, context))
    }
    @Test fun lowRiskLabelCannotBypassDeviceVerification() {
        assertFalse(Safety.allowDisable(candidate, true))
    }
    @Test fun requiresApprovalEvenWithVerifiedEvidence() {
        assertFalse(Safety.allowDisable(candidate, false, verified))
        assertTrue(Safety.allowDisable(candidate, true, verified))
    }
    @Test fun allowlistWithoutVerifiedInventoryIsInsufficient() {
        assertFalse(Safety.allowDisable(candidate, true, verified.copy(inventoryVerified = false)))
    }
    @Test fun verifiedInventoryWithoutOptionalEvidenceIsInsufficient() {
        assertEquals(Risk.UNKNOWN, Safety.assess(optional, DeviceSafetyContext(inventoryVerified = true)).risk)
    }
    @Test fun deviceRolesOverrideOptionalEvidence() {
        val roles = listOf(
            verified.copy(activeLaunchers = setOf(optional)),
            verified.copy(inputMethods = setOf(optional)),
            verified.copy(requiredPackages = setOf(optional)),
            verified.copy(dependencyPackages = setOf(optional))
        )
        roles.forEach {
            assertEquals(Risk.PROTECTED, Safety.assess(optional, it).risk)
            assertFalse(Safety.allowDisable(candidate, true, it))
        }
    }
    @Test fun deviceRolesAreProtectedEvenBeforeInventoryIsComplete() {
        assertEquals(Risk.PROTECTED, Safety.assess(optional, DeviceSafetyContext(activeLaunchers = setOf(optional))).risk)
    }
    @Test fun rejectsMalformedPackageIdentifiers() {
        listOf("", " com.vendor.ads", "com.vendor.ads ", "com.vendor.ads;reboot", "com.vendor\nads", "com..ads", "--user", "android").forEach {
            assertEquals(Risk.PROTECTED, Safety.assess(it, verified).risk)
        }
    }
    @Test fun explicitPackageRiskCannotBeOverriddenByEvidence() {
        listOf(Risk.PROTECTED, Risk.UNKNOWN, Risk.REVIEW).forEach {
            assertFalse(Safety.allowDisable(candidate.copy(risk = it), true, verified))
        }
    }
    @Test fun systemDisablePolicyDoesNotAuthorizeUserApps() {
        assertFalse(Safety.allowDisable(candidate.copy(systemApp = false), true, verified))
    }
    @Test fun criticalChecksAreCaseInsensitive() {
        assertEquals(Risk.PROTECTED, Safety.classify("COM.ANDROID.SYSTEMUI"))
    }
}
