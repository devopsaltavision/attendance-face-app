package com.syntaxgenie.hfx05attendance.employee.local;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import java.util.List;

@Dao
public interface EmployeeDao {
    @Query("SELECT * FROM employees ORDER BY display_name COLLATE NOCASE, employee_id") List<EmployeeEntity> getAll();
    @Query("SELECT * FROM employees WHERE display_name LIKE '%' || :query || '%' COLLATE NOCASE " +
            "OR employee_id LIKE '%' || :query || '%' COLLATE NOCASE " +
            "OR user_id LIKE '%' || :query || '%' COLLATE NOCASE ORDER BY display_name COLLATE NOCASE")
    List<EmployeeEntity> search(String query);
    @Insert(onConflict = OnConflictStrategy.REPLACE) void upsertAll(List<EmployeeEntity> users);
    @Query("DELETE FROM employees") void deleteAll();
    @Query("SELECT * FROM employee_sync_state WHERE id = 'sync' LIMIT 1") EmployeeSyncStateEntity getSyncState();
    @Insert(onConflict = OnConflictStrategy.REPLACE) void saveSyncState(EmployeeSyncStateEntity state);

    @Transaction default void replaceDirectory(List<EmployeeEntity> users, EmployeeSyncStateEntity state) {
        deleteAll(); upsertAll(users); saveSyncState(state);
    }
    @Transaction default void updateDirectory(List<EmployeeEntity> users, EmployeeSyncStateEntity state) {
        upsertAll(users); saveSyncState(state);
    }
}
