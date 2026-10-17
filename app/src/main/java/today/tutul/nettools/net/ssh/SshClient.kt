package today.tutul.nettools.net.ssh

import android.util.Base64
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** The server's host key, shown to the user for a trust decision. */
data class ServerHostKey(val type: String, val blob: ByteArray) {
    val encoded: String get() = Base64.encodeToString(blob, Base64.NO_WRAP)

    /** Same format as OpenSSH's `ssh-keygen -lf`: SHA256:<base64 without padding>. */
    val fingerprint: String
        get() = "SHA256:" + Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(blob), Base64.NO_WRAP or Base64.NO_PADDING
        )

    companion object {
        /** The blob starts with the key type as an SSH string: uint32 length + bytes. */
        fun of(blob: ByteArray): ServerHostKey {
            val len = (blob[0].toInt() and 0xff shl 24) or (blob[1].toInt() and 0xff shl 16) or
                (blob[2].toInt() and 0xff shl 8) or (blob[3].toInt() and 0xff)
            val type = if (len in 1..blob.size - 4) String(blob, 4, len) else "unknown"
            return ServerHostKey(type, blob)
        }
    }
}

/** First connection to this server: the user must confirm the fingerprint. */
class HostKeyUnknownException(val key: ServerHostKey) : Exception("unknown host key")

/** The server presented a different key than the one accepted before. */
class HostKeyChangedException(val key: ServerHostKey) : Exception("host key changed")

sealed interface SshOutput {
    data class Stdout(val line: String) : SshOutput
    data class Stderr(val line: String) : SshOutput
    data class Exit(val status: Int) : SshOutput
}

object SshClient {
    private const val TIMEOUT_MS = 10_000

    /**
     * Connects and authenticates. The host key is checked during key exchange,
     * before any password or key signature is sent.
     */
    suspend fun connect(
        server: SshServer,
        trustedHostKey: String?,
        privateKey: ByteArray?,
        publicKey: String?,
        password: String?,
    ): SshConnection = withContext(Dispatchers.IO) {
        SshCrypto.install()
        val jsch = JSch()
        val pinned = PinnedHostKey(trustedHostKey)
        jsch.hostKeyRepository = pinned
        if (server.useKey) {
            requireNotNull(privateKey) { "No SSH key on this phone yet; generate one first" }
            jsch.addIdentity("nettools", privateKey, publicKey?.toByteArray(), null)
        }
        val session = jsch.getSession(server.user, server.host, server.port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig(
            "PreferredAuthentications",
            if (server.useKey) "publickey" else "password,keyboard-interactive",
        )
        if (!server.useKey) {
            session.setPassword(password.orEmpty().toByteArray())
            session.userInfo = PasswordOnly(password)
        }
        session.serverAliveInterval = 30_000
        try {
            session.connect(TIMEOUT_MS)
        } catch (e: JSchException) {
            val seen = pinned.seen
            when (pinned.result) {
                HostKeyRepository.NOT_INCLUDED -> if (seen != null) throw HostKeyUnknownException(seen)
                HostKeyRepository.CHANGED -> if (seen != null) throw HostKeyChangedException(seen)
            }
            throw e
        }
        SshConnection(session)
    }

    /** Accepts exactly one previously trusted key; records what the server offered. */
    private class PinnedHostKey(private val trusted: String?) : HostKeyRepository {
        var seen: ServerHostKey? = null
        var result = HostKeyRepository.NOT_INCLUDED

        override fun check(host: String, key: ByteArray): Int {
            val offered = ServerHostKey.of(key)
            seen = offered
            result = when (trusted) {
                null -> HostKeyRepository.NOT_INCLUDED
                offered.encoded -> HostKeyRepository.OK
                else -> HostKeyRepository.CHANGED
            }
            return result
        }

        override fun add(hostkey: HostKey, ui: UserInfo?) {}
        override fun remove(host: String, type: String?) {}
        override fun remove(host: String, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID() = "nettools"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    /** Answers keyboard-interactive password prompts; refuses every other question. */
    private class PasswordOnly(private val password: String?) : UserInfo, com.jcraft.jsch.UIKeyboardInteractive {
        override fun getPassphrase(): String? = null
        override fun getPassword(): String? = password
        override fun promptPassword(message: String?) = password != null
        override fun promptPassphrase(message: String?) = false
        override fun promptYesNo(message: String?) = false
        override fun showMessage(message: String?) {}
        override fun promptKeyboardInteractive(
            destination: String?, name: String?, instruction: String?,
            prompt: Array<out String>?, echo: BooleanArray?,
        ): Array<String>? {
            if (password == null || prompt == null || prompt.size != 1) return null
            return arrayOf(password)
        }
    }
}

class SshConnection(private val session: Session) {
    val isConnected: Boolean get() = session.isConnected

    /**
     * Runs one command in its own exec channel, without a terminal, and streams
     * its output. Cancelling the collector closes the channel.
     */
    fun exec(command: String): Flow<SshOutput> = channelFlow {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        channel.setInputStream(null)
        val stdout = channel.inputStream
        val stderr = channel.errStream
        channel.connect(10_000)
        // On cancel, signal the remote command: without a terminal, closing the channel
        // alone leaves it running on the server. Disconnecting also closes the streams,
        // which unblocks the readers.
        val closer = launch {
            try {
                awaitCancellation()
            } finally {
                if (!channel.isClosed) runCatching { channel.sendSignal("TERM") }
                channel.disconnect()
            }
        }
        try {
            val out = launch(Dispatchers.IO) { readLines(stdout) { send(SshOutput.Stdout(it)) } }
            val err = launch(Dispatchers.IO) { readLines(stderr) { send(SshOutput.Stderr(it)) } }
            out.join()
            err.join()
            // The exit status arrives just before the channel closes.
            while (!channel.isClosed && isActive) delay(20)
            send(SshOutput.Exit(channel.exitStatus))
        } finally {
            closer.cancel()
            channel.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    fun close() = session.disconnect()

    private suspend fun readLines(input: InputStream, emit: suspend (String) -> Unit) {
        try {
            input.bufferedReader(Charsets.UTF_8).useLines { lines -> for (line in lines) emit(line) }
        } catch (_: IOException) {
            // Stream closed because the channel went away; nothing more to read.
        }
    }
}
