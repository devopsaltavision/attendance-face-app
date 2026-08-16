package com.syntaxgenie.hfx05attendance.fingerprint.repository.local;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(
        tableName = "biometric_templates",
        indices = {
                @Index(value = {"employee_id"}),
                @Index(value = {"employee_id", "finger_position"}),
                @Index(
                        value = {"employee_id", "finger_position", "template_slot"},
                        unique = true
                )
        }
)
public final class BiometricTemplateEntity {
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    public final String id;
    @NonNull
    @ColumnInfo(name = "employee_id")
    public final String employeeId;
    @NonNull
    @ColumnInfo(name = "finger_position")
    public final String fingerPosition;
    @ColumnInfo(name = "template_slot")
    public final int templateSlot;
    @NonNull
    @ColumnInfo(name = "matcher_engine")
    public final String matcherEngine;
    @NonNull
    @ColumnInfo(name = "matcher_implementation_version")
    public final String matcherImplementationVersion;
    @NonNull
    @ColumnInfo(name = "template_format")
    public final String templateFormat;
    @ColumnInfo(name = "template_format_version")
    public final int templateFormatVersion;
    @NonNull
    @ColumnInfo(name = "template_bytes", typeAffinity = ColumnInfo.BLOB)
    public final byte[] templateBytes;
    @ColumnInfo(name = "created_at")
    public final long createdAtEpochMillis;
    @ColumnInfo(name = "updated_at")
    public final long updatedAtEpochMillis;

    public BiometricTemplateEntity(
            @NonNull String id,
            @NonNull String employeeId,
            @NonNull String fingerPosition,
            int templateSlot,
            @NonNull String matcherEngine,
            @NonNull String matcherImplementationVersion,
            @NonNull String templateFormat,
            int templateFormatVersion,
            @NonNull byte[] templateBytes,
            long createdAtEpochMillis,
            long updatedAtEpochMillis
    ) {
        this.id = id;
        this.employeeId = employeeId;
        this.fingerPosition = fingerPosition;
        this.templateSlot = templateSlot;
        this.matcherEngine = matcherEngine;
        this.matcherImplementationVersion = matcherImplementationVersion;
        this.templateFormat = templateFormat;
        this.templateFormatVersion = templateFormatVersion;
        this.templateBytes = templateBytes;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.updatedAtEpochMillis = updatedAtEpochMillis;
    }
}
