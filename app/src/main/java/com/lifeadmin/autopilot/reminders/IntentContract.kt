package com.lifeadmin.autopilot.reminders

import android.net.Uri

/**
 * Internal intent contract. PendingIntent identity comes from the data URI (unique per
 * occurrence / task), so distinct active occurrences never share a PendingIntent.
 * Everything parsed from an intent is validated and then re-checked against Room.
 */
object IntentContract {
    const val ACTION_FIRE = "com.lifeadmin.autopilot.action.REMINDER_FIRE"
    const val ACTION_TEST_FIRE = "com.lifeadmin.autopilot.action.TEST_REMINDER_FIRE"
    const val ACTION_DONE = "com.lifeadmin.autopilot.action.DONE"
    const val ACTION_SNOOZE = "com.lifeadmin.autopilot.action.SNOOZE"
    const val EXTRA_GENERATION = "generation"
    const val EXTRA_TASK_ID = "com.lifeadmin.autopilot.extra.TASK_ID"
    private const val SCHEME = "lifeadmin"

    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    fun isValidId(id: String?): Boolean = id != null && ID.matches(id)

    fun occurrenceUri(occurrenceId: String): Uri = Uri.Builder().scheme(SCHEME).authority("occurrence").appendPath(occurrenceId).build()
    fun taskUri(taskId: String, action: String): Uri = Uri.Builder().scheme(SCHEME).authority("task").appendPath(taskId).appendPath(action).build()

    fun testUri(testId: String): Uri = Uri.Builder().scheme(SCHEME).authority("test").appendPath(testId).build()

    fun testIdFrom(uri: Uri?): String? =
        uri?.takeIf { it.scheme == SCHEME && it.authority == "test" }?.pathSegments?.singleOrNull()?.takeIf(::isValidId)

    fun occurrenceIdFrom(uri: Uri?): String? =
        uri?.takeIf { it.scheme == SCHEME && it.authority == "occurrence" }?.pathSegments?.singleOrNull()?.takeIf(::isValidId)

    fun taskIdFrom(uri: Uri?): String? =
        uri?.takeIf { it.scheme == SCHEME && it.authority == "task" }?.pathSegments?.firstOrNull()?.takeIf(::isValidId)
}
