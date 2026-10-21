package com.tunerquet.app

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugEndpointTest {
    @Test fun formatsIpv4Address() {
        val endpoint = DebugEndpoint("id", "TV", "192.168.1.10", 5555, EndpointKind.LEGACY)
        assertEquals("192.168.1.10:5555", endpoint.address)
    }
    @Test fun bracketsIpv6AddressWithScope() {
        val endpoint = DebugEndpoint("id", "TV", "fe80::1%wlan0", 37123, EndpointKind.CONNECT)
        assertEquals("[fe80::1%wlan0]:37123", endpoint.address)
    }
}
