package com.lifeadmin.autopilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Verifies security/permission properties of the source manifest (SPEC §5.4, §8):
 * network only for optional AI planning (TLS-only config), no sensitive permissions, no
 * USE_EXACT_ALARM, non-exported internal receivers, backups disabled.
 */
class ManifestContractTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))

    private fun elements(tag: String): List<Element> = doc.getElementsByTagName(tag).let { nl -> (0 until nl.length).map { nl.item(it) as Element } }
    private fun Element.attr(name: String) = getAttributeNS(ns, name)

    @Test fun onlyNecessaryPermissionsAreRequested() {
        val permissions = elements("uses-permission").filter { it.getAttributeNS("http://schemas.android.com/tools", "node") != "remove" }.map { it.attr("name") }.toSet()
        val removed = elements("uses-permission").filter { it.getAttributeNS("http://schemas.android.com/tools", "node") == "remove" }.map { it.attr("name") }
        assertEquals(listOf("android.permission.FOREGROUND_SERVICE"), removed)
        assertEquals(
            setOf(
                "android.permission.POST_NOTIFICATIONS", "android.permission.RECEIVE_BOOT_COMPLETED", "android.permission.SCHEDULE_EXACT_ALARM",
                // M2: only for optional, user-triggered AI planning requests.
                "android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
            ),
            permissions,
        )
        listOf("USE_EXACT_ALARM", "READ_CONTACTS", "ACCESS_FINE_LOCATION", "RECORD_AUDIO", "READ_SMS", "READ_CALENDAR", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "USE_FULL_SCREEN_INTENT")
            .forEach { p -> assertFalse(p, permissions.any { it.endsWith(".$p") }) }
    }

    @Test fun internalReceiversAreNotExported() {
        val receivers = elements("receiver").associateBy { it.attr("name") }
        assertEquals("false", receivers.getValue(".receivers.ReminderAlarmReceiver").attr("exported"))
        assertEquals("false", receivers.getValue(".receivers.NotificationActionReceiver").attr("exported"))
    }

    @Test fun systemReceiverListensOnlyToProtectedRecoveryBroadcasts() {
        val r = elements("receiver").single { it.attr("name") == ".receivers.SystemEventReceiver" }
        val actions = r.getElementsByTagName("action").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).attr("name") } }.toSet()
        assertEquals(
            setOf(
                "android.intent.action.BOOT_COMPLETED", "android.intent.action.MY_PACKAGE_REPLACED", "android.intent.action.TIME_SET",
                "android.intent.action.TIMEZONE_CHANGED", "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
            ),
            actions,
        )
    }

    @Test fun cloudBackupIsDisabled() {
        val app = elements("application").single()
        assertEquals("false", app.attr("allowBackup"))
        assertTrue(app.attr("dataExtractionRules").isNotEmpty())
    }

    @Test fun noServicesOrProvidersAreDeclaredByTheApp() {
        assertTrue(elements("service").isEmpty())
        assertTrue(elements("provider").isEmpty())
    }

    /** The ChatGPT share target (proof of concept) accepts only plain-text SEND; the launcher entry is unchanged. */
    @Test fun shareTargetAcceptsOnlyPlainTextAndTheLauncherIsUnchanged() {
        val activities = elements("activity").associateBy { it.attr("name") }
        assertEquals(setOf(".MainActivity", ".ui.share.ShareImportActivity"), activities.keys)
        fun filter(a: Element, tag: String) = a.getElementsByTagName(tag).let { nl -> (0 until nl.length).map { (nl.item(it) as Element) } }
        val main = activities.getValue(".MainActivity")
        assertEquals(listOf("android.intent.action.MAIN"), filter(main, "action").map { it.attr("name") })
        assertEquals(listOf("android.intent.category.LAUNCHER"), filter(main, "category").map { it.attr("name") })
        val share = activities.getValue(".ui.share.ShareImportActivity")
        assertEquals(1, filter(share, "intent-filter").size)
        assertEquals(listOf("android.intent.action.SEND"), filter(share, "action").map { it.attr("name") })
        assertEquals(listOf("android.intent.category.DEFAULT"), filter(share, "category").map { it.attr("name") })
        assertEquals(listOf("text/plain"), filter(share, "data").map { it.attr("mimeType") })
        assertEquals("singleTop", share.attr("launchMode"))
    }

    @Test fun networkIsTlsOnlyWithSystemCertificates() {
        val app = elements("application").single()
        assertEquals("@xml/network_security_config", app.attr("networkSecurityConfig"))
        val cfg = File("src/main/res/xml/network_security_config.xml").readText()
        assertTrue(cfg.contains("cleartextTrafficPermitted=\"false\""))
        assertTrue(cfg.contains("<certificates src=\"system\" />"))
        assertFalse(cfg.contains("src=\"user\""))
    }
}
