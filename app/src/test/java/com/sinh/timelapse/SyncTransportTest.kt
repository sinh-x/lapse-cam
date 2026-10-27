package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test

class SyncTransportTest {
    @Test fun exactTailnetEndpointHasNoCredentialQueryOrRedirectSurface() {
        assertEquals("https://host.tail.ts.net:8443", SyncTransport.validateEndpoint("https://host.tail.ts.net:8443/"))
        listOf("http://host.tail.ts.net:8443", "https://127.0.0.1:8443", "https://host.tail.ts.net:443",
            "https://host.tail.ts.net", "https://HOST.tail.ts.net:8443", "https://x@host.tail.ts.net:8443",
            "https://host.tail.ts.net:8443/v1", "https://host.tail.ts.net:8443?token=x",
            "https://host.tail.ts.net:8443#x", "https://host.tail.ts.net.evil:8443").forEach { value ->
            assertTrue(value, runCatching { SyncTransport.validateEndpoint(value) }.isFailure)
        }
    }
    @Test fun ineligibleAttemptNeverOpensConnectionOrWritesBytes() {
        var checked = 0
        val transport = SyncTransport("https://host.tail.ts.net:8443", "a".repeat(64)) { checked++; error("Offline fixture") }
        assertTrue(runCatching { transport.request("POST", "/v1/source", ByteArray(1024)) }.isFailure)
        assertEquals(1, checked)
    }
    @Test fun disabledUnenrolledOrWrongTransportNeverTransfers() {
        for (network in SyncPolicy.Network.entries) for (manual in listOf(false, true)) {
            assertFalse(SyncPolicy.mayTransfer(false, true, network, manual, true))
            assertFalse(SyncPolicy.mayTransfer(true, false, network, manual, true))
        }
        assertFalse(SyncPolicy.mayTransfer(true, true, SyncPolicy.Network.MOBILE, false, true))
        assertFalse(SyncPolicy.mayTransfer(true, true, SyncPolicy.Network.MOBILE, true, false))
        assertTrue(SyncPolicy.mayTransfer(true, true, SyncPolicy.Network.MOBILE, true, true))
    }
}
