package com.example.nutritrack.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.ColumnInfo
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "food_intake",
    foreignKeys = [
        ForeignKey(
            entity = Patient::class,
            parentColumns = ["userId"],
            childColumns = ["patientUserId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["patientUserId", "submissionDate"])]
)
data class FoodIntake(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val patientUserId: Int,
    val submissionDate: Long,
    @ColumnInfo(defaultValue = "'Legacy intake'")
    val foodName: String = "Legacy intake",

    val vegetablesServings: Double = 0.0,
    val fruitServings: Double = 0.0,
    val grainsServings: Double = 0.0,
    val meatServings: Double = 0.0,
    val dairyServings: Double = 0.0,
    val discretionaryServings: Double = 0.0,

    val cruciferous: Double = 0.0,
    val tuberAndBulb: Double = 0.0,
    val greenVegetables: Double = 0.0,
    val redOrangeVegetables: Double = 0.0,
    val otherVegetables: Double = 0.0,
    val legumes: Double = 0.0,
    
    val pomefruits: Double = 0.0,
    val tropicalFruits: Double = 0.0,
    val berryFruits: Double = 0.0,
    val stoneFruits: Double = 0.0,
    val citrusFruits: Double = 0.0,
    val otherFruits: Double = 0.0,
    
    val wholeGrains: Double = 0.0,
    val nonWholeGrains: Double = 0.0,

    val sodiumMg: Double = 0.0,
    val alcoholStandardDrinks: Double = 0.0,
    val waterMl: Double = 0.0,
    val totalBeverageMl: Double = 0.0,
    val sugarG: Double = 0.0,
    val saturatedFatG: Double = 0.0,
    val unsaturatedFatG: Double = 0.0,

    val questionnaireResponses: String = "{}"
)
