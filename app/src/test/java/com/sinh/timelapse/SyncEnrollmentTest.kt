package com.sinh.timelapse

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class SyncEnrollmentTest {
    private val endpoint = "https://host.tail.ts.net:8443"
    private val device = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
    private fun failure(problem: SyncEnrollment.Problem, action: () -> Unit) {
        val error = assertThrows(SyncEnrollment.Failure::class.java) { action() }
        assertEquals(problem, error.problem)
        assertEquals(problem.message, SyncEnrollment.error(error))
    }
    @Test fun pastedEndpointAndTokenAllowOnlyOuterWhitespace() {
        assertEquals(endpoint, SyncEnrollment.endpoint("  $endpoint/\n"))
        assertEquals("a".repeat(64), SyncEnrollment.token("  " + "a".repeat(64) + "\n"))
    }
    @Test fun malformedInputsHaveActionableSecretSafeErrors() {
        failure(SyncEnrollment.Problem.ENDPOINT) { SyncEnrollment.endpoint("https://secret-token@host.example/?secret=private") }
        listOf("", "browser-password", "a".repeat(63), "a".repeat(32) + " " + "a".repeat(32)).forEach {
            failure(SyncEnrollment.Problem.TOKEN) { SyncEnrollment.token(it) }
        }
    }
    @Test fun offlineAndUnknownTransportDoNotPretendToBeTailscaleConnectivity() {
        failure(SyncEnrollment.Problem.OFFLINE) { SyncEnrollment.checkNetwork(SyncPolicy.Network.OFFLINE) }
        failure(SyncEnrollment.Problem.NETWORK_TYPE) { SyncEnrollment.checkNetwork(SyncPolicy.Network.OTHER) }
        SyncEnrollment.checkNetwork(SyncPolicy.Network.WIFI)
        SyncEnrollment.checkNetwork(SyncPolicy.Network.MOBILE)
    }
    @Test fun archiveReplacementRemainsBlockedWithoutReset() {
        SyncEnrollment.checkArchive("", endpoint)
        SyncEnrollment.checkArchive(endpoint, endpoint)
        failure(SyncEnrollment.Problem.ARCHIVE) { SyncEnrollment.checkArchive(endpoint, "https://other.tail.ts.net:8443") }
    }
    @Test fun successfulIdentityCheckAndWrongInstallationAreDistinct() {
        var requests = 0
        SyncEnrollment.checkIdentity(device) { requests++; device }
        assertEquals(1, requests)
        failure(SyncEnrollment.Problem.IDENTITY) { SyncEnrollment.checkIdentity(device) { "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb" } }
    }
    @Test fun unreachablePrivateServerRecommendsTailscaleWithoutClaimingExactCause() {
        for (error in listOf(ConnectException("secret-token"), IOException("private-response"))) {
            failure(SyncEnrollment.Problem.UNREACHABLE) { SyncEnrollment.checkIdentity(device) { throw error } }
        }
        assertTrue(SyncEnrollment.Problem.UNREACHABLE.message.contains("Tailscale is Connected"))
    }
    @Test fun dnsTimeoutAndCertificateFailuresHaveDifferentRemedies() {
        listOf(
            UnknownHostException("private-response") to SyncEnrollment.Problem.DNS,
            SocketTimeoutException("private-response") to SyncEnrollment.Problem.TIMEOUT,
            SSLHandshakeException("private-response") to SyncEnrollment.Problem.TLS
        ).forEach { (error, problem) -> failure(problem) { SyncEnrollment.checkIdentity(device) { throw error } } }
        assertTrue(SyncEnrollment.Problem.TLS.message.contains("Do not bypass"))
    }
    @Test fun rejectedTokenAndRateLimitAreNotReportedAsLocalStorageFailures() {
        for (status in listOf(401, 403)) {
            failure(SyncEnrollment.Problem.AUTH) { SyncEnrollment.checkIdentity(device) { throw SyncTransport.HttpError(status) } }
        }
        failure(SyncEnrollment.Problem.RATE_LIMIT) { SyncEnrollment.checkIdentity(device) { throw SyncTransport.HttpError(429) } }
    }
    @Test fun unexpectedServerStatusAndMalformedResponseNeverExposeBodies() {
        for (status in listOf(301, 404, 500)) {
            failure(SyncEnrollment.Problem.SERVER) { SyncEnrollment.checkIdentity(device) { throw SyncTransport.HttpError(status) } }
        }
        failure(SyncEnrollment.Problem.SERVER) { SyncEnrollment.checkIdentity(device) { error("private-response") } }
    }
    @Test fun localStorageExceptionsAreNotMislabelledAsNetworkFailures() {
        val message = SyncEnrollment.error(IOException("secret-token /private-path private-response"))
        assertEquals(SyncEnrollment.Problem.STORAGE.message, message)
        assertFalse(message.contains("secret-token")); assertFalse(message.contains("private-path")); assertFalse(message.contains("private-response"))
        SyncEnrollment.Problem.entries.forEach {
            assertTrue(it.message.startsWith("Enrollment failed:"))
            assertFalse(it.message.contains("secret-token"))
        }
    }
}
