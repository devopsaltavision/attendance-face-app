package com.syntaxgenie.hfx05attendance.fingerprint.repository.local;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

@Database(
        entities = {BiometricTemplateEntity.class},
        version = 1,
        exportSchema = true
)
public abstract class BiometricDatabase extends RoomDatabase {
    public static final String DATABASE_NAME = "biometric_templates.db";

    public abstract BiometricTemplateDao biometricTemplateDao();

    public static BiometricDatabase create(Context context) {
        return Room.databaseBuilder(
                context.getApplicationContext(),
                BiometricDatabase.class,
                DATABASE_NAME
        ).build();
    }
}
