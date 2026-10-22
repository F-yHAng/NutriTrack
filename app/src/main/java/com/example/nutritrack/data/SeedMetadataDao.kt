package com.example.nutritrack.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface SeedMetadataDao {
    @Query("SELECT * FROM seed_metadata WHERE seedName = :seedName")
    suspend fun getByName(seedName: String): SeedMetadata?

    @Upsert
    suspend fun upsert(metadata: SeedMetadata)

    @Query("DELETE FROM seed_metadata WHERE seedName = :seedName")
    suspend fun deleteByName(seedName: String)
}
