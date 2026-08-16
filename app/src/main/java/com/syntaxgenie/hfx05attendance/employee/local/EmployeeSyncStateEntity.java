package com.syntaxgenie.hfx05attendance.employee.local;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "employee_sync_state")
public class EmployeeSyncStateEntity {
    @PrimaryKey @NonNull public final String id;
    @Nullable @ColumnInfo(name = "next_updated_after") public final String nextUpdatedAfter;
    @Nullable @ColumnInfo(name = "last_successful_sync_at") public final String lastSuccessfulSyncAt;
    public EmployeeSyncStateEntity(@NonNull String id, @Nullable String nextUpdatedAfter, @Nullable String lastSuccessfulSyncAt) {
        this.id = id; this.nextUpdatedAfter = nextUpdatedAfter; this.lastSuccessfulSyncAt = lastSuccessfulSyncAt;
    }
}
