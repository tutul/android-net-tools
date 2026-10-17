package today.tutul.nettools.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import today.tutul.nettools.net.Iperf
import today.tutul.nettools.net.IperfEvent
import today.tutul.nettools.net.IperfOptions
import today.tutul.nettools.net.PUBLIC_IPERF_SERVERS

class IperfViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("iperf", Context.MODE_PRIVATE)
    private val iperf = Iperf(app)

    // The last settings used are remembered across app restarts.
    var host by mutableStateOf(prefs.getString("host", "") ?: "")
    var ports by mutableStateOf(prefs.getString("ports", "5201") ?: "5201")
    var reverse by mutableStateOf(prefs.getBoolean("reverse", false))
    var udp by mutableStateOf(prefs.getBoolean("udp", false))
    var seconds by mutableStateOf(prefs.getString("seconds", "10") ?: "10")
    /** Data cap in MB (1 MB = 1,000,000 bytes); blank or 0 = no limit. */
    var maxMb by mutableStateOf(prefs.getString("maxMb", "100") ?: "100")
    var parallel by mutableStateOf(prefs.getString("parallel", "1") ?: "1")
    var udpMbps by mutableStateOf(prefs.getString("udpMbps", "10") ?: "10")

    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var stopping by mutableStateOf(false)
        private set
    var summary by mutableStateOf<IperfEvent.Summary?>(null)
        private set
    /** Why the test ended early, if it did; the summary is then partial. */
    var stoppedReason by mutableStateOf<String?>(null)
        private set
    var transferred by mutableStateOf(0L)
        private set
    val intervals = mutableStateListOf<IperfEvent.Interval>()
    val messages = mutableStateListOf<String>()
    private var job: Job? = null

    fun start() {
        if (running) return
        val portRange = parsePorts(ports)
        val target = host.trim()
        if (target.isEmpty() || target.any(Char::isWhitespace) || portRange == null) {
            status = "Enter a host and a port (e.g. 5201 or 5200-5209)"
            return
        }
        prefs.edit {
            putString("host", target)
            putString("ports", ports.trim())
            putBoolean("reverse", reverse)
            putBoolean("udp", udp)
            putString("seconds", seconds)
            putString("maxMb", maxMb)
            putString("parallel", parallel)
            putString("udpMbps", udpMbps)
        }
        val options = IperfOptions(
            host = target,
            ports = portRange,
            reverse = reverse,
            udp = udp,
            seconds = seconds.toIntOrNull()?.coerceIn(1, 300) ?: 10,
            maxBytes = maxMb.toLongOrNull()?.takeIf { it > 0 }?.times(1_000_000),
            parallel = parallel.toIntOrNull()?.coerceIn(1, 16) ?: 1,
            udpMbps = udpMbps.toIntOrNull()?.coerceIn(1, 10_000) ?: 10,
        )
        intervals.clear()
        messages.clear()
        summary = null
        stoppedReason = null
        transferred = 0
        running = true
        job = viewModelScope.launch {
            try {
                iperf.run(options).collect { event ->
                    when (event) {
                        is IperfEvent.Connecting -> status = "Connecting to $target:${event.port}…"
                        is IperfEvent.Connected -> status = "${event.local} → ${event.remote}"
                        is IperfEvent.Stopped -> stoppedReason = event.reason
                        is IperfEvent.Interval -> {
                            // A stop leaves a final sliver of a few ms whose rate is noise.
                            if (event.end - event.start >= 0.1) intervals += event
                            transferred += event.bytes
                        }
                        is IperfEvent.Summary -> summary = event
                        is IperfEvent.Error -> messages += "Error: ${event.message}"
                        is IperfEvent.Log -> if (event.line.isNotBlank()) messages += event.line
                    }
                }
            } catch (e: CancellationException) {
                messages += "— stopped —"
                throw e
            } catch (e: Exception) {
                messages += "Error: ${e.message}"
            } finally {
                running = false
                stopping = false
            }
        }
    }

    /** Lets iperf3 wrap up and report what it measured; force-kills it if it hangs. */
    fun stop() {
        if (!running || stopping) return
        stopping = true
        iperf.stop("stopped by user")
        val current = job
        viewModelScope.launch {
            delay(5_000)
            current?.cancel()
        }
    }

    private fun parsePorts(text: String): IntRange? {
        val parts = text.trim().split('-').map { it.trim().toIntOrNull() ?: return null }
        val range = when (parts.size) {
            1 -> parts[0]..parts[0]
            2 -> parts[0]..parts[1]
            else -> return null
        }
        return range.takeIf { !it.isEmpty() && it.first >= 1 && it.last <= 65535 }
    }
}

