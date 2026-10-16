package com.lifeadmin.autopilot.data.local

import androidx.room.TypeConverter
import com.lifeadmin.autopilot.domain.model.DeadlineType
import com.lifeadmin.autopilot.domain.model.EventType
import com.lifeadmin.autopilot.domain.model.OccurrenceKind
import com.lifeadmin.autopilot.domain.model.OccurrenceStatus
import com.lifeadmin.autopilot.domain.model.Priority
import com.lifeadmin.autopilot.domain.model.QuietHoursBehavior
import com.lifeadmin.autopilot.domain.model.ReminderKind
import com.lifeadmin.autopilot.domain.model.TaskStatus
import com.lifeadmin.autopilot.domain.model.ZoneMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** Instants are stored as epoch millis (UTC); local dates/times as ISO strings; enums by name. */
class Converters {
    @TypeConverter fun instantToLong(v: Instant?): Long? = v?.toEpochMilli()
    @TypeConverter fun longToInstant(v: Long?): Instant? = v?.let(Instant::ofEpochMilli)

    @TypeConverter fun localDateToString(v: LocalDate?): String? = v?.toString()
    @TypeConverter fun stringToLocalDate(v: String?): LocalDate? = v?.let(LocalDate::parse)

    @TypeConverter fun localTimeToString(v: LocalTime?): String? = v?.toString()
    @TypeConverter fun stringToLocalTime(v: String?): LocalTime? = v?.let(LocalTime::parse)

    @TypeConverter fun taskStatusToString(v: TaskStatus): String = v.name
    @TypeConverter fun stringToTaskStatus(v: String): TaskStatus = TaskStatus.valueOf(v)

    @TypeConverter fun priorityToString(v: Priority): String = v.name
    @TypeConverter fun stringToPriority(v: String): Priority = Priority.valueOf(v)

    @TypeConverter fun deadlineTypeToString(v: DeadlineType): String = v.name
    @TypeConverter fun stringToDeadlineType(v: String): DeadlineType = DeadlineType.valueOf(v)

    @TypeConverter fun reminderKindToString(v: ReminderKind): String = v.name
    @TypeConverter fun stringToReminderKind(v: String): ReminderKind = ReminderKind.valueOf(v)

    @TypeConverter fun zoneModeToString(v: ZoneMode): String = v.name
    @TypeConverter fun stringToZoneMode(v: String): ZoneMode = ZoneMode.valueOf(v)

    @TypeConverter fun quietBehaviorToString(v: QuietHoursBehavior?): String? = v?.name
    @TypeConverter fun stringToQuietBehavior(v: String?): QuietHoursBehavior? = v?.let(QuietHoursBehavior::valueOf)

    @TypeConverter fun occurrenceKindToString(v: OccurrenceKind): String = v.name
    @TypeConverter fun stringToOccurrenceKind(v: String): OccurrenceKind = OccurrenceKind.valueOf(v)

    @TypeConverter fun occurrenceStatusToString(v: OccurrenceStatus): String = v.name
    @TypeConverter fun stringToOccurrenceStatus(v: String): OccurrenceStatus = OccurrenceStatus.valueOf(v)

    @TypeConverter fun eventTypeToString(v: EventType): String = v.name
    @TypeConverter fun stringToEventType(v: String): EventType = EventType.valueOf(v)
}
