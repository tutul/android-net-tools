package today.tutul.nettools.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import today.tutul.nettools.net.ssh.HostKeyChangedException
import today.tutul.nettools.net.ssh.HostKeyUnknownException
import today.tutul.nettools.net.ssh.ServerHostKey
import today.tutul.nettools.net.ssh.SshClient
import today.tutul.nettools.net.ssh.SshConnection
import today.tutul.nettools.net.ssh.SshKeys
import today.tutul.nettools.net.ssh.SshOutput
import today.tutul.nettools.net.ssh.SshServer
import today.tutul.nettools.net.ssh.SshStore

data class OutputLine(val kind: Kind, val text: String) {
    enum class Kind { Command, Stdout, Stderr, Info }
}

/** Shown when the server's host key needs a decision; [password] is kept only until then. */
data class HostKeyPrompt(val server: SshServer, val key: ServerHostKey, val changed: Boolean, val password: String?)

class SshViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SshStore(app)
    private val keys = SshKeys(app)

    var servers by mutableStateOf(store.servers())
        private set
    var publicKey by mutableStateOf(keys.publicKey())
        private set
    var editing by mutableStateOf<SshServer?>(null)
    var passwordFor by mutableStateOf<SshServer?>(null)
    var hostKeyPrompt by mutableStateOf<HostKeyPrompt?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var connecting by mutableStateOf(false)
        private set

    var connected by mutableStateOf<SshServer?>(null)
        private set
    private var connection: SshConnection? = null
    var command by mutableStateOf("")
    var running by mutableStateOf(false)
        private set
    var history by mutableStateOf<List<String>>(emptyList())
        private set
    val output = mutableStateListOf<OutputLine>()
    private var job: Job? = null

    fun generateKey() {
        viewModelScope.launch {
            publicKey = withContext(Dispatchers.Default) {
                runCatching { keys.generate() }.onFailure {
                    Log.w(TAG, "key generation failed", it)
                    error = "Key generation failed: ${it.message ?: it.javaClass.simpleName}"
                }.getOrNull()
            }
        }
    }

    fun saveServer(server: SshServer) {
        store.save(server)
        servers = store.servers()
        editing = null
    }

    fun deleteServer(server: SshServer) {
        store.delete(server)
        servers = store.servers()
        editing = null
    }

    fun forgetHostKey(server: SshServer) = store.forgetHostKey(server)

    /** Key servers connect right away; password servers ask for the password first. */
    fun connect(server: SshServer) {
        if (server.useKey) doConnect(server, null) else passwordFor = server
    }

    fun connectWithPassword(server: SshServer, password: String) {
        passwordFor = null
        doConnect(server, password)
    }

    fun trustHostKey(prompt: HostKeyPrompt) {
        hostKeyPrompt = null
        store.saveHostKey(prompt.server, prompt.key.encoded)
        doConnect(prompt.server, prompt.password)
    }

    fun dismissHostKey() {
        hostKeyPrompt = null
    }

    private fun doConnect(server: SshServer, password: String?) {
        if (connecting) return
        connecting = true
        error = null
        viewModelScope.launch {
            val privateKey = if (server.useKey) withContext(Dispatchers.IO) { keys.privateKey() } else null
            try {
                connection = SshClient.connect(server, store.hostKey(server), privateKey, publicKey, password)
                connected = server
                history = store.history(server)
                output.clear()
                output += OutputLine(OutputLine.Kind.Info, "Connected to ${server.user}@${server.host}:${server.port}")
            } catch (e: HostKeyUnknownException) {
                hostKeyPrompt = HostKeyPrompt(server, e.key, changed = false, password = password)
            } catch (e: HostKeyChangedException) {
                hostKeyPrompt = HostKeyPrompt(server, e.key, changed = true, password = null)
            } catch (e: Exception) {
                error = "Could not connect: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                privateKey?.fill(0)
                connecting = false
            }
        }
    }

    fun run() {
        val server = connected ?: return
        val conn = connection ?: return
        val cmd = command.trim()
        if (running || cmd.isEmpty()) return
        if (!conn.isConnected) {
            output += OutputLine(OutputLine.Kind.Info, "Connection lost. Disconnect and connect again.")
            return
        }
        store.addHistory(server, cmd)
        history = store.history(server)
        command = ""
        output += OutputLine(OutputLine.Kind.Command, "$ $cmd")
        running = true
        job = viewModelScope.launch {
            try {
                conn.exec(cmd).collect { out ->
                    when (out) {
                        is SshOutput.Stdout -> output += OutputLine(OutputLine.Kind.Stdout, out.line)
                        is SshOutput.Stderr -> output += OutputLine(OutputLine.Kind.Stderr, out.line)
                        is SshOutput.Exit -> output += OutputLine(OutputLine.Kind.Info, "exit ${out.status}")
                    }
                    if (output.size > MAX_LINES) output.removeRange(0, output.size - MAX_LINES)
                }
            } catch (e: CancellationException) {
                output += OutputLine(OutputLine.Kind.Info, "— stopped —")
                throw e
            } catch (e: Exception) {
                output += OutputLine(OutputLine.Kind.Stderr, "Error: ${e.message}")
            } finally {
                running = false
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun disconnect() {
        job?.cancel()
        val conn = connection
        connection = null
        connected = null
        viewModelScope.launch(Dispatchers.IO) { conn?.close() }
    }

    override fun onCleared() {
        connection?.close()
    }

    private companion object {
        const val TAG = "SshViewModel"
        const val MAX_LINES = 2000
    }
}

@Composable
fun SshScreen(vm: SshViewModel = viewModel()) {
    if (vm.connected == null) ServerList(vm) else Session(vm)

    vm.editing?.let { EditServerDialog(vm, it) }
    vm.passwordFor?.let { PasswordDialog(vm, it) }
    vm.hostKeyPrompt?.let { HostKeyDialog(vm, it) }
}

@Composable
private fun ServerList(vm: SshViewModel) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionTitle("Servers")
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                Button(onClick = { vm.editing = SshServer(name = "", host = "", user = "") }) { Text("Add server") }
            }
        }
        if (vm.connecting) Text("Connecting…")
        vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (vm.servers.isEmpty()) {
            Text("No servers yet.", style = MaterialTheme.typography.bodySmall)
        }
        vm.servers.forEach { server ->
            Card(Modifier.fillMaxWidth().clickable(enabled = !vm.connecting) { vm.connect(server) }) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(server.label, fontWeight = FontWeight.Bold)
                        Mono("${server.user}@${server.host}:${server.port}")
                        Text(if (server.useKey) "key" else "password", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { vm.editing = server }) { Text("Edit") }
                }
            }
        }

        SectionTitle("This phone's key")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val key = vm.publicKey
                if (key == null) {
                    Text("Generate an ed25519 key to log in without a password.")
                    Button(onClick = vm::generateKey) { Text("Generate key") }
                } else {
                    Text(
                        "Add this line to ~/.ssh/authorized_keys on each server:",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    SelectionContainer { Mono(key) }
                    OutlinedButton(onClick = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("SSH public key", key))
                    }) { Text("Copy public key") }
                }
            }
        }
    }
}

