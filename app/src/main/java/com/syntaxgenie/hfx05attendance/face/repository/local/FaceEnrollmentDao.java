package com.syntaxgenie.hfx05attendance.face.repository.local;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;
import androidx.room.Transaction;
import java.util.List;

@Dao
public interface FaceEnrollmentDao {
    @Query("SELECT * FROM face_enrollments WHERE enrollment_id = :id LIMIT 1") FaceEnrollmentEntity getById(String id);
    @Query("SELECT * FROM face_enrollments WHERE employee_id = :employeeId ORDER BY created_at")
    List<FaceEnrollmentEntity> listByEmployee(String employeeId);
    @Query("SELECT * FROM face_enrollments WHERE engine_id = :engineId AND model_id = :modelId " +
            "AND model_version = :modelVersion AND template_format_version = :templateFormatVersion " +
            "ORDER BY created_at")
    List<FaceEnrollmentEntity> listCompatible(String engineId, String modelId, String modelVersion,
            String templateFormatVersion);
    @Insert(onConflict = OnConflictStrategy.ABORT) void insert(FaceEnrollmentEntity entity);
    @Update int update(FaceEnrollmentEntity entity);
    @Query("DELETE FROM face_enrollments WHERE enrollment_id = :id") int deleteById(String id);
    @Query("DELETE FROM face_enrollments WHERE employee_id = :employeeId") int deleteAllForEmployee(String employeeId);
    @Transaction default void replaceAllForEmployee(String employeeId, List<FaceEnrollmentEntity> entities) {
        deleteAllForEmployee(employeeId);
        for (FaceEnrollmentEntity entity : entities) insert(entity);
    }
}
