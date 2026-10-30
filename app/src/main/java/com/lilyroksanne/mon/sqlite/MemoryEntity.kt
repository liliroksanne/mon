package com.lilyroksanne.mon.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mon_memories")
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val summaryText: String
)