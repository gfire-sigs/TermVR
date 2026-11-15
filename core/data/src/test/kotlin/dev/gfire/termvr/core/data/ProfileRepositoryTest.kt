package dev.gfire.termvr.core.data

import androidx.datastore.core.CorruptionException
import dev.gfire.termvr.core.model.ColorPalette
import dev.gfire.termvr.core.model.FontConfig
import dev.gfire.termvr.core.model.GlobalAppSettings
import dev.gfire.termvr.core.model.KeyBarConfig
import dev.gfire.termvr.core.model.SandboxProfile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ProfileRepositoryTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `INV-D1 DataStore persists profiles keybar font settings and SAF grants with schemaVersion check`() = runTest {
        // Property: AppPersistenceSerializer round-trips all state and rejects unsupported schemaVersion.
        // Seam: ProfileRepository + AppPersistenceSerializer.
        // Oracle: Design 0001 Section 8.4 and Section 11 persistence contracts.
        // Fault detected: dropped profile fields, stale DataStore cache, or silent acceptance of schemaVersion=99.
        val dataStore = ProfileRepository.createDataStore(tempDir, backgroundScope)
        val repo = ProfileRepository(dataStore, tempDir, InMemorySecretStore())

        val profile = SandboxProfile(
            id = "alpine-dev",
            displayName = "Alpine Dev",
            imageRef = "docker.io/library/alpine:3.21",
            memoryMiB = 2048,
        )
        repo.upsertProfile(profile)
        repo.updateKeyBarConfig(KeyBarConfig(hapticFeedback = false))
        repo.updateAppSettings(
            GlobalAppSettings(
                pinKeyBarWithHardwareKeyboard = true,
                passAllShortcutsToTerminal = true,
                selectedPaletteId = ColorPalette.HIGH_CONTRAST_DARK.id,
            ),
        )
        repo.recordSafGrant(
            SafGrantRecord(
                uriString = "content://dev.gfire.termvr.documents/tree/alpine-dev",
                displayName = "alpine-dev workspace",
                profileId = "alpine-dev",
            ),
        )

        val loadedProfile = repo.getProfile("alpine-dev")
        assertEquals(profile, loadedProfile)
        assertEquals(false, repo.observeKeyBarConfig().first().hapticFeedback)
        assertTrue(repo.observeAppSettings().first().pinKeyBarWithHardwareKeyboard)
        assertEquals(1, repo.observeSafGrants().first().size)

        val serialized = ByteArrayOutputStream().also { out ->
            AppPersistenceSerializer.writeTo(repo.state.first(), out)
        }.toByteArray()
        val decoded = AppPersistenceSerializer.readFrom(ByteArrayInputStream(serialized))
        assertEquals(profile, decoded.profiles.single())

        var threwCorruption = false
        try {
            AppPersistenceSerializer.readFrom(ByteArrayInputStream("""{"schemaVersion":99}""".toByteArray()))
        } catch (_: CorruptionException) {
            threwCorruption = true
        }
        assertTrue(threwCorruption, "Expected CorruptionException for schemaVersion=99")
    }

    @Test
    fun `INV-D2 WireGuard config management enforces naming section validation and 0600 permissions`() = runTest {
        // Property: WireGuard configs save to <filesDir>/wg/<name>.conf with 0600 mode and reject traversal or malformed INI.
        // Seam: ProfileRepository.saveWireGuardConfig, readWireGuardConfig, listWireGuardConfigNames, deleteWireGuardConfig.
        // Oracle: Design 0001 Section 11.1 and 16.3 WireGuard storage contract.
        // Fault detected: path traversal outside wg/, world-readable private key file, or missing [Peer] section.
        val repo = ProfileRepository(
            ProfileRepository.createDataStore(tempDir, backgroundScope),
            tempDir,
            InMemorySecretStore(),
        )
        val wgText = """
            [Interface]
            PrivateKey = cGVlcjEyMzQ1Njc4OTAxMjM0NTY3ODkwMTIzNDU2Nzg5MDE=
            Address = 10.7.0.2/32

            [Peer]
            PublicKey = cHVibGljMTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=
            Endpoint = 198.51.100.10:51820
        """.trimIndent()

        val savedFile = repo.saveWireGuardConfig("home-vpn", wgText)
        assertEquals(File(tempDir, "wg/home-vpn.conf").canonicalPath, savedFile.canonicalPath)
        assertEquals(wgText, repo.readWireGuardConfig("home-vpn"))
        assertEquals(listOf("home-vpn"), repo.listWireGuardConfigNames())

        val perms = Files.getPosixFilePermissions(savedFile.toPath())
        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            perms,
        )

        var threwTraversal = false
        try {
            repo.saveWireGuardConfig("../escape", wgText)
        } catch (_: IllegalArgumentException) {
            threwTraversal = true
        }
        assertTrue(threwTraversal)

        var threwMalformed = false
        try {
            repo.saveWireGuardConfig("bad-wg", "[Interface]\nAddress = 10.0.0.2/32\n")
        } catch (_: IllegalArgumentException) {
            threwMalformed = true
        }
        assertTrue(threwMalformed)

        assertTrue(repo.deleteWireGuardConfig("home-vpn"))
        assertNull(repo.readWireGuardConfig("home-vpn"))
    }

    @Test
    fun `INV-D3 Custom font import verifies sfnt header computes SHA-256 and rejects digest mismatch`() = runTest {
        // Property: importCustomFont writes <filesDir>/fonts/<sha256>.ttf, verifies expectedSha256, and rejects bad magic.
        // Seam: ProfileRepository.importCustomFont and FontConfig state.
        // Oracle: Design 0001 Section 11.2 and Section 13.3 custom font SHA-256 contract.
        // Fault detected: accepting arbitrary non-font bytes, wrong SHA-256 filename, or ignoring expectedSha256 mismatch.
        val repo = ProfileRepository(
            ProfileRepository.createDataStore(tempDir, backgroundScope),
            tempDir,
            InMemorySecretStore(),
        )

        val fakeTtfBytes = byteArrayOf(0x00, 0x01, 0x00, 0x00) + ByteArray(64) { idx -> (idx and 0xFF).toByte() }
        val expectedSha = MessageDigest.getInstance("SHA-256")
            .digest(fakeTtfBytes)
            .joinToString("") { "%02x".format(it) }

        val updatedFont: FontConfig = repo.importCustomFont(
            inputStream = ByteArrayInputStream(fakeTtfBytes),
            expectedSha256 = expectedSha,
        )
        assertEquals(expectedSha, updatedFont.customFontSha256)
        assertEquals(File(tempDir, "fonts/$expectedSha.ttf").absolutePath, updatedFont.customFontPath)
        assertTrue(File(updatedFont.customFontPath!!).exists())

        var threwDigestMismatch = false
        try {
            repo.importCustomFont(
                inputStream = ByteArrayInputStream(fakeTtfBytes),
                expectedSha256 = "0".repeat(64),
            )
        } catch (_: IllegalArgumentException) {
            threwDigestMismatch = true
        }
        assertTrue(threwDigestMismatch)

        var threwBadMagic = false
        try {
            repo.importCustomFont(ByteArrayInputStream("NOT_A_FONT_FILE".toByteArray()))
        } catch (_: IllegalArgumentException) {
            threwBadMagic = true
        }
        assertTrue(threwBadMagic)
    }

    @Test
    fun `INV-D4 SecretStore stores retrieves and deletes proxy secrets by alias`() {
        // Property: Secrets stored by credentialKeyAlias are retrievable, deletable, and isolated from invalid aliases.
        // Seam: SecretStore interface via InMemorySecretStore and AndroidKeystoreSecretStore.validateAlias.
        // Oracle: Design 0001 Section 8.1 and 11.1 credential isolation contract.
        // Fault detected: alias traversal, stale secret after deletion, or corrupted UTF-8 password bytes.
        val store = InMemorySecretStore()
        val secret = "s3cr3t-pass!".toCharArray()
        store.putSecret("orbot_proxy", secret)
        assertTrue(store.containsAlias("orbot_proxy"))
        assertArrayEquals(secret, store.getSecret("orbot_proxy"))

        store.deleteSecret("orbot_proxy")
        assertNull(store.getSecret("orbot_proxy"))

        assertThrows(IllegalArgumentException::class.java) {
            store.putSecret("../bad", "x".toCharArray())
        }
    }
}