@Composable
private fun Session(vm: SshViewModel) {
    val keyboard = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    LaunchedEffect(vm.output.size) {
        if (vm.output.isNotEmpty()) listState.animateScrollToItem(vm.output.lastIndex)
    }
    val server = vm.connected ?: return

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(server.label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = vm::disconnect) { Text("Disconnect") }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = vm.command,
                onValueChange = { vm.command = it },
                label = { Text("Command") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Send,
                ),
                keyboardActions = KeyboardActions(onSend = { vm.run() }),
                modifier = Modifier.weight(1f),
            )
            if (vm.running) {
                Button(onClick = vm::stop) { Text("Stop") }
            } else {
                Button(onClick = { keyboard?.hide(); vm.run() }) { Text("Run") }
            }
        }
        if (vm.history.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(vm.history) { cmd ->
                    AssistChip(onClick = { vm.command = cmd }, label = { Text(cmd, maxLines = 1) })
                }
            }
        }
        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                items(vm.output) { line ->
                    val color = when (line.kind) {
                        OutputLine.Kind.Command -> MaterialTheme.colorScheme.primary
                        OutputLine.Kind.Stderr -> MaterialTheme.colorScheme.error
                        OutputLine.Kind.Info -> MaterialTheme.colorScheme.outline
                        OutputLine.Kind.Stdout -> Color.Unspecified
                    }
                    Text(
                        line.text,
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (line.kind == OutputLine.Kind.Command) FontWeight.Bold else null,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = if (line.kind == OutputLine.Kind.Command) 8.dp else 0.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun EditServerDialog(vm: SshViewModel, original: SshServer) {
    var name by remember { mutableStateOf(original.name) }
    var host by remember { mutableStateOf(original.host) }
    var port by remember { mutableStateOf(original.port.toString()) }
    var user by remember { mutableStateOf(original.user) }
    var useKey by remember { mutableStateOf(original.useKey) }
    val isNew = vm.servers.none { it.id == original.id }
    val valid = host.isNotBlank() && !host.any(Char::isWhitespace) && user.isNotBlank() &&
        port.toIntOrNull() in 1..65535

    AlertDialog(
        onDismissRequest = { vm.editing = null },
        title = { Text(if (isNew) "Add server" else "Edit server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name (optional)") }, singleLine = true)
                OutlinedTextField(
                    host, { host = it.trim() }, label = { Text("Host") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        user, { user = it.trim() }, label = { Text("User") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        port, { v -> port = v.filter(Char::isDigit).take(5) }, label = { Text("Port") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(90.dp),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = useKey, onClick = { useKey = true }, label = { Text("Key") })
                    FilterChip(selected = !useKey, onClick = { useKey = false }, label = { Text("Password") })
                }
                if (!isNew) {
                    Row {
                        TextButton(onClick = { vm.forgetHostKey(original) }) { Text("Forget host key") }
                        TextButton(onClick = { vm.deleteServer(original) }) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                vm.saveServer(original.copy(name = name.trim(), host = host, port = port.toInt(), user = user, useKey = useKey))
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = { vm.editing = null }) { Text("Cancel") } },
    )
}

@Composable
private fun PasswordDialog(vm: SshViewModel, server: SshServer) {
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { vm.passwordFor = null },
        title = { Text("Password for ${server.user}@${server.host}") },
        text = {
            OutlinedTextField(
                password, { password = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { vm.connectWithPassword(server, password) }),
            )
        },
        confirmButton = { TextButton(onClick = { vm.connectWithPassword(server, password) }) { Text("Connect") } },
        dismissButton = { TextButton(onClick = { vm.passwordFor = null }) { Text("Cancel") } },
    )
}

@Composable
private fun HostKeyDialog(vm: SshViewModel, prompt: HostKeyPrompt) {
    AlertDialog(
        onDismissRequest = vm::dismissHostKey,
        title = { Text(if (prompt.changed) "⚠ Host key changed" else "New server") },
        text = {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (prompt.changed) {
                        Text(
                            "${prompt.server.host} is presenting a different key than before. Someone may be " +
                                "intercepting the connection. If you reinstalled the server or changed its keys, " +
                                "use Edit → Forget host key, then connect again.",
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            "First connection to ${prompt.server.host}. Check that this fingerprint matches the " +
                                "one the server shows for `for f in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf \$f; done`.",
                        )
                    }
                    Text(prompt.key.type, style = MaterialTheme.typography.labelMedium)
                    Mono(prompt.key.fingerprint)
                }
            }
        },
        confirmButton = {
            if (!prompt.changed) TextButton(onClick = { vm.trustHostKey(prompt) }) { Text("Trust and connect") }
        },
        dismissButton = { TextButton(onClick = vm::dismissHostKey) { Text("Cancel") } },
    )
}
