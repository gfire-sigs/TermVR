package dev.gfire.termvr.core.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.KeyStore
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores and retrieves proxy credentials isolated from serialized profile JSON.
 */
interface SecretStore {
    fun putSecret(alias: String, secret: CharArray)
    fun getSecret(alias: String): CharArray?
    fun deleteSecret(alias: String)
    fun containsAlias(alias: String): Boolean
}

/**
 * Encrypts proxy secrets with AES-256-GCM keys held inside Android Keystore.
 */
class AndroidKeystoreSecretStore(
    private val secretsDir: File,
    private val keyStoreProvider: String = DEFAULT_KEYSTORE_PROVIDER,
) : SecretStore {
    init {
        if (!secretsDir.exists()) {
            secretsDir.mkdirs()
        }
        setOwnerOnlyDirectoryPermissions(secretsDir)
    }

    override fun putSecret(alias: String, secret: CharArray) {
        validateAlias(alias)
        val secretKey = getOrCreateAesKey(keystoreAliasFor(alias))
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        val iv = cipher.iv
        require(iv.size == GCM_IV_LENGTH_BYTES) {
            "Unexpected GCM IV length: ${iv.size}"
        }

        val charBuffer = CharBuffer.wrap(secret)
        val encoded = StandardCharsets.UTF_8.encode(charBuffer)
        val plainBytes = ByteArray(encoded.remaining())
        encoded.get(plainBytes)
        try {
            val cipherText = cipher.doFinal(plainBytes)
            val targetFile = secretFileFor(alias)
            val tempFile = File(secretsDir, "${targetFile.name}.tmp")
            val payload = ByteBuffer.allocate(1 + iv.size + cipherText.size)
                .put(iv.size.toByte())
                .put(iv)
                .put(cipherText)
                .array()
            tempFile.writeBytes(payload)
            setOwnerOnlyFilePermissions(tempFile)
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
                setOwnerOnlyFilePermissions(targetFile)
            }
        } finally {
            Arrays.fill(plainBytes, 0.toByte())
            if (encoded.hasArray()) {
                Arrays.fill(encoded.array(), 0.toByte())
            }
        }
    }

    override fun getSecret(alias: String): CharArray? {
        validateAlias(alias)
        val targetFile = secretFileFor(alias)
        if (!targetFile.exists()) return null

        val keyStore = loadKeyStore()
        val secretKey = keyStore.getKey(keystoreAliasFor(alias), null) as? SecretKey ?: return null
        val payload = targetFile.readBytes()
        if (payload.size < 2) return null

        val ivLen = payload[0].toInt() and 0xFF
        if (ivLen != GCM_IV_LENGTH_BYTES || payload.size <= 1 + ivLen) return null

        val iv = payload.copyOfRange(1, 1 + ivLen)
        val cipherText = payload.copyOfRange(1 + ivLen, payload.size)
        val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(GCM_TAG_BITS, iv))

        val plainBytes = cipher.doFinal(cipherText)
        val byteBuffer = ByteBuffer.wrap(plainBytes)
        val decoded = StandardCharsets.UTF_8.decode(byteBuffer)
        val chars = CharArray(decoded.remaining())
        decoded.get(chars)
        Arrays.fill(plainBytes, 0.toByte())
        if (decoded.hasArray()) {
            Arrays.fill(decoded.array(), '\u0000')
        }
        return chars
    }

    override fun deleteSecret(alias: String) {
        validateAlias(alias)
        val targetFile = secretFileFor(alias)
        if (targetFile.exists()) {
            targetFile.delete()
        }
        val keyStore = loadKeyStore()
        val ksAlias = keystoreAliasFor(alias)
        if (keyStore.containsAlias(ksAlias)) {
            keyStore.deleteEntry(ksAlias)
        }
    }

    override fun containsAlias(alias: String): Boolean {
        validateAlias(alias)
        return secretFileFor(alias).exists()
    }

    private fun secretFileFor(alias: String): File = File(secretsDir, "$alias.enc")

    private fun keystoreAliasFor(alias: String): String = "$KEY_ALIAS_PREFIX$alias"

    private fun loadKeyStore(): KeyStore =
        KeyStore.getInstance(keyStoreProvider).apply { load(null) }

    private fun getOrCreateAesKey(ksAlias: String): SecretKey {
        val keyStore = loadKeyStore()
        val existing = keyStore.getKey(ksAlias, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, keyStoreProvider)
        val spec = KeyGenParameterSpec.Builder(
            ksAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(AES_KEY_BITS)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    companion object {
        private const val DEFAULT_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
        private const val KEY_ALIAS_PREFIX = "termvr_proxy_"
        private const val AES_KEY_BITS = 256
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_BITS = 128

        private val ALIAS_REGEX = Regex("^[a-zA-Z0-9._-]{1,128}$")

        fun validateAlias(alias: String) {
            require(ALIAS_REGEX.matches(alias) && alias != "." && alias != "..") {
                "Secret alias must match [a-zA-Z0-9._-]{1,128} without '.' or '..'"
            }
        }
    }
}

/**
 * In-memory SecretStore implementation for host JVM unit tests.
 */
class InMemorySecretStore : SecretStore {
    private val entries = LinkedHashMap<String, CharArray>()

    override fun putSecret(alias: String, secret: CharArray) {
        AndroidKeystoreSecretStore.validateAlias(alias)
        entries[alias]?.let { Arrays.fill(it, '\u0000') }
        entries[alias] = secret.copyOf()
    }

    override fun getSecret(alias: String): CharArray? {
        AndroidKeystoreSecretStore.validateAlias(alias)
        return entries[alias]?.copyOf()
    }

    override fun deleteSecret(alias: String) {
        AndroidKeystoreSecretStore.validateAlias(alias)
        entries.remove(alias)?.let { Arrays.fill(it, '\u0000') }
    }

    override fun containsAlias(alias: String): Boolean {
        AndroidKeystoreSecretStore.validateAlias(alias)
        return entries.containsKey(alias)
    }
}

internal fun setOwnerOnlyFilePermissions(file: File) {
    try {
        Files.setPosixFilePermissions(
            file.toPath(),
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
        )
    } catch (_: UnsupportedOperationException) {
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
        file.setExecutable(false, false)
    }
}

internal fun setOwnerOnlyDirectoryPermissions(dir: File) {
    try {
        Files.setPosixFilePermissions(
            dir.toPath(),
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
    } catch (_: UnsupportedOperationException) {
        dir.setReadable(false, false)
        dir.setReadable(true, true)
        dir.setWritable(false, false)
        dir.setWritable(true, true)
        dir.setExecutable(false, false)
        dir.setExecutable(true, true)
    }
}
