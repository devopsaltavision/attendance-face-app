package com.syntaxgenie.hfx05attendance.face.repository.local;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "face_enrollments", indices = {
        @Index("employee_id"),
        @Index(value = {"engine_id", "model_id", "model_version", "template_format_version"}),
        @Index("status")
})
public class FaceEnrollmentEntity {
    @PrimaryKey @NonNull @ColumnInfo(name = "enrollment_id") public final String enrollmentId;
    @NonNull @ColumnInfo(name = "employee_id") public final String employeeId;
    @NonNull @ColumnInfo(name = "protected_template_payload") public final byte[] protectedTemplatePayload;
    @NonNull @ColumnInfo(name = "engine_id") public final String engineId;
    @NonNull @ColumnInfo(name = "model_id") public final String modelId;
    @NonNull @ColumnInfo(name = "model_version") public final String modelVersion;
    @NonNull @ColumnInfo(name = "template_format_version") public final String templateFormatVersion;
    @ColumnInfo(name = "backup_schema_version") public final int backupSchemaVersion;
    @Nullable @ColumnInfo(name = "quality_score") public final Double qualityScore;
    @Nullable @ColumnInfo(name = "enrollment_sample_count") public final Integer enrollmentSampleCount;
    @ColumnInfo(name = "created_at") public final long createdAt;
    @ColumnInfo(name = "updated_at") public final long updatedAt;
    @NonNull @ColumnInfo(name = "status") public final String status;

    public FaceEnrollmentEntity(@NonNull String enrollmentId, @NonNull String employeeId,
            @NonNull byte[] protectedTemplatePayload, @NonNull String engineId, @NonNull String modelId,
            @NonNull String modelVersion, @NonNull String templateFormatVersion, int backupSchemaVersion,
            @Nullable Double qualityScore, @Nullable Integer enrollmentSampleCount, long createdAt,
            long updatedAt, @NonNull String status) {
        this.enrollmentId = enrollmentId;
        this.employeeId = employeeId;
        this.protectedTemplatePayload = protectedTemplatePayload.clone();
        this.engineId = engineId;
        this.modelId = modelId;
        this.modelVersion = modelVersion;
        this.templateFormatVersion = templateFormatVersion;
        this.backupSchemaVersion = backupSchemaVersion;
        this.qualityScore = qualityScore;
        this.enrollmentSampleCount = enrollmentSampleCount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.status = status;
    }

    @Override public String toString() {
        return "FaceEnrollmentEntity(enrollmentId=" + enrollmentId + ", employeeId=" + employeeId +
                ", engineId=" + engineId + ", modelId=" + modelId + ", modelVersion=" + modelVersion +
                ", templateFormatVersion=" + templateFormatVersion + ", status=" + status +
                ", protectedTemplatePayload=<redacted>)";
    }
}
