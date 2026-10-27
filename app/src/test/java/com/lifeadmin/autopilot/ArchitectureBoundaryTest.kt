package com.lifeadmin.autopilot

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Built-in AI is retired (Autopilot manages commitments; ChatGPT handles intelligence): no code may
 * depend on an AI provider SDK or open network connections, and tasks, reminders and persistence
 * never depend on the `ai` package, which only describes research saved by earlier versions.
 */
class ArchitectureBoundaryTest {
    private val root = File("src/main/java/com/lifeadmin/autopilot")
    private fun sources() = root.walkTopDown().filter { it.isFile && it.extension == "kt" }

    @Test fun noCodeImportsAnAiProviderSdk() {
        val offenders = sources()
            .filter { f -> f.readLines().any { it.trimStart().let { l -> l.startsWith("import com.anthropic") || l.startsWith("import com.openai") } } }
            .map { it.relativeTo(root).path }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun noCodeOpensNetworkConnections() {
        val markers = listOf("java.net.HttpURLConnection", "java.net.URL(", "okhttp3", "openConnection(")
        val offenders = sources().filter { f -> f.readText().let { t -> markers.any { it in t } } }.map { it.relativeTo(root).path }.toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun coreTaskAndReminderCodeDoesNotDependOnAiPackages() {
        val core = listOf("domain/scheduling", "reminders", "notifications", "receivers", "data/local")
        val offenders = core.flatMap { dir -> File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { f -> f.readLines().any { it.trimStart().startsWith("import com.lifeadmin.autopilot.ai") } }
            .map { it.relativeTo(root).path }
        assertEquals(emptyList<String>(), offenders)
    }
}
