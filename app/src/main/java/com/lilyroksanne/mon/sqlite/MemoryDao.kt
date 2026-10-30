package com.lilyroksanne.mon.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface MemoryDao {
    @Insert
    suspend fun insertMemory(memory: MemoryEntity)

    // Solo trae el último resumen guardado
    @Query("SELECT * FROM mon_memories ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLastMemory(): MemoryEntity?
}