package com.syntaxgenie.hfx05attendance.employee.local;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "employees", indices = {
        @Index("employee_id"), @Index("display_name"), @Index("active")
})
public class EmployeeEntity {
    @PrimaryKey @NonNull @ColumnInfo(name = "user_id") public final String userId;
    @NonNull @ColumnInfo(name = "employee_id") public final String employeeId;
    @NonNull @ColumnInfo(name = "display_name") public final String displayName;
    @ColumnInfo(name = "active") public final boolean active;
    @ColumnInfo(name = "fingerprint_enrolled") public final boolean fingerprintEnrolled;
    @Nullable @ColumnInfo(name = "fingerprint_enrollment_id") public final String fingerprintEnrollmentId;
    @ColumnInfo(name = "face_enrolled") public final boolean faceEnrolled;
    @Nullable @ColumnInfo(name = "face_enrollment_id") public final String faceEnrollmentId;
    @NonNull @ColumnInfo(name = "updated_at") public final String updatedAt;

    public EmployeeEntity(@NonNull String userId, @NonNull String employeeId, @NonNull String displayName,
            boolean active, boolean fingerprintEnrolled, @Nullable String fingerprintEnrollmentId,
            boolean faceEnrolled, @Nullable String faceEnrollmentId,
            @NonNull String updatedAt) {
        this.userId = userId; this.employeeId = employeeId; this.displayName = displayName;
        this.active = active; this.fingerprintEnrolled = fingerprintEnrolled;
        this.fingerprintEnrollmentId = fingerprintEnrollmentId; this.updatedAt = updatedAt;
        this.faceEnrolled = faceEnrolled; this.faceEnrollmentId = faceEnrollmentId;
    }
}
