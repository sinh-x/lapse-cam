package com.sinh.timelapse

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** Enrollment-only diagnostics. Never display exception messages, response bodies or credentials. */
internal object SyncEnrollment {
    enum class Problem(val message: String) {
        ENDPOINT("Enrollment failed: enter a trusted https://host.tailnet.ts.net:8443 endpoint, without a path, query or embedded credentials."),
        TOKEN("Enrollment failed: enter the 64-character per-device token, not the browser password. Check for missing characters or spaces inside it."),
        OFFLINE("Enrollment failed: no network connection. Connect Wi-Fi or explicitly confirm mobile data, and connect Tailscale on this phone."),
        NETWORK_TYPE("Enrollment failed: this connection cannot be identified as Wi-Fi or mobile data. Check the phone's network and Tailscale connection; no transfer was started."),
        ARCHIVE("Enrollment failed: this installation already belongs to another archive. Existing credentials and receipts were retained; do not clear app data."),
        AUTH("Enrollment failed: the server rejected this device token. Check that it was provisioned for this installation UUID; do not reset or regenerate credentials blindly."),
        IDENTITY("Enrollment failed: this token belongs to a different installation UUID. Provision the UUID shown on this phone; existing data was retained."),
        DNS("Enrollment failed: the server hostname could not be resolved. Check the endpoint and that Tailscale is Connected on this phone."),
        TIMEOUT("Enrollment failed: the server did not respond in time. Check that Tailscale is Connected on this phone, tailnet access is allowed, and the server is running."),
        UNREACHABLE("Enrollment failed: cannot reach the private server. Check that Tailscale is Connected on this phone, tailnet access is allowed, and the server is running."),
        TLS("Enrollment failed: the server's secure connection could not be verified. Check its hostname, certificate and the phone's clock. Do not bypass certificate verification."),
        SERVER("Enrollment failed: the server returned an unexpected response. Check the server and protocol version; existing local data was retained."),
        RATE_LIMIT("Enrollment failed: the server is limiting requests. Wait before another explicit attempt; existing local data was retained."),
        STORAGE("Enrollment failed: local sync storage or secure credential storage is unavailable. Existing data was retained; do not clear app data or reset credentials.")
    }
    class Failure(val problem: Problem) : Exception()

    fun endpoint(value: String): String = try {
        SyncTransport.validateEndpoint(value)
    } catch (_: Exception) { throw Failure(Problem.ENDPOINT) }

    fun token(value: String): String = value.trim().also {
        if (!it.matches(Regex("[0-9a-f]{64}"))) throw Failure(Problem.TOKEN)
    }

    fun checkArchive(existing: String, selected: String) {
        if (existing.isNotEmpty() && existing != selected) throw Failure(Problem.ARCHIVE)
    }

    fun checkNetwork(network: SyncPolicy.Network) {
        when (network) {
            SyncPolicy.Network.OFFLINE -> throw Failure(Problem.OFFLINE)
            SyncPolicy.Network.OTHER -> throw Failure(Problem.NETWORK_TYPE)
            else -> Unit
        }
    }

    /** Only network/protocol exceptions from this scope are classified as connectivity failures. */
    fun checkIdentity(expected: String, fetch: () -> String) {
        val actual = try { fetch() } catch (error: Exception) {
            val problem = when (error) {
                is SyncTransport.HttpError -> when (error.status) {
                    401, 403 -> Problem.AUTH
                    429 -> Problem.RATE_LIMIT
                    else -> Problem.SERVER
                }
                is SSLException -> Problem.TLS
                is UnknownHostException -> Problem.DNS
                is SocketTimeoutException -> Problem.TIMEOUT
                is IOException -> Problem.UNREACHABLE
                else -> Problem.SERVER
            }
            throw Failure(problem)
        }
        if (actual != expected) throw Failure(Problem.IDENTITY)
    }

    fun error(error: Exception): String = (error as? Failure)?.problem?.message ?: Problem.STORAGE.message
}
