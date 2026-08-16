package com.syntaxgenie.hfx05attendance.fingerprint.repository.local;

import android.content.Context;
import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Database(
        entities = {BiometricTemplateEntity.class},
        version = 2,
        exportSchema = true
)
public abstract class BiometricDatabase extends RoomDatabase {
    public static final String DATABASE_NAME = "biometric_templates.db";
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase database) {
            database.execSQL(
                    "CREATE TABLE IF NOT EXISTS `biometric_templates_v2` (" +
                            "`id` TEXT NOT NULL, `enrollment_id` TEXT NOT NULL, " +
                            "`employee_id` TEXT NOT NULL, `finger_position` TEXT NOT NULL, " +
                            "`template_slot` INTEGER NOT NULL, `matcher_engine` TEXT NOT NULL, " +
                            "`matcher_implementation_version` TEXT NOT NULL, " +
                            "`template_format` TEXT NOT NULL, `template_format_version` INTEGER NOT NULL, " +
                            "`template_bytes` BLOB NOT NULL, `created_at` INTEGER NOT NULL, " +
                            "`updated_at` INTEGER NOT NULL, PRIMARY KEY(`id`))"
            );
            SupportSQLiteStatement insert = database.compileStatement(
                    "INSERT INTO `biometric_templates_v2` (" +
                            "`id`, `enrollment_id`, `employee_id`, `finger_position`, `template_slot`, " +
                            "`matcher_engine`, `matcher_implementation_version`, `template_format`, " +
                            "`template_format_version`, `template_bytes`, `created_at`, `updated_at`) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            );
            try (Cursor cursor = database.query(
                    "SELECT `id`, `employee_id`, `finger_position`, `template_slot`, " +
                            "`matcher_engine`, `matcher_implementation_version`, `template_format`, " +
                            "`template_format_version`, `template_bytes`, `created_at`, `updated_at` " +
                            "FROM `biometric_templates`"
            )) {
                while (cursor.moveToNext()) {
                    String employeeId = cursor.getString(1);
                    String fingerPosition = cursor.getString(2);
                    insert.clearBindings();
                    insert.bindString(1, cursor.getString(0));
                    insert.bindString(2, legacyEnrollmentId(employeeId, fingerPosition));
                    insert.bindString(3, employeeId);
                    insert.bindString(4, fingerPosition);
                    insert.bindLong(5, cursor.getLong(3));
                    insert.bindString(6, cursor.getString(4));
                    insert.bindString(7, cursor.getString(5));
                    insert.bindString(8, cursor.getString(6));
                    insert.bindLong(9, cursor.getLong(7));
                    insert.bindBlob(10, cursor.getBlob(8));
                    insert.bindLong(11, cursor.getLong(9));
                    insert.bindLong(12, cursor.getLong(10));
                    insert.executeInsert();
                }
            }
            database.execSQL("DROP TABLE `biometric_templates`");
            database.execSQL("ALTER TABLE `biometric_templates_v2` RENAME TO `biometric_templates`");
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_biometric_templates_employee_id` " +
                            "ON `biometric_templates` (`employee_id`)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_biometric_templates_employee_id_finger_position` " +
                            "ON `biometric_templates` (`employee_id`, `finger_position`)"
            );
            database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                            "`index_biometric_templates_employee_id_finger_position_template_slot` " +
                            "ON `biometric_templates` (`employee_id`, `finger_position`, `template_slot`)"
            );
            database.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_biometric_templates_enrollment_id` " +
                            "ON `biometric_templates` (`enrollment_id`)"
            );
            database.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS `index_biometric_templates_enrollment_id_template_slot` " +
                            "ON `biometric_templates` (`enrollment_id`, `template_slot`)"
            );
        }
    };

    public abstract BiometricTemplateDao biometricTemplateDao();

    public static BiometricDatabase create(Context context) {
        return Room.databaseBuilder(
                context.getApplicationContext(),
                BiometricDatabase.class,
                DATABASE_NAME
        ).addMigrations(MIGRATION_1_2).build();
    }

    private static String legacyEnrollmentId(String employeeId, String fingerPosition) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    (employeeId + "\u0000" + fingerPosition).getBytes(StandardCharsets.UTF_8)
            );
            StringBuilder encoded = new StringBuilder("legacy-");
            for (byte value : hash) {
                encoded.append(String.format("%02x", value & 0xff));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
