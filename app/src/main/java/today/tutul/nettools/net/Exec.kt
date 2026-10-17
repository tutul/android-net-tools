package today.tutul.nettools.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** Runs a command and streams its output lines; cancelling the collector kills the process. */
fun execLines(args: List<String>, env: Map<String, String> = emptyMap()): Flow<String> = channelFlow {
    val process = ProcessBuilder(args)
        .redirectErrorStream(true)
        .apply { environment().putAll(env) }
        .start()
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
