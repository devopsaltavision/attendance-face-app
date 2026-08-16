package com.syntaxgenie.hfx05attendance.employee.local;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(entities = {EmployeeEntity.class, EmployeeSyncStateEntity.class}, version = 1, exportSchema = true)
public abstract class EmployeeDirectoryDatabase extends RoomDatabase {
    public abstract EmployeeDao employeeDao();
    public static EmployeeDirectoryDatabase create(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(), EmployeeDirectoryDatabase.class,
                "employee_directory.db").build();
    }
}
