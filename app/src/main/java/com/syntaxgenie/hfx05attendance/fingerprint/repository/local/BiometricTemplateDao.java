package com.syntaxgenie.hfx05attendance.fingerprint.repository.local;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import java.util.List;

@Dao
public interface BiometricTemplateDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    void insert(BiometricTemplateEntity entity);

    @Query("SELECT * FROM biometric_templates WHERE employee_id = :employeeId " +
            "ORDER BY finger_position, template_slot")
    List<BiometricTemplateEntity> getByEmployee(String employeeId);

    @Query("SELECT * FROM biometric_templates WHERE employee_id = :employeeId " +
            "AND finger_position = :fingerPosition ORDER BY template_slot")
    List<BiometricTemplateEntity> getByEmployeeAndFinger(String employeeId, String fingerPosition);

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
}
