package com.tunerquet.app.adb

import org.junit.Assert.*
import org.junit.Test

class PairingTargetTest {
    @Test fun supportsExplicitIpv4AndIpv6() {
        assertEquals("192.168.1.20:37123", PairingTarget.parse("192.168.1.20", "37123").address)
        assertEquals("[::1]:1", PairingTarget.parse("[::1]", "1").address)
        assertEquals("[2001:db8::1]:65535", PairingTarget.parse("2001:db8::1", "65535").address)
    }
    @Test fun rejectsHostnamesUrlsAndMalformedAddresses() {
        listOf("", "tv.local", "https://192.168.1.20", "192.168.1.20:5555", "256.1.2.3", "1.2.3", "01.2.3.4", "::::", "::1/64").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { PairingTarget.parse(it, "37123") }
        }
    }
    @Test fun rejectsMissingAndOutOfRangePorts() {
        listOf("", "0", "65536", "-1", "pair", "1.5").forEach {
            assertThrows(IllegalArgumentException::class.java) { PairingTarget.parse("192.168.1.20", it) }
        }
    }
}
