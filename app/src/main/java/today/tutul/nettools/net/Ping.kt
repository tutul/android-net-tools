package today.tutul.nettools.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

data class Hop(
    val ttl: Int,
    val ip: String?,
    val rttMs: Double?,
    val reached: Boolean,
    val hostname: String? = null,
)

/**
 * Wraps the system's own ping binary. Apps can't open raw sockets, but
 * /system/bin/ping uses unprivileged ICMP sockets, so it works without root.
 */
object Ping {
    private const val PING = "/system/bin/ping"
    private const val PING6 = "/system/bin/ping6"

    private val REPLY = Regex("""bytes from ([0-9a-fA-F.:%\w]+?):? .*time[=<]([\d.]+)""")
    private val FROM = Regex("""^From ([0-9a-fA-F.:%\w]+?):? """)

    /** Resolve in Kotlin so ping only ever receives an IP literal, never user text. */
    suspend fun resolve(host: String, ipv6: Boolean): InetAddress = withContext(Dispatchers.IO) {
        InetAddress.getAllByName(host.trim())
            .firstOrNull { if (ipv6) it is Inet6Address else it is Inet4Address }
            ?: throw UnknownHostException("no ${if (ipv6) "IPv6" else "IPv4"} address for $host")
    }

    /** count <= 0 pings until the collector is cancelled. */
    fun ping(target: InetAddress, count: Int): Flow<String> {
        val args = baseCommand(target) + "-n" +
            (if (count > 0) listOf("-c", count.toString()) else emptyList()) +
            target.hostAddress!!
        return execLines(args)
    }

    fun parseRtt(line: String): Double? = REPLY.find(line)?.groupValues?.get(2)?.toDoubleOrNull()

    /**
     * Classic traceroute built from single pings with increasing TTL: each router
     * that drops the packet answers "Time to live exceeded" and reveals its IP.
     * IPv4 only for now.
     */
    fun traceroute(target: Inet4Address, maxHops: Int): Flow<Hop> = flow {
        val dest = target.hostAddress!!
        for (ttl in 1..maxHops) {
            var hopIp: String? = null
            var rtt: Double? = null
            execLines(listOf(PING, "-n", "-c", "1", "-W", "1", "-t", ttl.toString(), dest)).collect { line ->
                REPLY.find(line)?.let {
                    hopIp = it.groupValues[1]
                    rtt = it.groupValues[2].toDoubleOrNull()
                } ?: FROM.find(line)?.let { hopIp = it.groupValues[1] }
            }
            val reached = hopIp == dest
            // A TTL-exceeded reply carries no timing, so ping the router directly for an RTT.
            if (hopIp != null && !reached) {
                execLines(listOf(PING, "-n", "-c", "1", "-W", "1", hopIp!!)).collect { line ->
                    parseRtt(line)?.let { rtt = it }
                }
            }
            emit(Hop(ttl, hopIp, rtt, reached))
            if (reached) break
        }
    }

    private fun baseCommand(target: InetAddress): List<String> = when {
        target !is Inet6Address -> listOf(PING)
        File(PING6).exists() -> listOf(PING6)
        else -> listOf(PING, "-6")
    }

    /** Runs a command and streams its output lines; cancelling the collector kills the process. */
    private fun execLines(args: List<String>): Flow<String> = channelFlow {
        val process = ProcessBuilder(args).redirectErrorStream(true).start()
        // Destroying the process closes its stdout, which unblocks the read below.
        val killer = launch { try { awaitCancellation() } finally { process.destroy() } }
        try {
            withContext(Dispatchers.IO) {
                process.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) send(line)
                }
                process.waitFor()
            }
        } catch (e: IOException) {
            if (isActive) throw e
        } finally {
            killer.cancel()
        }
    }
}
