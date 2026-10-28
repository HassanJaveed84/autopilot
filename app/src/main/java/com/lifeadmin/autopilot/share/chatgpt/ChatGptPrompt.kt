package com.lifeadmin.autopilot.share.chatgpt

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.model.TaskStep
import com.lifeadmin.autopilot.share.applink.ImportLinkParser
import com.lifeadmin.autopilot.share.applink.TaskRef
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the user chose to share in "Discuss in ChatGPT". The title and the reference are always included. */
data class DiscussChoices(val steps: Boolean = true, val deadline: Boolean = true, val notes: Boolean = false)

/**
 * The message "Discuss in ChatGPT" prefills (docs/CHATGPT_ROUND_TRIP_UX.md §2). It opens a normal
 * conversation; the return-link instructions sit after a divider and apply only when the user asks
 * to send the result to Autopilot. Pure text: no API call, nothing stored.
 */
object ChatGptPrompt {
    private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM uuuu", Locale.ENGLISH)
    private val dateTimeFmt = DateTimeFormatter.ofPattern("EEE d MMM uuuu, HH:mm", Locale.ENGLISH)

    fun deadlineText(task: Task, zone: ZoneId): String? = when (task.deadlineType) {
        DeadlineType.NONE -> null
        DeadlineType.DATE_ONLY -> task.deadlineLocalDate?.format(dateFmt)
        DeadlineType.EXACT -> task.deadlineInstant?.atZone(zone)?.format(dateTimeFmt)
    }

    fun build(task: Task, steps: List<TaskStep>, choices: DiscussChoices, host: String, zone: ZoneId): String = buildString {
        append("I'd like your help with something on my to-do list: “").append(task.title.trim()).append("”. ")
        append("I keep track of it in my Autopilot app. Let's talk it through first: research where it helps, ask me questions when something matters, ")
        append("and tell me what you honestly think. Please don't jump straight to a checklist.\n")

        val facts = ArrayList<String>()
        if (choices.deadline) deadlineText(task, zone)?.let { facts += "Deadline: $it" }
        if (choices.steps) {
            val ordered = steps.sortedBy { it.sortOrder }
            ordered.filter { it.isDone }.takeIf { it.isNotEmpty() }?.let { done -> facts += "Done: " + done.joinToString("; ") { it.title.trim() } }
            ordered.filterNot { it.isDone }.takeIf { it.isNotEmpty() }?.let { open -> facts += "Still to do: " + open.joinToString("; ") { it.title.trim() } }
        }
        if (facts.isNotEmpty()) {
            append("\nWhat I have so far:\n")
            facts.forEach { append("- ").append(it).append('\n') }
        }
        val notes = task.notes?.trim()?.takeIf { choices.notes && it.isNotEmpty() }
        if (notes != null) append("\nMy notes:\n").append(notes).append('\n')

        append("\n---\n")
        append("For later, only when I ask you to send this to Autopilot: reply with one Markdown link labelled “Open in Autopilot”. ")
        append("Start its URL with exactly\n")
        append(ImportLinkParser.prefix(host, TaskRef.of(task.id), task.title)).append('\n')
        append("then add one field per item: &d= a decision I actually made, &s= a next step, &n= a useful note (a budget, a model, a contact, a date). ")
        append("Use only what we agreed; never say something is done unless I told you. Leave out steps already on my list, unless you add details to one.\n")
        append("Right after a step you may add its details: &sd= one short paragraph on how to do it (up to 4), and &sl= a link written as a short label, a space, then the full web address (up to 5, the most useful first). ")
        append("Only use web addresses you actually found or I gave you, without tracking parameters. To add details to a step already on my list, repeat its exact name in &s= and put the &sd= and &sl= right after it.\n")
        append("Finish the link with exactly ").append(ImportLinkParser.END_MARKER).append('\n')
        append("Encoding: percent-encode each value as UTF-8. Keep A–Z a–z 0–9 - . _ ~ and write everything else as %XX: ")
        append("space %20 (never +), & %26, = %3D, + %2B, # %23, % %25, ' %27, , %2C, \$ %24, é %C3%A9. ")
        append("That includes web addresses: their : / ? & = # % become %3A %2F %3F %26 %3D %23 %25.\n")
        append("Limits: up to 5 d, 10 s and 5 n, each under 200 characters on one line; per step up to 4 sd of 300 characters and 5 sl; the whole link under 4,000 characters. If something doesn't fit, tell me what you left out.")
    }
}

/**
 * Hands the prepared message to ChatGPT with a plain `ACTION_SEND` `text/plain` intent, the same
 * mechanism as sharing from any app. ChatGPT prefills its composer and the user presses send; nothing
 * is submitted for them. When ChatGPT isn't installed, the system chooser opens instead.
 */
object ChatGptShare {
    const val PACKAGE = "com.openai.chatgpt"

    fun sendIntent(text: String): Intent = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    fun directIntent(text: String): Intent = sendIntent(text).setPackage(PACKAGE)

    fun chooserIntent(text: String): Intent = Intent.createChooser(sendIntent(text), "Discuss in…")

    /** Needs the `<queries>` entry for [PACKAGE] in the manifest (Android 11+ package visibility). */
    fun isAvailable(context: Context): Boolean = directIntent("").resolveActivity(context.packageManager) != null

    /** Opens ChatGPT directly when it accepts shared text, else the chooser. Returns false if neither could start. */
    fun open(context: Context, text: String): Boolean {
        if (isAvailable(context)) {
            try {
                context.startActivity(directIntent(text))
                return true
            } catch (_: ActivityNotFoundException) {
                // Uninstalled or disabled since the check: fall through to the chooser.
            } catch (_: SecurityException) {
            }
        }
        return try {
            context.startActivity(chooserIntent(text))
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
