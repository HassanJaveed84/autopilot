package com.lifeadmin.autopilot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Verifies security/permission properties of the source manifest (SPEC §5.4, §8):
 * no network permission (a TLS-only config stays as defence in depth), no sensitive permissions, no
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
            ),
            permissions,
        )
        // Built-in AI is retired: the app makes no network requests of its own.
        listOf("INTERNET", "ACCESS_NETWORK_STATE", "USE_EXACT_ALARM", "READ_CONTACTS", "ACCESS_FINE_LOCATION", "RECORD_AUDIO", "READ_SMS", "READ_CALENDAR", "REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "USE_FULL_SCREEN_INTENT")
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

    /** No share target: the only entry points are the launcher and the two App Link routes. */
    @Test fun noShareTargetAndTheLauncherIsUnchanged() {
        val activities = elements("activity").associateBy { it.attr("name") }
        assertEquals(setOf(".MainActivity", ".ui.applink.AppLinkTestActivity", ".ui.handoff.HandoffImportActivity"), activities.keys)
        fun filter(a: Element, tag: String) = a.getElementsByTagName(tag).let { nl -> (0 until nl.length).map { (nl.item(it) as Element) } }
        val main = activities.getValue(".MainActivity")
        assertEquals(listOf("android.intent.action.MAIN"), filter(main, "action").map { it.attr("name") })
        assertEquals(listOf("android.intent.category.LAUNCHER"), filter(main, "category").map { it.attr("name") })
        val actions = activities.values.flatMap { filter(it, "action") }.map { it.attr("name") }
        assertTrue(actions.none { it == "android.intent.action.SEND" || it == "android.intent.action.SEND_MULTIPLE" })
    }

    /** The App Link spike claims one https route on one host, verified, and nothing broader. */
    @Test fun appLinkFilterClaimsOnlyTheVerifiedTestRoute() = assertAppLinkRoute(".ui.applink.AppLinkTestActivity", "/autopilot/test")

    /** The ChatGPT round trip's import route: same host and shape, its own exact path, its own task. */
    @Test fun importFilterClaimsOnlyTheImportRoute() = assertAppLinkRoute(".ui.handoff.HandoffImportActivity", "/autopilot/import")

    /** "Discuss in ChatGPT" may see exactly one other package: the ChatGPT app. */
    @Test fun onlyTheChatGptPackageIsQueried() {
        val queries = elements("queries").single()
        val packages = queries.getElementsByTagName("package").let { nl -> (0 until nl.length).map { (nl.item(it) as Element).attr("name") } }
        assertEquals(listOf("com.openai.chatgpt"), packages)
        assertEquals(0, queries.getElementsByTagName("intent").length)
    }

    private fun assertAppLinkRoute(name: String, path: String) {
        val activity = elements("activity").single { it.attr("name") == name }
        fun Element.all(tag: String) = getElementsByTagName(tag).let { nl -> (0 until nl.length).map { (nl.item(it) as Element) } }
        val filter = activity.all("intent-filter").single()
        assertEquals("true", filter.attr("autoVerify"))
        assertEquals(listOf("android.intent.action.VIEW"), filter.all("action").map { it.attr("name") })
        assertEquals(setOf("android.intent.category.DEFAULT", "android.intent.category.BROWSABLE"), filter.all("category").map { it.attr("name") }.toSet())
        val data = filter.all("data").single()
        assertEquals("https", data.attr("scheme"))
        assertEquals("\${appLinkHost}", data.attr("host"))
        assertEquals(path, data.attr("path"))
        listOf("pathPrefix", "pathPattern", "pathAdvancedPattern", "port").forEach { assertTrue(it, data.attr(it).isEmpty()) }
        assertEquals("singleTop", activity.attr("launchMode"))
        assertEquals("persistNever", activity.attr("persistableMode"))
        assertEquals("", activity.attr("taskAffinity"))
        assertTrue(activity.hasAttributeNS(ns, "taskAffinity"))
    }

    /** The site file the spike publishes names only the release package, with well-formed SHA-256 fingerprints. */
    @Test fun assetLinksNameOnlyTheReleasePackage() {
        val statements = Json.parseToJsonElement(File("../applink-site/.well-known/assetlinks.json").readText()).jsonArray.map { it.jsonObject }
        val statement = statements.single()
        assertEquals(listOf("delegate_permission/common.handle_all_urls"), statement.getValue("relation").jsonArray.map { it.jsonPrimitive.content })
        val target = statement.getValue("target").jsonObject
        assertEquals("android_app", target.getValue("namespace").jsonPrimitive.content)
        assertEquals(BuildConfig.APPLICATION_ID.removeSuffix(".debug"), target.getValue("package_name").jsonPrimitive.content)
        val prints = target.getValue("sha256_cert_fingerprints").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(prints.isNotEmpty() && prints.all { Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}").matches(it) })
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
