package today.tutul.nettools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import today.tutul.nettools.net.Hop
import today.tutul.nettools.net.Ping
import today.tutul.nettools.net.Traceroute
import java.net.InetAddress

class TracerouteViewModel : ViewModel() {
    var host by mutableStateOf("1.1.1.1")
    var ipv6 by mutableStateOf(false)
    var resolveNames by mutableStateOf(false)
    var running by mutableStateOf(false)
        private set
    var status by mutableStateOf("")
        private set
    val hops = mutableStateListOf<Hop>()
    private var job: Job? = null

    fun start() {
        if (running || host.isBlank()) return
        hops.clear()
        running = true
        job = viewModelScope.launch {
            try {
                val target = Ping.resolve(host, ipv6)
                status = "${host.trim()} → ${target.hostAddress}"
                Traceroute.run(target, MAX_HOPS).collect { hop ->
                    hops += hop
                    val ip = hop.ips.firstOrNull()
                    if (resolveNames && ip != null) lookupName(hops.lastIndex, ip)
                }
                val last = hops.lastOrNull()
                status += when {
                    last?.reached == true -> "  · reached"
                    last?.note != null -> "  · ${last.note}"
                    else -> "  · not reached in $MAX_HOPS hops"
                }
            } catch (e: CancellationException) {
                status += "  · stopped"
                throw e
            } catch (e: Exception) {
                status = "Error: ${e.message}"
            } finally {
                running = false
            }
        }
    }

    // Reverse DNS can take seconds, so it fills in after the hop is shown.
    private fun lookupName(index: Int, ip: String) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()
            }
            if (name != null && name != ip && index < hops.size && hops[index].ips.firstOrNull() == ip) {
                hops[index] = hops[index].copy(hostname = name)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private companion object {
        const val MAX_HOPS = 30
    }
}

@Composable
fun TracerouteScreen(vm: TracerouteViewModel = viewModel()) {
    val keyboard = LocalSoftwareKeyboardController.current

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = vm.host,
                onValueChange = { vm.host = it },
                label = { Text("Host or IP") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { keyboard?.hide(); vm.start() }),
                modifier = Modifier.weight(1f),
            )
            if (vm.running) {
                Button(onClick = vm::stop) { Text("Stop") }
            } else {
                Button(onClick = { keyboard?.hide(); vm.start() }) { Text("Start") }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("IPv6")
            Switch(checked = vm.ipv6, onCheckedChange = { vm.ipv6 = it })
            Text("Reverse DNS")
            Switch(checked = vm.resolveNames, onCheckedChange = { vm.resolveNames = it })
        }
        Text(vm.status, style = MaterialTheme.typography.bodySmall)
        SelectionContainer {
            LazyColumn(Modifier.fillMaxSize()) {
                items(vm.hops) { hop ->
                    val rtts = hop.rttsMs.joinToString("  ") { rtt -> rtt?.let { "%.1f".format(it) } ?: "*" }
                    Column(Modifier.padding(vertical = 2.dp)) {
                        Mono("%2d  %s".format(hop.ttl, hop.ips.firstOrNull() ?: "*"))
                        hop.ips.drop(1).forEach { Mono("    $it") }
                        Mono("    $rtts ms")
                        hop.hostname?.let {
                            Text("    $it", style = MaterialTheme.typography.bodySmall)
                        }
                        hop.note?.let {
                            Text("    $it", color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
