package com.syntaxgenie.hfx05attendance.employee.local;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface AttendanceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) long insert(AttendanceEventEntity event);

    @Query("SELECT * FROM attendance_events WHERE event_id = :eventId LIMIT 1")
    AttendanceEventEntity get(String eventId);

    @Query("SELECT * FROM attendance_events WHERE sync_state = 'PENDING' " +
            "ORDER BY device_timestamp, event_id LIMIT :limit")
    List<AttendanceEventEntity> pending(int limit);

    @Query("UPDATE attendance_events SET sync_state = 'SYNCED', attendance_record_id = :recordId, " +
            "attendance_action = :action, server_timestamp = :serverTimestamp WHERE event_id = :eventId")
    void markSynced(String eventId, String recordId, String action, String serverTimestamp);

    @Query("DELETE FROM attendance_events WHERE event_id = :eventId")
    int delete(String eventId);
}
