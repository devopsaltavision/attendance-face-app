package com.syntaxgenie.hfx05attendance.face.repository.local;

import android.content.Context;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/** Independent biometric database. Never use destructive migrations for this database. */
@Database(entities = {FaceEnrollmentEntity.class}, version = 1, exportSchema = true)
public abstract class FaceEnrollmentDatabase extends RoomDatabase {
    public abstract FaceEnrollmentDao faceEnrollmentDao();

    public static FaceEnrollmentDatabase create(Context context) {
        return Room.databaseBuilder(context.getApplicationContext(), FaceEnrollmentDatabase.class,
                "face_enrollments.db").build();
    }
}
