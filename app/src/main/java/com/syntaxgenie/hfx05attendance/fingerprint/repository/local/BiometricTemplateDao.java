package com.syntaxgenie.hfx05attendance.fingerprint.repository.local;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;
import java.util.List;

@Dao
public interface BiometricTemplateDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insert(BiometricTemplateEntity entity);

    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insertAll(List<BiometricTemplateEntity> entities);

    @Transaction
    default void insertEnrollment(List<BiometricTemplateEntity> entities) {
        insertAll(entities);
    }

    @Query("SELECT * FROM biometric_templates WHERE employee_id = :employeeId " +
            "ORDER BY finger_position, template_slot")
    List<BiometricTemplateEntity> getByEmployee(String employeeId);

    @Query("SELECT * FROM biometric_templates WHERE employee_id = :employeeId " +
            "AND finger_position = :fingerPosition ORDER BY template_slot")
    List<BiometricTemplateEntity> getByEmployeeAndFinger(String employeeId, String fingerPosition);

    @Query("SELECT * FROM biometric_templates WHERE enrollment_id = :enrollmentId ORDER BY template_slot")
    List<BiometricTemplateEntity> getByEnrollmentId(String enrollmentId);

    @Query("SELECT * FROM biometric_templates ORDER BY employee_id, finger_position, template_slot")
    List<BiometricTemplateEntity> getAll();

    @Query("SELECT COUNT(*) FROM biometric_templates WHERE employee_id = :employeeId " +
            "AND finger_position = :fingerPosition AND template_slot = :templateSlot")
    int countLogicalSlot(String employeeId, String fingerPosition, int templateSlot);

    @Query("DELETE FROM biometric_templates WHERE employee_id = :employeeId " +
            "AND finger_position = :fingerPosition")
    int deleteByEmployeeAndFinger(String employeeId, String fingerPosition);

    @Query("DELETE FROM biometric_templates WHERE employee_id = :employeeId")
    int deleteByEmployee(String employeeId);

    @Query("DELETE FROM biometric_templates WHERE enrollment_id = :enrollmentId")
    int deleteByEnrollmentId(String enrollmentId);

    @Query("DELETE FROM biometric_templates")
    int deleteAll();
}
