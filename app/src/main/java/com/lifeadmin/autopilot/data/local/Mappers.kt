package com.lifeadmin.autopilot.data.local

import com.lifeadmin.autopilot.domain.model.ReminderOccurrence
import com.lifeadmin.autopilot.domain.model.ReminderPolicy
import com.lifeadmin.autopilot.domain.model.Task
import com.lifeadmin.autopilot.domain.model.TaskEvent
import com.lifeadmin.autopilot.domain.model.TaskStep
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

fun TaskEntity.toDomain() = Task(
    id = id, title = title, notes = notes, status = status, priority = priority,
    createdAt = createdAt, updatedAt = updatedAt, completedAt = completedAt, archivedAt = archivedAt,
    deadlineType = deadlineType, deadlineLocalDate = deadlineLocalDate, deadlineInstant = deadlineInstant,
    deadlineZoneId = deadlineZoneId, snoozedUntil = snoozedUntil,
    waitingReason = waitingReason, waitingCheckInAt = waitingCheckInAt,
)

fun Task.toEntity() = TaskEntity(
    id = id, title = title, notes = notes, status = status, priority = priority,
    createdAt = createdAt, updatedAt = updatedAt, completedAt = completedAt, archivedAt = archivedAt,
    deadlineType = deadlineType, deadlineLocalDate = deadlineLocalDate, deadlineInstant = deadlineInstant,
    deadlineZoneId = deadlineZoneId, snoozedUntil = snoozedUntil,
    waitingReason = waitingReason, waitingCheckInAt = waitingCheckInAt,
)

fun TaskStepEntity.toDomain() = TaskStep(id, taskId, sortOrder, title, isDone, completedAt)
fun TaskStep.toEntity() = TaskStepEntity(id, taskId, sortOrder, title, isDone, completedAt)

fun ReminderPolicyEntity.toDomain(slots: List<ReminderTimeSlotEntity>) = ReminderPolicy(
    id = id, taskId = taskId, enabled = enabled, kind = kind, startLocalDate = startLocalDate,
    zoneMode = zoneMode, zoneId = zoneId, dayInterval = dayInterval, quietHoursBehavior = quietHoursBehavior,
    snoozeDefaultMinutes = snoozeDefaultMinutes, atInstant = atInstant, lastEditedAt = lastEditedAt,
    generation = generation, times = slots.sortedWith(compareBy({ it.sortOrder }, { it.localTime })).map { it.localTime },
)

fun ReminderPolicy.toEntity() = ReminderPolicyEntity(
    id = id, taskId = taskId, enabled = enabled, kind = kind, startLocalDate = startLocalDate,
    zoneMode = zoneMode, zoneId = zoneId, dayInterval = dayInterval, quietHoursBehavior = quietHoursBehavior,
    snoozeDefaultMinutes = snoozeDefaultMinutes, atInstant = atInstant, lastEditedAt = lastEditedAt,
    generation = generation,
)

fun ReminderOccurrenceEntity.toDomain() = ReminderOccurrence(
    id = id, policyId = policyId, taskId = taskId, kind = kind, plannedAt = plannedAt, slotAt = slotAt,
    scheduledAt = scheduledAt, deliveredAt = deliveredAt, status = status, generation = generation,
)

private val metadataJson = Json { ignoreUnknownKeys = true }

fun TaskEventEntity.toDomain() = TaskEvent(
    id = id, taskId = taskId, at = at, type = eventType, summary = summary,
    metadata = decodeMetadata(structuredMetadata),
)

fun encodeMetadata(map: Map<String, String>): String? =
    if (map.isEmpty()) null else JsonObject(map.mapValues { JsonPrimitive(it.value) }).toString()

fun decodeMetadata(raw: String?): Map<String, String> {
    if (raw.isNullOrBlank()) return emptyMap()
    return runCatching {
        metadataJson.parseToJsonElement(raw).let { it as? JsonObject }?.mapValues { it.value.jsonPrimitive.content }
    }.getOrNull() ?: emptyMap()
}
