package com.example.nutritrack.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface UserAccountDao {
    
    @Query("SELECT * FROM user_accounts WHERE userId = :userId")
    suspend fun getUserById(userId: Int): UserAccount?
    
    @Query("SELECT * FROM user_accounts WHERE userId = :userId AND phoneNumber = :phoneNumber")
    suspend fun getUserByIdAndPhone(userId: Int, phoneNumber: String): UserAccount?
    
    @Query("SELECT COUNT(*) FROM user_accounts WHERE userId = :userId")
    suspend fun isUserRegistered(userId: Int): Int
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUser(userAccount: UserAccount)
    
    @Update
    suspend fun updateUser(userAccount: UserAccount)
    
    @Query("UPDATE user_accounts SET lastLoginTime = :loginTime WHERE userId = :userId")
    suspend fun updateLastLoginTime(userId: Int, loginTime: Long)
    
    @Query("UPDATE user_accounts SET name = :name WHERE userId = :userId")
    suspend fun updateUserName(userId: Int, name: String)
    
    @Delete
    suspend fun deleteUser(userAccount: UserAccount)
    
    @Query("SELECT * FROM user_accounts")
    fun getAllUsers(): Flow<List<UserAccount>>
}
