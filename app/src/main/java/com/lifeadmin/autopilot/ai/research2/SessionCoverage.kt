package com.lifeadmin.autopilot.ai.research2

/** Coverage of a saved M3A.2 session, for the read-only session screen. */
object SessionCoverage {
    /** Requirements still open when the session was saved. */
    fun gaps(session: SessionContent): List<Requirement> = session.requirements.filter { session.coverageOf(it.id).status.open }

    /** "3 of 4 ready · 1 needs you" (d2). */
    fun line(session: SessionContent): String {
        val items = session.requirements.filter { it.type == RequirementType.ITEM || it.type == RequirementType.USER_ADDED }
        if (items.isEmpty()) return ""
        val st = items.map { session.coverageOf(it.id).status }
        val ready = st.count { it == RequirementStatus.ANSWERED || it == RequirementStatus.RESOLVED_CONFLICT }
        val you = st.count { it.needsUser }
        val missing = st.count { it == RequirementStatus.NOT_FOUND || it == RequirementStatus.PENDING }
        val partly = st.count { it == RequirementStatus.PARTLY }
        return buildList {
            add("$ready of ${items.size} ready")
            if (you > 0) add("$you need${if (you == 1) "s" else ""} you")
            if (partly > 0) add("$partly partly")
            if (missing > 0) add("$missing not found")
        }.joinToString(" · ")
    }
}
