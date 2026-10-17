package today.tutul.nettools.net

import android.net.InetAddresses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.URL

data class PublicIpResult(
    val family: String,
    val source: String,
    val ip: String? = null,
    val extra: String? = null,
    val error: String? = null,
)

/**
 * Asks echo services which address our traffic arrives from. This is the one
 * feature that has to trust a third party, so every family is checked against
 * two independent sources and the UI flags any disagreement.
 */
object PublicIp {

    private class Source(
        val family: String,
        val name: String,
        val url: String,
        val parse: (String) -> Pair<String, String?>,
    )

    // Cloudflare's trace page: "key=value" lines, including ip, loc and colo.
    private val cloudflareTrace: (String) -> Pair<String, String?> = { body ->
        val fields = body.lines()
            .map { it.split('=', limit = 2) }
            .filter { it.size == 2 }
            .associate { it[0] to it[1] }
        val ip = fields["ip"] ?: error("no ip field in response")
        ip to listOfNotNull(
            fields["loc"]?.let { "loc=$it" },
            fields["colo"]?.let { "colo=$it" },
        ).joinToString(" ").ifEmpty { null }
    }

    private val plainText: (String) -> Pair<String, String?> = { it.trim() to null }

    private val sources = listOf(
        Source("IPv4", "Cloudflare", "https://1.1.1.1/cdn-cgi/trace", cloudflareTrace),
        Source("IPv4", "ipify", "https://api4.ipify.org", plainText),
        Source("IPv6", "Cloudflare", "https://[2606:4700:4700::1111]/cdn-cgi/trace", cloudflareTrace),
        Source("IPv6", "ipify", "https://api6.ipify.org", plainText),
    )

    suspend fun lookupAll(): List<PublicIpResult> = coroutineScope {
        sources.map { async(Dispatchers.IO) { lookup(it) } }.awaitAll()
    }

    private fun lookup(source: Source): PublicIpResult = try {
        val (ip, extra) = source.parse(fetch(source.url))
        val ok = InetAddresses.isNumericAddress(ip) && InetAddresses.parseNumericAddress(ip).let {
            if (source.family == "IPv4") it is Inet4Address else it is Inet6Address
        }
        if (ok) PublicIpResult(source.family, source.name, ip = ip, extra = extra)
        else PublicIpResult(source.family, source.name, error = "unexpected reply: ${ip.take(60)}")
    } catch (e: Exception) {
        PublicIpResult(source.family, source.name, error = e.message ?: e.javaClass.simpleName)
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5_000
        conn.readTimeout = 5_000
        conn.useCaches = false
        try {
            if (conn.responseCode != 200) error("HTTP ${conn.responseCode}")
            // Cap the read: we only expect a few hundred bytes.
            return conn.inputStream.use { input ->
                val buf = ByteArray(4096)
                var n = 0
                while (n < buf.size) {
                    val r = input.read(buf, n, buf.size - n)
                    if (r < 0) break
                    n += r
                }
                String(buf, 0, n)
            }
        } finally {
            conn.disconnect()
        }
    }
}
