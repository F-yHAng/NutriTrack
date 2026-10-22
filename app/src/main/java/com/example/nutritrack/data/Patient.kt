package com.example.nutritrack.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "patients")
data class Patient(
    @PrimaryKey
    val userId: Int,
    val phoneNumber: String,
    val name: String = "",
    val sex: String,
    
    // HEIFA total scores
    val hEIFAtotalscoreMale: Double,
    val hEIFAtotalscoreFemale: Double,
    
    // Discretionary scores
    val discretionaryHEIFAscoreMale: Double,
    val discretionaryHEIFAscoreFemale: Double,
    val discretionaryservesize: Double,
    
    // Vegetables scores
    val vegetablesHEIFAscoreMale: Double,
    val vegetablesHEIFAscoreFemale: Double,
    val vegetableswithlegumesallocatedservesize: Double,
    val legumesallocatedVegetables: Double,
    val vegetablesvariationsscore: Double,
    val vegetablesCruciferous: Double,
    val vegetablesTuberandbulb: Double,
    val vegetablesOther: Double,
    val legumes: Double,
    val vegetablesGreen: Double,
    val vegetablesRedandorange: Double,
    
    // Fruit scores
    val fruitHEIFAscoreMale: Double,
    val fruitHEIFAscoreFemale: Double,
    val fruitservesize: Double,
    val fruitvariationsscore: Double,
    val fruitPome: Double,
    val fruitTropicalandsubtropical: Double,
    val fruitBerry: Double,
    val fruitStone: Double,
    val fruitCitrus: Double,
    val fruitOther: Double,
    
    // Grains and cereals scores
    val grainsandcerealsHEIFAscoreMale: Double,
    val grainsandcerealsHEIFAscoreFemale: Double,
    val grainsandcerealsservesize: Double,
    val grainsandcerealsNonwholegrains: Double,
    val wholegrainsHEIFAscoreMale: Double,
    val wholegrainsHEIFAscoreFemale: Double,
    val wholegrainsservesize: Double,
    
    // Meat and alternatives scores
    val meatandalternativesHEIFAscoreMale: Double,
    val meatandalternativesHEIFAscoreFemale: Double,
    val meatandalternativeswithlegumesallocatedservesize: Double,
    val legumesallocatedMeatandalternatives: Double,
    
    // Dairy and alternatives scores
    val dairyandalternativesHEIFAscoreMale: Double,
    val dairyandalternativesHEIFAscoreFemale: Double,
    val dairyandalternativesservesize: Double,
    
    // Sodium scores
    val sodiumHEIFAscoreMale: Double,
    val sodiumHEIFAscoreFemale: Double,
    val sodiummgmilligrams: Double,
    
    // Alcohol scores
    val alcoholHEIFAscoreMale: Double,
    val alcoholHEIFAscoreFemale: Double,
    val alcoholstandarddrinks: Double,
    
    // Water scores
    val waterHEIFAscoreMale: Double,
    val waterHEIFAscoreFemale: Double,
    val water: Double,
    val waterTotalmL: Double,
    val beverageTotalmL: Double,
    
    // Sugar scores
    val sugarHEIFAscoreMale: Double,
    val sugarHEIFAscoreFemale: Double,
    val sugar: Double,
    
    // Saturated Fat scores
    val saturatedFatHEIFAscoreMale: Double,
    val saturatedFatHEIFAscoreFemale: Double,
    val saturatedFat: Double,
    
    // Unsaturated Fat scores
    val unsaturatedFatHEIFAscoreMale: Double,
    val unsaturatedFatHEIFAscoreFemale: Double,
    val unsaturatedFatservesize: Double
) 