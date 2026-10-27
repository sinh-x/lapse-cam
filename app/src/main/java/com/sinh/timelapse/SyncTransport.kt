package com.sinh.timelapse

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

internal object SyncCredential {
    private const val ALIAS = "timelapse-private-sync-v1"
    fun exists(): Boolean = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.containsAlias(ALIAS)
    private fun key(alias: String, create: Boolean): SecretKey {
        SyncThreads.io()
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create) { "Credential key missing; no automatic replacement" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun encrypt(token: String, alias: String = ALIAS): String {
        require(token.matches(Regex("[0-9a-f]{64}"))) { "Enter the privately provisioned 64-character device token" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(alias, true)) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)
    }
    fun decrypt(value: String, alias: String = ALIAS): String {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        require(bytes.size == 92)
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(alias, false), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.also { check(it.matches(Regex("[0-9a-f]{64}"))) }
    }
}

/** Platform certificate/hostname validation, no redirect, proxy credentials, cookies or cleartext fallback. */
internal class SyncTransport(endpoint: String, private val token: String, private val eligible: () -> Unit = {}) : SyncHttp {
    private val base = validateEndpoint(endpoint)
    class HttpError(val status: Int) : Exception("Private server returned HTTP $status; work remains pending")
    override fun request(method: String, path: String, body: ByteArray?, mutation: String?,
        headers: Map<String, String>): ByteArray {
        val buffer = java.io.ByteArrayOutputStream()
        exchange(method, path, body?.size?.toLong(), mutation, headers, { body?.let(it::write) }) { source ->
            val chunk = ByteArray(8192)
            while (true) {
                val n = source.read(chunk); if (n < 0) break
                check(buffer.size() + n <= 8 * 1024 * 1024) { "Server response exceeds protocol bound" }
                buffer.write(chunk, 0, n)
            }
        }
        return buffer.toByteArray()
    }
    fun exchange(method: String, path: String, length: Long?, mutation: String?, headers: Map<String, String>,
        write: (OutputStream) -> Unit, read: (InputStream) -> Unit) {
        eligible()
        SyncThreads.io()
        require(path.startsWith("/v1/") && !path.contains('#') && !path.contains('\\') && !path.contains(".."))
        val connection = URI(base + path).toURL().openConnection(java.net.Proxy.NO_PROXY) as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000; connection.readTimeout = 30_000
            connection.requestMethod = method
            connection.useCaches = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("Accept", "application/json")
            mutation?.let { connection.setRequestProperty("X-Mutation-ID", it) }
            headers.forEach { (k, v) -> connection.setRequestProperty(k, v) }
            if (length != null) {
                connection.doOutput = true; connection.setFixedLengthStreamingMode(length)
                if (!headers.containsKey("Content-Type")) connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { eligible(); write(it) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw HttpError(status)
            connection.inputStream.use(read)
        } finally { connection.disconnect() }
    }
    companion object {
        fun validateEndpoint(value: String): String {
            val uri = URI(value.trim().removeSuffix("/"))
            require(uri.scheme == "https" && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                uri.rawPath.isNullOrEmpty() && uri.host != null && uri.host == uri.host.lowercase() &&
                uri.host.matches(Regex("[a-z0-9](?:[a-z0-9.-]*[a-z0-9])?\\.ts\\.net")) &&
                uri.port in 1024..65535) { "Use the exact trusted https://host.tailnet.ts.net:8443 endpoint" }
            return uri.toASCIIString()
        }
    }
}
