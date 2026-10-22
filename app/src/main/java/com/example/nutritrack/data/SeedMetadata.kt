package com.example.nutritrack.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "seed_metadata")
data class SeedMetadata(
    @PrimaryKey
    val seedName: String,
    val version: Int,
    val importedAtEpochMillis: Long,
    val sourceRowCount: Int
)
