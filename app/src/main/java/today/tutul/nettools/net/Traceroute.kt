package today.tutul.nettools.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.Inet6Address
import java.net.InetAddress

/** JNI bindings for app/src/main/cpp/traceroute.c. */
object NativeTrace {
    init {
        System.loadLibrary("nettools")
    }

    external fun open(v6: Boolean): Int
    external fun close(fd: Int)
    external fun probe(fd: Int, addr: ByteArray, scopeId: Int, ttl: Int, seq: Int, timeoutMs: Int): String?
}

data class Hop(
    val ttl: Int,
    val ips: List<String>,
    /** One entry per probe; null means that probe timed out. */
    val rttsMs: List<Double?>,
    val reached: Boolean,
    val note: String? = null,
    val hostname: String? = null,
)

object Traceroute {
    private const val PROBES_PER_HOP = 3
    private const val TIMEOUT_MS = 1000

    fun run(target: InetAddress, maxHops: Int): Flow<Hop> = flow {
        val scopeId = (target as? Inet6Address)?.scopeId ?: 0
        val fd = NativeTrace.open(target is Inet6Address)
        try {
            for (ttl in 1..maxHops) {
                val ips = mutableListOf<String>()
                val rtts = mutableListOf<Double?>()
                var reached = false
                var note: String? = null
                repeat(PROBES_PER_HOP) { i ->
                    currentCoroutineContext().ensureActive()
                    val seq = ttl * PROBES_PER_HOP + i
                    val reply = NativeTrace.probe(fd, target.address, scopeId, ttl, seq, TIMEOUT_MS)
                    if (reply == null) {
                        rtts += null
                        return@repeat
                    }
                    val (kind, ip, rttUs, code) = reply.split(' ')
                    rtts += rttUs.toLong() / 1000.0
                    if (ip !in ips) ips += ip
                    when (kind) {
                        "R" -> reached = true
                        "U" -> note = "unreachable (code $code)"
                        "E" -> note = "error (errno $code)"
                    }
                }
                emit(Hop(ttl, ips, rtts, reached, note))
                if (reached || note != null) break
            }
        } finally {
            NativeTrace.close(fd)
        }
    }.flowOn(Dispatchers.IO)
}
