package today.tutul.nettools.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import today.tutul.nettools.net.InterfaceSummary
import today.tutul.nettools.net.LocalNetwork
import today.tutul.nettools.net.NetworkSummary
import today.tutul.nettools.net.PublicIp
import today.tutul.nettools.net.PublicIpResult

class IpViewModel(app: Application) : AndroidViewModel(app) {
    var networks by mutableStateOf<List<NetworkSummary>>(emptyList())
        private set
    var interfaces by mutableStateOf<List<InterfaceSummary>>(emptyList())
        private set
    var publicIps by mutableStateOf<List<PublicIpResult>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set

    init {
        refresh()
    }

    fun refresh() {
        if (loading) return
        loading = true
        publicIps = emptyList()
        viewModelScope.launch {
            val (n, i) = withContext(Dispatchers.IO) {
                LocalNetwork.networks(getApplication()) to LocalNetwork.interfaces()
            }
            networks = n
            interfaces = i
            publicIps = PublicIp.lookupAll()
            loading = false
        }
    }
}

@Composable
fun IpScreen(vm: IpViewModel = viewModel()) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = vm::refresh, enabled = !vm.loading) { Text("Refresh") }
            if (vm.loading) {
                CircularProgressIndicator(Modifier.padding(start = 16.dp))
            }
        }

        SectionTitle("Public IP")
        for (family in listOf("IPv4", "IPv6")) {
            PublicIpCard(family, vm.publicIps.filter { it.family == family }, vm.loading)
        }

        SectionTitle("Networks")
        vm.networks.forEach { NetworkCard(it) }

        SectionTitle("All interfaces")
        Card(Modifier.fillMaxWidth()) {
            SelectionContainer {
                Column(Modifier.padding(12.dp)) {
                    vm.interfaces.forEach { nif ->
                        Text(nif.name, fontWeight = FontWeight.Bold)
                        nif.addresses.forEach { Mono("  $it") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PublicIpCard(family: String, results: List<PublicIpResult>, loading: Boolean) {
    val ips = results.mapNotNull { it.ip }.toSet()
    val verdict = when {
        results.isEmpty() -> if (loading) "checking…" else ""
        ips.isEmpty() -> "unavailable"
        ips.size == 1 && results.all { it.ip != null } -> "✓ sources agree"
        ips.size == 1 -> "only one source answered"
        else -> "⚠ sources disagree"
    }
    Card(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(Modifier.padding(12.dp)) {
                Text("$family  $verdict", fontWeight = FontWeight.Bold)
                results.forEach { r ->
                    Text(r.source, style = MaterialTheme.typography.labelMedium)
                    if (r.ip != null) {
                        Mono(r.ip)
                        r.extra?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    } else {
                        Text(r.error.orEmpty(), color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun NetworkCard(n: NetworkSummary) {
    Card(Modifier.fillMaxWidth()) {
        SelectionContainer {
            Column(Modifier.padding(12.dp)) {
                Text(
                    "${n.transport} · ${n.interfaceName ?: "?"}" + if (n.isDefault) "  (default)" else "",
                    fontWeight = FontWeight.Bold,
                )
                Labeled("Address", n.addresses)
                Labeled("Gateway", n.gateways)
                Labeled("DNS", n.dnsServers)
            }
        }
    }
}

@Composable
private fun Labeled(label: String, values: List<String>) {
    if (values.isEmpty()) return
    Text(label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
    values.forEach { Mono(it) }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
fun Mono(text: String) {
    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
}
