package com.example.nutritrack.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface PatientDao {
    
    @Query("SELECT * FROM patients")
    fun getAllPatients(): Flow<List<Patient>>
    
    @Query("SELECT * FROM patients WHERE userId = :userId")
    suspend fun getPatientByUserId(userId: Int): Patient?
    
    @Query("SELECT * FROM patients WHERE phoneNumber = :phoneNumber")
    suspend fun getPatientByPhoneNumber(phoneNumber: String): Patient?
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPatient(patient: Patient)
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllPatients(patients: List<Patient>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSeedPatients(patients: List<Patient>): List<Long>
    
    @Update
    suspend fun updatePatient(patient: Patient)
    
    @Delete
    suspend fun deletePatient(patient: Patient)
    
    @Query("DELETE FROM patients")
    suspend fun deleteAllPatients()
    
    @Query("SELECT COUNT(*) FROM patients")
    suspend fun getPatientCount(): Int
    
    @Query("UPDATE patients SET name = :name WHERE userId = :userId")
    suspend fun updatePatientName(userId: Int, name: String)
}
