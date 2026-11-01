package com.lilyroksanne.mon.data.db

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.lilyroksanne.mon.util.MonLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemoryRepository(context: Context) {
    private val TAG = "MemoryRepository"
    private val dbHelper = SummaryDbHelper(context)

    // ==============================================
    // MEMORIA DE PERFIL (USUARIO)
    // ==============================================

    /**
     * Guarda o actualiza un dato del perfil del usuario (Gustos, edad, familiares, etc.)
     */
    suspend fun upsertUserProfileData(category: String, newInfo: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase

        val cursor = db.query(
            SummaryDbHelper.TABLE_LILY_PROFILE,
            arrayOf(SummaryDbHelper.COLUMN_PROFILE_INFO),
            "${SummaryDbHelper.COLUMN_PROFILE_CATEGORY} = ?",
            arrayOf(category),
            null, null, null
        )

        var existingInfo = ""
        val exists = cursor.moveToFirst()
        if (exists) {
            existingInfo = cursor.getString(0)
        }
        cursor.close()

        val finalInfo = if (exists) {
            if (existingInfo.contains(newInfo, ignoreCase = true)) {
                existingInfo
            } else {
                "$existingInfo. $newInfo"
            }
        } else {
            newInfo
        }

        val values = ContentValues().apply {
            put(SummaryDbHelper.COLUMN_PROFILE_CATEGORY, category)
            put(SummaryDbHelper.COLUMN_PROFILE_INFO, finalInfo)
        }

        db.insertWithOnConflict(
            SummaryDbHelper.TABLE_LILY_PROFILE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        MonLogger.d(TAG, "Perfil de Usuario actualizado. Categoría: [$category] -> Info: [$finalInfo]")
    }

    /**
     * Busca en el perfil del usuario utilizando una palabra clave.
     */
    suspend fun searchUserProfile(keyword: String): String = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            SummaryDbHelper.TABLE_LILY_PROFILE,
            arrayOf(SummaryDbHelper.COLUMN_PROFILE_CATEGORY, SummaryDbHelper.COLUMN_PROFILE_INFO),
            "${SummaryDbHelper.COLUMN_PROFILE_INFO} LIKE ? OR ${SummaryDbHelper.COLUMN_PROFILE_INFO} LIKE ?",
            arrayOf("%$keyword%", "%$keyword%"),
            null, null, null
        )

        val profileBuilder = StringBuilder()
        var hasData = false

        while (cursor.moveToNext()) {
            val category = cursor.getString(0)
            val info = cursor.getString(1)
            profileBuilder.append("- $category: $info\n")
            hasData = true
        }
        cursor.close()

        if (hasData) {
            "El usuario ha registrado esto sobre sí mismo:\n$profileBuilder"
        } else {
            ""
        }
    }


    // Agrega nuevo contenido al registro actual (el de los últimos 7 minutos)
    // Devuelve el texto acumulado (raw) de ese registro para poder resumirlo.
    suspend fun appendToCurrentChunk(startTime: Long, addedContent: String): String = withContext(Dispatchers.IO) {
        MonLogger.d(TAG, "appendToCurrentChunk_addedContent $addedContent")

        MonLogger.d(TAG, "appendToCurrentChunk: Iniciando guardado de mensaje en chunk $startTime")
        val db = dbHelper.writableDatabase
        
        val cursor = db.query(SummaryDbHelper.TABLE_SUMMARIES, null, "${SummaryDbHelper.COLUMN_START_TIME} = ?", arrayOf(startTime.toString()), null, null, null)
        
        var id = -1L
        var existingContent = ""
        if (cursor.moveToFirst()) {
            id = cursor.getLong(cursor.getColumnIndexOrThrow(SummaryDbHelper.COLUMN_ID))
            existingContent = cursor.getString(cursor.getColumnIndexOrThrow(SummaryDbHelper.COLUMN_RAW_CONTENT))
            MonLogger.d(TAG, "appendToCurrentChunk: Chunk encontrado. Agregando texto al ID $id")
        } else {
            MonLogger.d(TAG, "appendToCurrentChunk: Chunk no encontrado. Creando nuevo registro.")
        }
        cursor.close()

        val newContent = if (existingContent.isEmpty()) addedContent else "$existingContent\n$addedContent"
        
        val values = ContentValues().apply {
            put(SummaryDbHelper.COLUMN_START_TIME, startTime)
            put(SummaryDbHelper.COLUMN_RAW_CONTENT, newContent)
        }

        if (id == -1L) {
            val insertedId = db.insert(SummaryDbHelper.TABLE_SUMMARIES, null, values)
            MonLogger.d(TAG, "appendToCurrentChunk: Registro insertado exitosamente con ID $insertedId")
        } else {
            val rowsUpdated = db.update(SummaryDbHelper.TABLE_SUMMARIES, values, "${SummaryDbHelper.COLUMN_ID} = ?", arrayOf(id.toString()))
            MonLogger.d(TAG, "appendToCurrentChunk: Registro actualizado exitosamente. Filas afectadas: $rowsUpdated")
        }
        
        return@withContext newContent
    }



    // ==============================================
    // MEMORIA DE PERFIL (LILY)
    // ==============================================

    /**
     * Guarda o actualiza un dato del perfil de Lily.
     * Si ya existe esa categoría (ej. "gustos"), añade la nueva info a la existente.
     */
    suspend fun upsertProfileData(category: String, newInfo: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase

        // 1. Verificar si ya existe esa categoría
        val cursor = db.query(
            SummaryDbHelper.TABLE_LILY_PROFILE,
            arrayOf(SummaryDbHelper.COLUMN_PROFILE_INFO),
            "${SummaryDbHelper.COLUMN_PROFILE_CATEGORY} = ?",
            arrayOf(category),
            null, null, null
        )

        var existingInfo = ""
        val exists = cursor.moveToFirst()
        if (exists) {
            existingInfo = cursor.getString(0)
        }
        cursor.close()

        // 2. Si ya existe y el nuevo dato no está contenido, concatenarlo.
        val finalInfo = if (exists) {
            if (existingInfo.contains(newInfo, ignoreCase = true)) {
                existingInfo // No duplicar
            } else {
                "$existingInfo. $newInfo"
            }
        } else {
            newInfo
        }

        // 3. Guardar en SQLite
        val values = ContentValues().apply {
            put(SummaryDbHelper.COLUMN_PROFILE_CATEGORY, category)
            put(SummaryDbHelper.COLUMN_PROFILE_INFO, finalInfo)
        }

        db.insertWithOnConflict(
            SummaryDbHelper.TABLE_LILY_PROFILE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
        MonLogger.d(TAG, "Perfil de Lily actualizado. Categoría: [$category] -> Info: [$finalInfo]")
    }

    /**
     * Obtiene todo el perfil de Lily formateado como texto para inyectarlo al System Prompt.
     */
    suspend fun getFullProfileContext(): String = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            SummaryDbHelper.TABLE_LILY_PROFILE,
            null, null, null, null, null, null
        )

        val profileBuilder = StringBuilder()
        var hasData = false

        while (cursor.moveToNext()) {
            val category = cursor.getString(cursor.getColumnIndexOrThrow(SummaryDbHelper.COLUMN_PROFILE_CATEGORY))
            val info = cursor.getString(cursor.getColumnIndexOrThrow(SummaryDbHelper.COLUMN_PROFILE_INFO))
            profileBuilder.append("- $category: $info\n")
            hasData = true
        }
        cursor.close()

        if (hasData) {
            "INFORMACIÓN PERSONAL DE LILY:\n$profileBuilder"
        } else {
            ""
        }
    }



    suspend fun saveSummary(startTime: Long, summary: String) = withContext(Dispatchers.IO) {
        MonLogger.d(TAG, "saveSummary: Guardando resumen para chunk $startTime.\nContenido del resumen a guardar:\n$summary")
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(SummaryDbHelper.COLUMN_SUMMARY_TEXT, summary)
        }
        val rows = db.update(SummaryDbHelper.TABLE_SUMMARIES, values, "${SummaryDbHelper.COLUMN_START_TIME} = ?", arrayOf(startTime.toString()))
        MonLogger.d(TAG, "saveSummary: Resumen guardado. Filas afectadas: $rows")
    }

    // Obtiene resúmenes que coincidan estrictamente con un rango de tiempo
    suspend fun getSummariesFromTimeRange(startTime: Long, endTime: Long, limit: Int = 10): String = withContext(Dispatchers.IO) {
        MonLogger.d(TAG, "getSummariesFromTimeRange: Buscando resúmenes entre $startTime y $endTime")
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            SummaryDbHelper.TABLE_SUMMARIES,
            arrayOf(SummaryDbHelper.COLUMN_START_TIME, SummaryDbHelper.COLUMN_SUMMARY_TEXT),
            "${SummaryDbHelper.COLUMN_START_TIME} >= ? AND ${SummaryDbHelper.COLUMN_START_TIME} <= ?",
            arrayOf(startTime.toString(), endTime.toString()),
            null, null,
            "${SummaryDbHelper.COLUMN_START_TIME} DESC",
            limit.toString()
        )
        
        val recentSummaries = mutableListOf<String>()
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "MX"))
        var total = 0
        
        while(cursor.moveToNext()) {
            val sTime = cursor.getLong(0)
            val text = cursor.getString(1)
            if (!text.isNullOrBlank()) {
                recentSummaries.add("[${dateFormat.format(Date(sTime))}] $text\n")
                total++
            }
        }
        cursor.close()
        recentSummaries.reverse()
        
        val builder = java.lang.StringBuilder()
        recentSummaries.forEach { builder.append(it) }
        MonLogger.d(TAG, "getSummariesFromTimeRange: Se encontraron $total resúmenes en ese lapso.")
        builder.toString()
    }

    // Obtiene resúmenes anteriores para darle contexto al bot sin saturar la API
    suspend fun getAllSummaries(limit: Int = 3): String = withContext(Dispatchers.IO) {
        MonLogger.d(TAG, "getAllSummaries: Extrayendo los últimos resúmenes de sesiones previas de la BD...")
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            SummaryDbHelper.TABLE_SUMMARIES, 
            arrayOf(SummaryDbHelper.COLUMN_START_TIME, SummaryDbHelper.COLUMN_SUMMARY_TEXT), 
            null, null, null, null, 
            "${SummaryDbHelper.COLUMN_START_TIME} DESC", // Ordenar del más nuevo al más viejo
            limit.toString() 
        )
        
        val recentSummaries = mutableListOf<String>()
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "MX"))
        var total = 0
        
        while(cursor.moveToNext()) {
            val startTime = cursor.getLong(0)
            val text = cursor.getString(1)
            if (!text.isNullOrBlank()) {
                val dateString = dateFormat.format(Date(startTime))
                // Lo guardamos en una lista temporal
                recentSummaries.add("[$dateString] $text\n")
                total++
            }
        }
        cursor.close()
        
        // Como los trajimos de más nuevo a más viejo (DESC), tenemos que voltear la lista
        // para que Gemini los lea cronológicamente (del más viejo al más nuevo dentro de esos 7)
        recentSummaries.reverse()
        
        val builder = java.lang.StringBuilder()
        recentSummaries.forEach { builder.append(it) }
        
        val finalSummaries = builder.toString()
        MonLogger.d(TAG, "getAllSummaries: Se obtuvieron los últimos $total chunks anteriores.\nContenido extraído de SQLite:\n$finalSummaries")
        finalSummaries
    }

    suspend fun saveChatMessage(sender: String, message: String) = withContext(Dispatchers.IO) {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(SummaryDbHelper.COLUMN_SENDER, sender)
            put(SummaryDbHelper.COLUMN_MESSAGE, message)
            put(SummaryDbHelper.COLUMN_TIMESTAMP, System.currentTimeMillis())
        }
        db.insert(SummaryDbHelper.TABLE_CHAT_HISTORY, null, values)
    }

    suspend fun getLastChatMessages(limit: Int = 12): List<String> = withContext(Dispatchers.IO) {
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            SummaryDbHelper.TABLE_CHAT_HISTORY,
            arrayOf(SummaryDbHelper.COLUMN_MESSAGE),
            null, null, null, null,
            "${SummaryDbHelper.COLUMN_TIMESTAMP} DESC",
            limit.toString()
        )
        val result = mutableListOf<String>()
        while (cursor.moveToNext()) {
            result.add(cursor.getString(0))
        }
        cursor.close()
        result.reverse()
        result
    }

    // Busca mensajes de chat en una ventana de tiempo específica (ej. entre ayer y hoy)
    suspend fun getChatMessagesFromTimeRange(startTime: Long, endTime: Long, limit: Int = 15): List<String> = withContext(Dispatchers.IO) {
        MonLogger.d(TAG, "getChatMessagesFromTimeRange: Buscando chat entre $startTime y $endTime")
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            SummaryDbHelper.TABLE_CHAT_HISTORY,
            arrayOf(SummaryDbHelper.COLUMN_MESSAGE),
            "${SummaryDbHelper.COLUMN_TIMESTAMP} >= ? AND ${SummaryDbHelper.COLUMN_TIMESTAMP} <= ?",
            arrayOf(startTime.toString(), endTime.toString()),
            null, null,
            "${SummaryDbHelper.COLUMN_TIMESTAMP} DESC",
            limit.toString()
        )
        val result = mutableListOf<String>()
        while (cursor.moveToNext()) {
            result.add(cursor.getString(0))
        }
        cursor.close()
        result.reverse()
        MonLogger.d(TAG, "getChatMessagesFromTimeRange: Se encontraron ${result.size} mensajes en ese lapso.")
        result
    }

    // Busca resúmenes que coincidan con un texto o palabra clave (ej. usando LIKE)
    suspend fun searchSummariesByKeyword(keyword: String, limit: Int = 10): String = withContext(Dispatchers.IO) {
        MonLogger.d(TAG, "searchSummariesByKeyword: Buscando la palabra clave '$keyword' en los resúmenes")
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            SummaryDbHelper.TABLE_SUMMARIES,
            arrayOf(SummaryDbHelper.COLUMN_START_TIME, SummaryDbHelper.COLUMN_SUMMARY_TEXT),
            "${SummaryDbHelper.COLUMN_SUMMARY_TEXT} LIKE ?",
            arrayOf("%$keyword%"),
            null, null,
            "${SummaryDbHelper.COLUMN_START_TIME} DESC",
            limit.toString()
        )
        
        val recentSummaries = mutableListOf<String>()
        val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "MX"))
        var total = 0
        
        while(cursor.moveToNext()) {
            val sTime = cursor.getLong(0)
            val text = cursor.getString(1)
            if (!text.isNullOrBlank()) {
                val dateString = dateFormat.format(Date(sTime))
                recentSummaries.add("[$dateString] $text\n")
                total++
            }
        }
        cursor.close()
        recentSummaries.reverse()
        
        val builder = java.lang.StringBuilder()
        recentSummaries.forEach { builder.append(it) }
        
        val finalSummaries = builder.toString()
        MonLogger.d(TAG, "searchSummariesByKeyword: Se encontraron $total resúmenes relacionados a '$keyword'.")
        finalSummaries
    }

    // Busca mensajes del chat que coincidan con un texto clave
    suspend fun searchChatHistoryByKeyword(keyword: String, limit: Int = 15): List<String> = withContext(Dispatchers.IO) {
        MonLogger.d(TAG, "searchChatHistoryByKeyword: Buscando la palabra clave '$keyword' en el historial textual")
        val db = dbHelper.readableDatabase
        val cursor = db.query(
            SummaryDbHelper.TABLE_CHAT_HISTORY,
            arrayOf(SummaryDbHelper.COLUMN_MESSAGE),
            "${SummaryDbHelper.COLUMN_MESSAGE} LIKE ?",
            arrayOf("%$keyword%"),
            null, null,
            "${SummaryDbHelper.COLUMN_TIMESTAMP} DESC",
            limit.toString()
        )
        val result = mutableListOf<String>()
        while (cursor.moveToNext()) {
            result.add(cursor.getString(0))
        }
        cursor.close()
        result.reverse()
        result
    }
}