package today.tutul.nettools.net

import android.content.Context
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.File

data class IperfServer(val name: String, val host: String, val ports: IntRange)

/**
 * Public servers from iperf.fr's list, each confirmed to answer an iperf 3.22
 * client on 2026-10-18. They are shared and often busy, which is why each one
 * carries a port range to rotate through.
 */
val PUBLIC_IPERF_SERVERS = listOf(
    IperfServer("Scaleway · Paris, FR", "ping.online.net", 5200..5209),
    IperfServer("Moji · Paris, FR", "iperf3.moji.fr", 5200..5240),
    IperfServer("MilkyWAN · Paris, FR", "speedtest.milkywan.fr", 9200..9240),
    IperfServer("Serverius · NL", "speedtest1.serverius.net", 5002..5002),
    IperfServer("Astra · Lviv, UA", "iperf.astra.in.ua", 5201..5206),
    IperfServer("Uztelecom · Tashkent, UZ", "speedtest.uztelecom.uz", 5200..5209),
    IperfServer("Hurricane Electric · Fremont, US", "iperf.he.net", 5201..5201),
)

data class IperfOptions(
    val host: String,
    val ports: IntRange,
    /** false = phone sends (upload), true = server sends (download, iperf3 -R). */
    val reverse: Boolean,
    val udp: Boolean,
    val seconds: Int,
    /** Stop once this much data has moved, whichever of this and [seconds] comes first; null = no limit. */
    val maxBytes: Long?,
    val parallel: Int,
    val udpMbps: Int,
)

sealed interface IperfEvent {
    data class Connecting(val port: Int) : IperfEvent
    /** We asked iperf3 to wrap up early; the following Summary is partial. */
    data class Stopped(val reason: String) : IperfEvent
    data class Connected(val local: String, val remote: String) : IperfEvent
    data class Interval(
        val start: Double,
        val end: Double,
        val bytes: Long,
        val bitsPerSecond: Double,
        val retransmits: Int?,
        val jitterMs: Double?,
        val lostPercent: Double?,
    ) : IperfEvent
    data class Summary(
        val sentBytes: Long,
        val receivedBytes: Long,
        val sentBps: Double,
        val receivedBps: Double,
        val retransmits: Int?,
        val jitterMs: Double?,
        val lostPercent: Double?,
    ) : IperfEvent
    data class Error(val message: String) : IperfEvent
    /** Anything iperf3 printed that isn't one of its JSON events, e.g. warnings. */
    data class Log(val line: String) : IperfEvent
}

/**
 * Runs the official iperf3 (built by scripts/build-iperf3.sh and shipped as
 * libiperf3.so) and turns its --json-stream output into events.
 */
class Iperf(context: Context) {
    private val binary = File(context.applicationInfo.nativeLibraryDir, "libiperf3.so")
    // iperf3 needs a writable temp dir; its Android default isn't writable by apps.
    private val env = mapOf("TMPDIR" to context.cacheDir.path)

    @Volatile private var process: Process? = null
    @Volatile private var stopReason: String? = null

    fun run(options: IperfOptions): Flow<IperfEvent> = flow {
        if (!binary.exists()) {
            emit(IperfEvent.Error("iperf3 is not built for this device's CPU"))
            return@flow
        }
        stopReason = null
        for (port in options.ports) {
            emit(IperfEvent.Connecting(port))
            var busy = false
            coroutineScope {
                var timer: Job? = null
                execLines(command(options, port), env, onStart = { process = it }).collect { line ->
                    val event = parse(line)
                    when {
                        // iperf3 reports our own SIGTERM as an error; the summary still follows.
                        event is IperfEvent.Error && stopReason != null && "interrupt" in event.message -> {}
                        event is IperfEvent.Error && "busy" in event.message && port != options.ports.last -> {
                            busy = true
                            emit(IperfEvent.Log("port $port is busy, trying the next one"))
                        }
                        else -> emit(event)
                    }
                    // With a data cap, iperf3 runs with -n and stops itself at exactly that
                    // many bytes; it can't also take -t, so the time limit is our timer.
                    if (event is IperfEvent.Connected && options.maxBytes != null && timer == null) {
                        timer = launch {
                            delay(options.seconds * 1000L)
                            stop("time limit reached")
                        }
                    }
                }
                timer?.cancel()
            }
            process = null
            if (!busy || stopReason != null) break
        }
        stopReason?.let { emit(IperfEvent.Stopped(it)) }
    }

