package today.tutul.nettools.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import today.tutul.nettools.net.Ping

data class PingStats(val received: Int = 0, val min: Double = 0.0, val max: Double = 0.0, val sum: Double = 0.0) {
    operator fun plus(rtt: Double) = PingStats(
        received = received + 1,
        min = if (received == 0) rtt else minOf(min, rtt),
        max = maxOf(max, rtt),
        sum = sum + rtt,
    )

    override fun toString() =
        if (received == 0) "no replies yet"
        else "replies %d   min %.1f   avg %.1f   max %.1f ms".format(received, min, sum / received, max)
}

class PingViewModel : ViewModel() {
    var host by mutableStateOf("1.1.1.1")
    var count by mutableStateOf("4")
    var ipv6 by mutableStateOf(false)
    var running by mutableStateOf(false)
        private set
    var stats by mutableStateOf(PingStats())
        private set
    val lines = mutableStateListOf<String>()
    private var job: Job? = null

    fun start() {
        if (running || host.isBlank()) return
        lines.clear()
        stats = PingStats()
        running = true
        job = viewModelScope.launch {
            try {
                val target = Ping.resolve(host, ipv6)
                lines += "${host.trim()} → ${target.hostAddress}"
                Ping.ping(target, count.toIntOrNull() ?: 4).collect { line ->
                    lines += line
                    if (lines.size > MAX_LINES) lines.removeRange(0, lines.size - MAX_LINES)
                    Ping.parseRtt(line)?.let { stats += it }
                }
            } catch (e: CancellationException) {
                lines += "— stopped —"
                throw e
            } catch (e: Exception) {
                lines += "Error: ${e.message}"
            } finally {
                running = false
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private companion object {
        const val MAX_LINES = 1000
    }
}

@Composable
fun PingScreen(vm: PingViewModel = viewModel()) {
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    LaunchedEffect(vm.lines.size) {
        if (vm.lines.isNotEmpty()) listState.animateScrollToItem(vm.lines.lastIndex)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = vm.host,
            onValueChange = { vm.host = it },
            label = { Text("Host or IP") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { keyboard?.hide(); vm.start() }),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = vm.count,
                onValueChange = { v -> vm.count = v.filter(Char::isDigit).take(4) },
                label = { Text("Count (0 = ∞)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(140.dp),
            )
            Text("IPv6")
            Switch(checked = vm.ipv6, onCheckedChange = { vm.ipv6 = it })
            if (vm.running) {
                Button(onClick = vm::stop) { Text("Stop") }
            } else {
                Button(onClick = { keyboard?.hide(); vm.start() }) { Text("Start") }
            }
        }
        Text(vm.stats.toString())
        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(vm.lines) { Mono(it) }
            }
        }
    }
}
