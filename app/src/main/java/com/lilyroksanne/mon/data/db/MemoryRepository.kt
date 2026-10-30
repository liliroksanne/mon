package com.lilyroksanne.mon.data.db

import android.content.ContentValues
import android.content.Context
import com.lilyroksanne.mon.util.MonLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MemoryRepository(context: Context) {
    private val TAG = "MemoryRepository"
    private val dbHelper = SummaryDbHelper(context)

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

    suspend fun saveSummary(startTime: Long, summary: String) = withContext(Dispatchers.IO) {
        MonLogger.d(TAG, "saveSummary: Guardando resumen para chunk $startTime.\nContenido del resumen a guardar:\n$summary")
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put(SummaryDbHelper.COLUMN_SUMMARY_TEXT, summary)
        }
        val rows = db.update(SummaryDbHelper.TABLE_SUMMARIES, values, "${SummaryDbHelper.COLUMN_START_TIME} = ?", arrayOf(startTime.toString()))
        MonLogger.d(TAG, "saveSummary: Resumen guardado. Filas afectadas: $rows")
    }

    // Obtiene solo los ÚLTIMOS 3 resúmenes anteriores para darle contexto inicial al bot sin saturar la API
    suspend fun getAllSummaries(): String = withContext(Dispatchers.IO) {
        MonLogger.d(TAG, "getAllSummaries: Extrayendo los últimos resúmenes de sesiones previas de la BD...")
        val db = dbHelper.readableDatabase
        // Traemos también la columna START_TIME, pero ordenados de forma descendente y limitados a 3 (más corto)
        val cursor = db.query(
            SummaryDbHelper.TABLE_SUMMARIES, 
            arrayOf(SummaryDbHelper.COLUMN_START_TIME, SummaryDbHelper.COLUMN_SUMMARY_TEXT), 
            null, null, null, null, 
            "${SummaryDbHelper.COLUMN_START_TIME} DESC", // Ordenar del más nuevo al más viejo
            "3" // REDUCIDO A SOLO LOS ÚLTIMOS 3 REGISTROS
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
}