    /**
     * Asks iperf3 to finish early with SIGTERM. It then prints its results and exits.
     * Process.destroy() would also close the output stream and lose those results.
     */
    fun stop(reason: String) {
        if (stopReason != null) return
        stopReason = reason
        if (process?.isAlive != true) return
        val pid = findPid() ?: return
        runCatching { Os.kill(pid, OsConstants.SIGTERM) }
    }

    // Android's Process has no pid() before API 33; /proc only lists our own processes.
    private fun findPid(): Int? = File("/proc").listFiles().orEmpty()
        .mapNotNull { it.name.toIntOrNull() }
        .firstOrNull { pid ->
            runCatching { File("/proc/$pid/cmdline").readText() }.getOrNull()
                ?.startsWith(binary.path) == true
        }

    private fun command(o: IperfOptions, port: Int): List<String> = buildList {
        addAll(listOf(binary.path, "-c", o.host, "-p", port.toString()))
        if (o.maxBytes != null) addAll(listOf("-n", o.maxBytes.toString()))
        else addAll(listOf("-t", o.seconds.toString()))
        addAll(listOf("-P", o.parallel.toString()))
        addAll(listOf("--json-stream", "--forceflush", "--connect-timeout", "5000"))
        if (o.reverse) add("-R")
        if (o.udp) addAll(listOf("-u", "-b", "${o.udpMbps}M"))
    }

    private fun parse(line: String): IperfEvent {
        val json = try {
            JSONObject(line)
        } catch (e: JSONException) {
            return IperfEvent.Log(line)
        }
        val data = json.opt("data")
        return when (json.optString("event")) {
            "start" -> {
                val c = (data as? JSONObject)?.optJSONArray("connected")?.optJSONObject(0)
                    ?: return IperfEvent.Log(line)
                IperfEvent.Connected(
                    local = "${c.optString("local_host")}:${c.optInt("local_port")}",
                    remote = "${c.optString("remote_host")}:${c.optInt("remote_port")}",
                )
            }
            "interval" -> {
                val sum = (data as? JSONObject)?.optJSONObject("sum") ?: return IperfEvent.Log(line)
                IperfEvent.Interval(
                    start = sum.optDouble("start"),
                    end = sum.optDouble("end"),
                    bytes = sum.optLong("bytes"),
                    bitsPerSecond = sum.optDouble("bits_per_second"),
                    retransmits = sum.intOrNull("retransmits"),
                    jitterMs = sum.doubleOrNull("jitter_ms"),
                    lostPercent = sum.doubleOrNull("lost_percent"),
                )
            }
            "end" -> {
                val end = data as? JSONObject ?: return IperfEvent.Log(line)
                val sent = end.optJSONObject("sum_sent") ?: end.optJSONObject("sum")
                val received = end.optJSONObject("sum_received") ?: end.optJSONObject("sum")
                if (sent == null || received == null) return IperfEvent.Log(line)
                IperfEvent.Summary(
                    sentBytes = sent.optLong("bytes"),
                    receivedBytes = received.optLong("bytes"),
                    sentBps = sent.optDouble("bits_per_second"),
                    receivedBps = received.optDouble("bits_per_second"),
                    retransmits = sent.intOrNull("retransmits"),
                    jitterMs = received.doubleOrNull("jitter_ms"),
                    lostPercent = received.doubleOrNull("lost_percent"),
                )
            }
            "error" -> IperfEvent.Error(data?.toString() ?: "unknown error")
            else -> IperfEvent.Log(line)
        }
    }

    private fun JSONObject.intOrNull(key: String) = if (has(key)) optInt(key) else null
    private fun JSONObject.doubleOrNull(key: String) = if (has(key)) optDouble(key) else null
}
