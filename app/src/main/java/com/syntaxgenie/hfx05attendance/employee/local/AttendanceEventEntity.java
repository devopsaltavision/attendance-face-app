package com.syntaxgenie.hfx05attendance.employee.local;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "attendance_events", indices = {
        @Index("sync_state"), @Index("device_timestamp")
})
public class AttendanceEventEntity {
    @PrimaryKey @NonNull @ColumnInfo(name = "event_id") public final String eventId;
    @NonNull @ColumnInfo(name = "user_id") public final String userId;
    @NonNull @ColumnInfo(name = "employee_id") public final String employeeId;
    @NonNull @ColumnInfo(name = "device_timestamp") public final String deviceTimestamp;
    @NonNull @ColumnInfo(name = "sync_state") public final String syncState;
    @Nullable @ColumnInfo(name = "attendance_record_id") public final String attendanceRecordId;
    @Nullable @ColumnInfo(name = "attendance_action") public final String attendanceAction;
    @Nullable @ColumnInfo(name = "server_timestamp") public final String serverTimestamp;
    @Nullable @ColumnInfo(name = "requested_action") public final String requestedAction;
    @Nullable @ColumnInfo(name = "biometric_type") public final String biometricType;

    public AttendanceEventEntity(@NonNull String eventId, @NonNull String userId,
            @NonNull String employeeId, @NonNull String deviceTimestamp, @NonNull String syncState,
            @Nullable String attendanceRecordId, @Nullable String attendanceAction,
            @Nullable String serverTimestamp, @Nullable String requestedAction, @Nullable String biometricType) {
        this.eventId = eventId; this.userId = userId; this.employeeId = employeeId;
        this.deviceTimestamp = deviceTimestamp; this.syncState = syncState;
        this.attendanceRecordId = attendanceRecordId; this.attendanceAction = attendanceAction;
        this.serverTimestamp = serverTimestamp;
        this.requestedAction = requestedAction; this.biometricType = biometricType;
    }
}
