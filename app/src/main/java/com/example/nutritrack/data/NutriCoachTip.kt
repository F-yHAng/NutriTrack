package com.example.nutritrack.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.Index

@Entity(
    tableName = "nutricoach_tips",
    foreignKeys = [
        ForeignKey(
            entity = Patient::class,
            parentColumns = ["userId"],
            childColumns = ["patientUserId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["patientUserId"])]
)
data class NutriCoachTip(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val patientUserId: Int,
    val tipContent: String,
    val tipType: String, // "motivational", "fruit_improvement", "general_nutrition"
    val timestamp: Long = System.currentTimeMillis(),
    val userFruitScore: Double = 0.0,
    val aiModel: String = "default" // Track which AI model generated this tip
)