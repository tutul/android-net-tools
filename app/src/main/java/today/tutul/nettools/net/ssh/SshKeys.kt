package today.tutul.nettools.net.ssh

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.jcraft.jsch.JSch
import com.jcraft.jsch.KeyPair
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SshCrypto {
    @Volatile private var installed = false

    /**
     * JSch prefers the Java 11/15 XDH and EdDSA APIs, which Android lacks, for
     * curve25519 key exchange and ed25519 keys. Point it at its Bouncy Castle versions.
     */
    fun install() {
        if (installed) return
        JSch.setConfig("xdh", "com.jcraft.jsch.bc.XDH")
        JSch.setConfig("keypairgen.eddsa", "com.jcraft.jsch.bc.KeyPairGenEdDSA")
        JSch.setConfig("ssh-ed25519", "com.jcraft.jsch.bc.SignatureEd25519")
        JSch.setConfig("ssh-ed448", "com.jcraft.jsch.bc.SignatureEd448")
        installed = true
    }
}

/**
 * This phone's SSH identity: one ed25519 key pair. The private key is stored
 * encrypted with an AES key that lives in the Android Keystore and never leaves it.
 */
class SshKeys(context: Context) {
    private val privateFile = File(context.filesDir, "ssh_id_ed25519.enc")
    private val publicFile = File(context.filesDir, "ssh_id_ed25519.pub")

    /** OpenSSH one-line public key, ready for authorized_keys; null until generated. */
    fun publicKey(): String? = publicFile.takeIf { it.exists() }?.readText()

    fun privateKey(): ByteArray? = privateFile.takeIf { it.exists() }?.let { decrypt(it.readBytes()) }

    fun generate(): String {
        SshCrypto.install()
        val pair = KeyPair.genKeyPair(JSch(), KeyPair.ED25519)
        try {
            // OpenSSH's own format; the legacy PEM writer doesn't support ed25519. No
            // passphrase here because the whole file is Keystore-encrypted below.
            val private = ByteArrayOutputStream().also { pair.writeOpenSSHv1PrivateKey(it, null) }.toByteArray()
            val public = ByteArrayOutputStream()
                .also { pair.writePublicKey(it, "nettools@${Build.MODEL.replace(' ', '-')}") }
                .toString(Charsets.UTF_8.name()).trim()
            privateFile.writeBytes(encrypt(private))
            private.fill(0)
            publicFile.writeText(public)
            return public
        } finally {
            pair.dispose()
        }
    }

    private fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return cipher.iv + cipher.doFinal(plain)
    }

    private fun decrypt(stored: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, stored, 0, IV_SIZE))
        return cipher.doFinal(stored, IV_SIZE, stored.size - IV_SIZE)
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val spec = KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            .apply { init(spec) }
            .generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "nettools_ssh_identity"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
    }
}
