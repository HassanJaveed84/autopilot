package proto

import java.net.URI
import java.security.MessageDigest

/*
 * Prototype of the v2 HandoffProposalBridge mapping and the apply-time merge, on a simplified model.
 * One proposal item per handoff step, so a rich step is reviewed and approved as one unit:
 *  - title not on the task          -> ADD_STEP (title + description + links)
 *  - title matches an existing step -> ADD_STEP_DETAILS (only what's new; wording untouched), or nothing
 */

data class SavedStep(val id: String, val title: String, val isDone: Boolean = false, val description: String? = null, val links: List<StepLink> = emptyList())

sealed interface Item {
    data class AddStep(val step: HandoffStep) : Item
    data class AddStepDetails(val anchorStepId: String, val description: List<String>, val links: List<StepLink>) : Item
    data class AppendNote(val text: String) : Item
}

object MatchKey { fun of(s: String) = s.lowercase().replace(Regex("""[^\p{L}\p{N}]+"""), " ").trim() }

object LinkKey {
    /** Same page for dedupe: scheme/host case, `www.`, default port, trailing slash, `#fragment` and utm_* params ignored. */
    fun of(url: String): String {
        val u = URI(url)
        val host = u.host.lowercase().removePrefix("www.")
        val path = (u.rawPath ?: "").trimEnd('/')
        val query = u.rawQuery?.split('&')?.filterNot { it.startsWith("utm_", ignoreCase = true) }?.joinToString("&")?.takeIf { it.isNotEmpty() }
        return "$host$path" + (query?.let { "?$it" } ?: "")
    }
}

object Bridge {
    fun setId(taskId: String, h: LinkHandoff): String {
        val canonical = buildString {
            append(taskId)
            h.decisions.forEach { append("\u0000d").append(it) }
            h.steps.forEach { s ->
                append("\u0000s").append(s.title)
                // Only present for rich steps, so a v1 link and its plain v2 twin keep the same id.
                s.description.forEach { append("\u0000sd").append(it) }
                s.links.forEach { append("\u0000sl").append(it.label.orEmpty()).append(' ').append(it.url) }
            }
            h.notes.forEach { append("\u0000n").append(it) }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return "hps-" + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    fun paragraphs(text: String?): List<String> = text.orEmpty().split(Regex("\n\\s*\n|\n")).map { it.trim() }.filter { it.isNotEmpty() }

    fun build(h: LinkHandoff, saved: List<SavedStep>): List<Item> {
        val out = ArrayList<Item>()
        // key -> index in out for steps proposed in this handoff, so a repeated title merges into one unit
        val proposed = HashMap<String, Int>()
        for (s in h.steps) {
            val key = MatchKey.of(s.title)
            val earlier = proposed[key]
            if (earlier != null) {
                out[earlier] = when (val it = out[earlier]) {
                    is Item.AddStep -> Item.AddStep(it.step.copy(description = mergeParas(it.step.description, s.description), links = mergeLinks(it.step.links, s.links)))
                    is Item.AddStepDetails -> it.copy(description = mergeParas(it.description, s.description), links = mergeLinks(it.links, s.links))
                    else -> it
                }
                continue
            }
            val matches = saved.filter { MatchKey.of(it.title) == key }
            val anchor = matches.firstOrNull { !it.isDone } ?: matches.firstOrNull()
            if (anchor == null) {
                proposed[key] = out.size
                out += Item.AddStep(s.copy(description = mergeParas(emptyList(), s.description), links = mergeLinks(emptyList(), s.links)))
                continue
            }
            val haveParas = paragraphs(anchor.description).map(MatchKey::of).toSet()
            val newParas = mergeParas(emptyList(), s.description).filter { MatchKey.of(it) !in haveParas }
            val haveLinks = anchor.links.map { LinkKey.of(it.url) }.toSet()
            val newLinks = mergeLinks(emptyList(), s.links).filter { LinkKey.of(it.url) !in haveLinks }
            if (newParas.isEmpty() && newLinks.isEmpty()) continue
            proposed[key] = out.size
            out += Item.AddStepDetails(anchor.id, newParas, newLinks)
        }
        return out
    }

    private fun mergeParas(a: List<String>, b: List<String>): List<String> {
        val seen = a.map(MatchKey::of).toMutableSet()
        return a + b.filter { seen.add(MatchKey.of(it)) }
    }

    private fun mergeLinks(a: List<StepLink>, b: List<StepLink>): List<StepLink> {
        val seen = a.map { LinkKey.of(it.url) }.toMutableSet()
        return a + b.filter { seen.add(LinkKey.of(it.url)) }
    }

    /** Apply-time merge, re-checked against the step as it is now. Never removes, rewords or reorders what's there. */
    fun applyDetails(step: SavedStep, item: Item.AddStepDetails, maxLinks: Int = 10): SavedStep {
        val have = paragraphs(step.description).map(MatchKey::of).toSet()
        val add = item.description.filter { MatchKey.of(it) !in have }
        val description = listOfNotNull(step.description?.trimEnd()?.takeIf { it.isNotBlank() }, add.takeIf { it.isNotEmpty() }?.joinToString("\n")).joinToString("\n\n").ifEmpty { null }
        val keys = step.links.map { LinkKey.of(it.url) }.toMutableSet()
        val links = step.links + item.links.filter { keys.add(LinkKey.of(it.url)) }
        require(links.size <= maxLinks) { "A step holds at most $maxLinks links" }
        return step.copy(description = description, links = links)
    }
}