@Composable
fun IperfScreen(vm: IperfViewModel = viewModel()) {
    val keyboard = LocalSoftwareKeyboardController.current
    var presetsOpen by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = vm.host,
                    onValueChange = { vm.host = it },
                    label = { Text("Server") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = vm.ports,
                    onValueChange = { v -> vm.ports = v.filter { it.isDigit() || it == '-' }.take(11) },
                    label = { Text("Port") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(120.dp),
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { presetsOpen = true }) { Text("Public servers ▾") }
                    DropdownMenu(expanded = presetsOpen, onDismissRequest = { presetsOpen = false }) {
                        PUBLIC_IPERF_SERVERS.forEach { server ->
                            val portText = if (server.ports.first == server.ports.last) "${server.ports.first}"
                                else "${server.ports.first}-${server.ports.last}"
                            DropdownMenuItem(
                                text = {
                                    Column {
                                        Text(server.name)
                                        Text("${server.host}:$portText", style = MaterialTheme.typography.bodySmall)
                                    }
                                },
                                onClick = {
                                    vm.host = server.host
                                    vm.ports = portText
                                    presetsOpen = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                if (vm.running) {
                    Button(onClick = vm::stop, enabled = !vm.stopping) {
                        Text(if (vm.stopping) "Stopping…" else "Stop")
                    }
                } else {
                    Button(onClick = { keyboard?.hide(); vm.start() }) { Text("Start") }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !vm.reverse, onClick = { vm.reverse = false }, label = { Text("Upload") })
                FilterChip(selected = vm.reverse, onClick = { vm.reverse = true }, label = { Text("Download") })
                FilterChip(selected = !vm.udp, onClick = { vm.udp = false }, label = { Text("TCP") })
                FilterChip(selected = vm.udp, onClick = { vm.udp = true }, label = { Text("UDP") })
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Seconds", vm.seconds) { vm.seconds = it }
                NumberField("Max MB", vm.maxMb) { vm.maxMb = it }
                if (vm.udp) {
                    NumberField("Mbit/s", vm.udpMbps) { vm.udpMbps = it }
                } else {
                    NumberField("Streams", vm.parallel) { vm.parallel = it }
                }
            }
            Text(
                "Stops at whichever comes first. Max MB blank or 0 = no limit.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Text(vm.status, style = MaterialTheme.typography.bodySmall)
            vm.intervals.lastOrNull()?.takeIf { vm.running }?.let {
                Text(formatBps(it.bitsPerSecond), style = MaterialTheme.typography.headlineMedium)
                Text("transferred ${formatBytes(vm.transferred)}", style = MaterialTheme.typography.bodySmall)
            }
        }
        vm.summary?.let { s ->
            item { SummaryCard(s, reverse = vm.reverse, stoppedReason = vm.stoppedReason) }
        }
        items(vm.messages) { line ->
            Text(line, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        items(vm.intervals) { iv ->
            val extra = when {
                iv.lostPercent != null -> "  jitter %.2f ms  loss %.1f%%".format(iv.jitterMs ?: 0.0, iv.lostPercent)
                iv.retransmits != null -> "  retr ${iv.retransmits}"
                else -> ""
            }
            Mono("%5.1f-%5.1f s  %s%s".format(iv.start, iv.end, formatBps(iv.bitsPerSecond), extra))
        }
    }
}

@Composable
private fun SummaryCard(s: IperfEvent.Summary, reverse: Boolean, stoppedReason: String?) {
    Card(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(Modifier.padding(12.dp)) {
                Text(if (reverse) "Download result" else "Upload result", fontWeight = FontWeight.Bold)
                if (stoppedReason == null) {
                    Text(formatBps(s.receivedBps), style = MaterialTheme.typography.headlineMedium)
                    Mono("sender    ${formatBps(s.sentBps)}  ${formatBytes(s.sentBytes)}")
                    Mono("receiver  ${formatBps(s.receivedBps)}  ${formatBytes(s.receivedBytes)}")
                } else {
                    // Stopped early: iperf3 only reports this side, the server's numbers never arrive.
                    val bps = if (reverse) s.receivedBps else s.sentBps
                    val bytes = if (reverse) s.receivedBytes else s.sentBytes
                    Text(formatBps(bps), style = MaterialTheme.typography.headlineMedium)
                    Mono("${if (reverse) "received" else "sent"}  ${formatBytes(bytes)}")
                    Text("Partial result: $stoppedReason", style = MaterialTheme.typography.bodySmall)
                }
                s.retransmits?.let { Mono("retransmits $it") }
                if (s.lostPercent != null) {
                    Mono("jitter %.2f ms   loss %.2f%%".format(s.jitterMs ?: 0.0, s.lostPercent))
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter(Char::isDigit).take(5)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.width(100.dp),
    )
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1e9)
    else -> "%.1f MB".format(bytes / 1e6)
}

private fun formatBps(bps: Double): String = when {
    bps >= 1e9 -> "%.2f Gbit/s".format(bps / 1e9)
    bps >= 1e6 -> "%.1f Mbit/s".format(bps / 1e6)
    else -> "%.0f kbit/s".format(bps / 1e3)
}
