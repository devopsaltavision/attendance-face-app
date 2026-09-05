package com.syntaxgenie.hfx05attendance.employee.local;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

@Database(entities = {EmployeeEntity.class, EmployeeSyncStateEntity.class, AttendanceEventEntity.class}, version = 3, exportSchema = true)
public abstract class EmployeeDirectoryDatabase extends RoomDatabase {
    public abstract EmployeeDao employeeDao();
    public abstract AttendanceDao attendanceDao();
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("CREATE TABLE IF NOT EXISTS attendance_events (" +
                    "event_id TEXT NOT NULL, user_id TEXT NOT NULL, employee_id TEXT NOT NULL, " +
                    "device_timestamp TEXT NOT NULL, sync_state TEXT NOT NULL, " +
                    "attendance_record_id TEXT, attendance_action TEXT, server_timestamp TEXT, " +
                    "PRIMARY KEY(event_id))");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_attendance_events_sync_state " +
                    "ON attendance_events(sync_state)");
            database.execSQL("CREATE INDEX IF NOT EXISTS index_attendance_events_device_timestamp " +
                    "ON attendance_events(device_timestamp)");
        }
    };
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override public void migrate(SupportSQLiteDatabase database) {
            database.execSQL("ALTER TABLE employees ADD COLUMN face_enrolled INTEGER NOT NULL DEFAULT 0");
            database.execSQL("ALTER TABLE employees ADD COLUMN face_enrollment_id TEXT");
        }
    };
    public static EmployeeDirectoryDatabase create(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(), EmployeeDirectoryDatabase.class,
                "employee_directory.db").addMigrations(MIGRATION_1_2, MIGRATION_2_3).build();
    }
}
