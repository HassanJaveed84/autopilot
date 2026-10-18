package com.lifeadmin.autopilot

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Provider boundary (M2B): only the Anthropic adapter may depend on Anthropic SDK types, so
 * tasks, reminders, UI and the planning domain stay provider-neutral (an on-device or proxy
 * provider can replace it).
 */
class ArchitectureBoundaryTest {
    @Test fun onlyTheAnthropicAdapterImportsTheSdk() {
        val root = File("src/main/java/com/lifeadmin/autopilot")
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readLines().any { it.trimStart().startsWith("import com.anthropic") } }
            .map { it.relativeTo(root).path }
            .filterNot { it.startsWith("ai/anthropic/") }
            .toList()
        assertEquals(emptyList<String>(), offenders)
    }

    @Test fun coreTaskAndReminderCodeDoesNotDependOnAiPackages() {
        val core = listOf("domain/scheduling", "reminders", "notifications", "receivers", "data/local")
        val root = File("src/main/java/com/lifeadmin/autopilot")
        val offenders = core.flatMap { dir -> File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
            .filter { f -> f.readLines().any { it.trimStart().startsWith("import com.lifeadmin.autopilot.ai") } }
            .map { it.relativeTo(root).path }
        assertEquals(emptyList<String>(), offenders)
    }
}
