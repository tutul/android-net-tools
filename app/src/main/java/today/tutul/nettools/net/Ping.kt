package today.tutul.nettools.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * Wraps the system's own ping binary. Apps can't open raw sockets, but
 * /system/bin/ping uses unprivileged ICMP sockets, so it works without root.
 */
object Ping {
    private const val PING = "/system/bin/ping"
    private const val PING6 = "/system/bin/ping6"

    private val REPLY = Regex("""bytes from ([0-9a-fA-F.:%\w]+?):? .*time[=<]([\d.]+)""")

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
