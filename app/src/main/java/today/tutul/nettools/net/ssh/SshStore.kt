package today.tutul.nettools.net.ssh

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class SshServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    /** true = this phone's key, false = password typed on each connect (never stored). */
    val useKey: Boolean = true,
) {
    val label get() = name.ifBlank { "$user@$host" }
}

/** Saved servers, pinned host keys and per-server command history. No secrets live here. */
class SshStore(context: Context) {
    private val prefs = context.getSharedPreferences("ssh", Context.MODE_PRIVATE)

    fun servers(): List<SshServer> {
        val array = JSONArray(prefs.getString("servers", "[]"))
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            SshServer(
                id = o.getString("id"),
                name = o.optString("name"),
                host = o.getString("host"),
                port = o.optInt("port", 22),
                user = o.getString("user"),
                useKey = o.optBoolean("useKey", true),
            )
        }
    }

    fun save(server: SshServer) {
        val list = servers().filter { it.id != server.id } + server
        writeServers(list)
    }

    fun delete(server: SshServer) {
        writeServers(servers().filter { it.id != server.id })
        forgetHostKey(server)
        prefs.edit { remove("history.${server.id}") }
    }

    private fun writeServers(list: List<SshServer>) {
        val array = JSONArray()
        list.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id).put("name", it.name).put("host", it.host)
                    .put("port", it.port).put("user", it.user).put("useKey", it.useKey)
            )
        }
        prefs.edit { putString("servers", array.toString()) }
    }

    /** Base64 of the server's public host key blob, as accepted on first connect. */
    fun hostKey(server: SshServer): String? = prefs.getString(hostKeyName(server), null)

    fun saveHostKey(server: SshServer, key: String) = prefs.edit { putString(hostKeyName(server), key) }

    fun forgetHostKey(server: SshServer) = prefs.edit { remove(hostKeyName(server)) }

    private fun hostKeyName(server: SshServer) = "hostkey.${server.host.lowercase()}:${server.port}"

    fun history(server: SshServer): List<String> {
        val array = JSONArray(prefs.getString("history.${server.id}", "[]"))
        return (0 until array.length()).map { array.getString(it) }
    }

    fun addHistory(server: SshServer, command: String) {
        val list = (listOf(command) + history(server).filter { it != command }).take(MAX_HISTORY)
        prefs.edit { putString("history.${server.id}", JSONArray(list).toString()) }
    }

    private companion object {
        const val MAX_HISTORY = 20
    }
}
