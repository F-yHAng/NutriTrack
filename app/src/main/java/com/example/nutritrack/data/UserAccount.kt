package com.example.nutritrack.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "user_accounts",
    foreignKeys = [
        ForeignKey(
            entity = Patient::class,
            parentColumns = ["userId"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["userId"])]
)
data class UserAccount(
    @PrimaryKey
    val userId: Int,
    val phoneNumber: String,
    val name: String = "",
    val passwordHash: ByteArray,
    val passwordSalt: ByteArray,
    val passwordAlgorithm: String,
    val passwordAlgorithmVersion: Int,
    val passwordIterations: Int,
    val passwordKeyLengthBits: Int,
    val registrationDate: Long = System.currentTimeMillis(),
    val lastLoginTime: Long = 0
)
