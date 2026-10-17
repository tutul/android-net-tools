package today.tutul.nettools.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
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
import kotlinx.coroutines.launch
import today.tutul.nettools.net.Iperf
import today.tutul.nettools.net.IperfEvent
import today.tutul.nettools.net.IperfOptions
import today.tutul.nettools.net.PUBLIC_IPERF_SERVERS

class IperfViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("iperf", Context.MODE_PRIVATE)
    private val iperf = Iperf(app)

    // The last server used is remembered across app restarts.
    var host by mutableStateOf(prefs.getString("host", "") ?: "")
    var ports by mutableStateOf(prefs.getString("ports", "5201") ?: "5201")
    var reverse by mutableStateOf(false)
    var udp by mutableStateOf(false)
    var seconds by mutableStateOf("10")
    var parallel by mutableStateOf("1")
    var udpMbps by mutableStateOf("10")

    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    var summary by mutableStateOf<IperfEvent.Summary?>(null)
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
        prefs.edit { putString("host", target); putString("ports", ports.trim()) }
        val options = IperfOptions(
            host = target,
            ports = portRange,
            reverse = reverse,
            udp = udp,
            seconds = seconds.toIntOrNull()?.coerceIn(1, 300) ?: 10,
            parallel = parallel.toIntOrNull()?.coerceIn(1, 16) ?: 1,
            udpMbps = udpMbps.toIntOrNull()?.coerceIn(1, 10_000) ?: 10,
        )
        intervals.clear()
        messages.clear()
        summary = null
        running = true
        job = viewModelScope.launch {
            try {
                iperf.run(options).collect { event ->
                    when (event) {
                        is IperfEvent.Connecting -> status = "Connecting to $target:${event.port}…"
                        is IperfEvent.Connected -> status = "${event.local} → ${event.remote}"
                        is IperfEvent.Interval -> intervals += event
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
            }
        }
    }

    fun stop() {
        job?.cancel()
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
                if (vm.udp) {
                    NumberField("Mbit/s", vm.udpMbps) { vm.udpMbps = it }
                } else {
                    NumberField("Streams", vm.parallel) { vm.parallel = it }
                }
                if (vm.running) {
                    Button(onClick = vm::stop) { Text("Stop") }
                } else {
                    Button(onClick = { keyboard?.hide(); vm.start() }) { Text("Start") }
                }
            }
        }
        item {
            Text(vm.status, style = MaterialTheme.typography.bodySmall)
            vm.intervals.lastOrNull()?.takeIf { vm.running }?.let {
                Text(formatBps(it.bitsPerSecond), style = MaterialTheme.typography.headlineMedium)
            }
        }
        vm.summary?.let { s ->
            item { SummaryCard(s, reverse = vm.reverse) }
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
private fun SummaryCard(s: IperfEvent.Summary, reverse: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(Modifier.padding(12.dp)) {
                Text(if (reverse) "Download result" else "Upload result", fontWeight = FontWeight.Bold)
                Text(formatBps(s.receivedBps), style = MaterialTheme.typography.headlineMedium)
                Mono("sender    ${formatBps(s.sentBps)}")
                Mono("receiver  ${formatBps(s.receivedBps)}")
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

private fun formatBps(bps: Double): String = when {
    bps >= 1e9 -> "%.2f Gbit/s".format(bps / 1e9)
    bps >= 1e6 -> "%.1f Mbit/s".format(bps / 1e6)
    else -> "%.0f kbit/s".format(bps / 1e3)
}
