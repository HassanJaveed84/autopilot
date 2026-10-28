package com.lifeadmin.autopilot.ai.usage

/*
 * Built-in AI is retired; these describe rows of the `usage_records` table that earlier versions
 * wrote. The rows stay on the phone and in backups, and import validates them with these enums.
 */

enum class UsageFeature(val label: String) {
    PLANNING("Planning"),
    RESEARCH("Research"),
    RESEARCH_CONTINUATION("Research · continued"),
    ASSIST("Assist"),
    /** Objective preparation and clarification under general consent (RES-023): never investigation spend. */
    OBJECTIVE_PREP("Research · understanding the question"),
}

enum class CallOutcome(val label: String) {
    IN_FLIGHT("In flight"),
    COMPLETE("Complete"),
    TRUNCATED("Cut short"),
    STOPPED("Stopped"),
    /**
     * Rejected before processing (authentication, permission, credit, rate limit, validation): the
     * provider reported no usage and the prompt never ran. Counted as $0 because nothing was reported,
     * not because Anthropic confirmed it; the dashboard says so.
     */
    FAILED_NOT_BILLED("Rejected · $0 assumed"),
    /** Failed after the request may have been processed: usage unknown, except any searches seen (a floor). */
    FAILED_UNKNOWN("Failed · usage unknown"),
    /** The app died while the call was in flight: usage unknown. */
    INTERRUPTED("Interrupted · usage unknown"),
}

enum class UsageStatus { COMPLETE, PARTIAL, UNKNOWN }
