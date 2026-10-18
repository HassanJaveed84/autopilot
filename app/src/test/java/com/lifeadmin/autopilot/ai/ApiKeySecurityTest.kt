package com.lifeadmin.autopilot.ai

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lifeadmin.autopilot.ai.security.ApiKeyStore
import com.lifeadmin.autopilot.ai.security.EncryptedApiKeyStore
import com.lifeadmin.autopilot.ai.security.SecretCipher
import com.lifeadmin.autopilot.data.repository.BackupRepository
import com.lifeadmin.autopilot.data.repository.InMemorySettingsRepository
import com.lifeadmin.autopilot.data.repository.NewTask
import com.lifeadmin.autopilot.data.repository.TaskRepository
import com.lifeadmin.autopilot.domain.model.AppSettings
import com.lifeadmin.autopilot.domain.model.DeliveryEnvironment
import com.lifeadmin.autopilot.reminders.Diagnostics
import com.lifeadmin.autopilot.reminders.ReminderEngine
import com.lifeadmin.autopilot.reminders.RoomDiagnosticsLog
import com.lifeadmin.autopilot.testing.CHICAGO
import com.lifeadmin.autopilot.testing.FakeAlarmDriver
import com.lifeadmin.autopilot.testing.FakeClock
import com.lifeadmin.autopilot.testing.FakeEnvironmentProbe
import com.lifeadmin.autopilot.testing.FakeNotificationPublisher
import com.lifeadmin.autopilot.testing.FakeZoneProvider
import com.lifeadmin.autopilot.testing.SequentialIds
import com.lifeadmin.autopilot.testing.at
import com.lifeadmin.autopilot.testing.inMemoryDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The BYOK key is stored only encrypted and never leaks into exports, diagnostics or settings. */
@RunWith(AndroidJUnit4::class)
class ApiKeySecurityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /** Stand-in for the Android Keystore (unavailable under Robolectric); the store logic is real. */
    private class XorCipher : SecretCipher {
        var destroyed = false
        override fun encrypt(plain: ByteArray) = byteArrayOf(9, 9, 9) + plain.map { (it.toInt() xor 0x5A).toByte() }
        override fun decrypt(blob: ByteArray) = blob.drop(3).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
        override fun destroyKey() { destroyed = true }
    }

    private val key = "test-key-ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"

    @Test fun keyIsStoredEncryptedAndRoundTrips() {
        val cipher = XorCipher()
        val store = EncryptedApiKeyStore(context, cipher)
        store.save("  $key \n")
        assertTrue(store.hasKey())
        assertEquals(key, store.get())
        assertEquals("2345", store.hint())
        val prefsFile = File(context.applicationInfo.dataDir, "shared_prefs/${EncryptedApiKeyStore.FILE}.xml")
        if (prefsFile.exists()) assertFalse("plaintext key must not be on disk", prefsFile.readText().contains(key))
        val raw = context.getSharedPreferences(EncryptedApiKeyStore.FILE, Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(raw.contains(key))
    }

    @Test fun deleteRemovesTheBlobAndTheKeystoreKey() {
        val cipher = XorCipher()
        val store = EncryptedApiKeyStore(context, cipher)
        store.save(key)
        store.delete()
        assertFalse(store.hasKey())
        assertNull(store.get())
        assertTrue(cipher.destroyed)
    }

    @Test fun unreadableBlobIsDiscardedInsteadOfCrashing() {
        val broken = object : SecretCipher {
            override fun encrypt(plain: ByteArray) = plain
            override fun decrypt(blob: ByteArray): ByteArray = throw javax.crypto.AEADBadTagException("key lost")
            override fun destroyKey() = Unit
        }
        val store = EncryptedApiKeyStore(context, broken)
        store.save(key)
        assertNull(store.get())
        assertFalse(store.hasKey())
    }

    @Test fun junkInputIsRejected() {
        assertNull(ApiKeyStore.normalize("hello"))
        assertNull(ApiKeyStore.normalize("sk-ant-<script>alert(1)</script>-xxxxxxxxxxxx"))
        assertEquals(key, ApiKeyStore.normalize(" $key "))
    }

    @Test fun keyNeverAppearsInExportDiagnosticsOrSettings() = runBlocking {
        val store = EncryptedApiKeyStore(context, XorCipher())
        store.save(key)
        val db = inMemoryDatabase()
        val clock = FakeClock(at(CHICAGO, 2026, 10, 14, 10, 0))
        val ids = SequentialIds("sec")
        val settings = InMemorySettingsRepository(AppSettings(aiEnabled = true, aiConsentAt = clock.now(), aiModel = AiConfig.DEFAULT_MODEL))
        val log = RoomDiagnosticsLog(db, clock, ids)
        val alarms = FakeAlarmDriver()
        val notifications = FakeNotificationPublisher()
        val engine = ReminderEngine(db, settings, alarms, notifications, clock, FakeZoneProvider(CHICAGO), ids, log, FakeEnvironmentProbe(DeliveryEnvironment()))
        val repo = TaskRepository(db, clock, ids, { CHICAGO }, engine, log)
        repo.createTask(NewTask("A task"))

        val export = BackupRepository(db, clock, ids, "test").exportJson()
        assertTrue(export.contains("A task"))
        assertFalse("backup export", export.contains(key))

        val diag = Diagnostics(db, settings, alarms, notifications, clock, FakeZoneProvider(CHICAGO), "test", 34).report()
        assertFalse("diagnostics", diag.contains(key))
        assertFalse("delivery ledger export", log.exportText(CHICAGO).contains(key))
        assertFalse("settings", settings.current().toString().contains(key))
        db.close()
    }

    @Test fun noRealLookingKeyIsCheckedIntoTheRepository() {
        val pattern = Regex("""sk-ant-[A-Za-z0-9_\-]{20,}""")
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
        val offenders = root.walkTopDown()
            .onEnter { d -> d.name !in setOf("build", ".gradle", ".git", ".idea", ".kotlin") }
            .filter { it.isFile && it.length() < 2_000_000 && it.extension in setOf("kt", "kts", "xml", "md", "json", "properties", "yml", "yaml", "txt", "pro") }
            .filter { pattern.containsMatchIn(it.readText()) }
            .map { it.relativeTo(root).path }
            .toList()
        assertEquals("files containing something shaped like an Anthropic key", emptyList<String>(), offenders)
    }
}
