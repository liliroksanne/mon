package com.lilyroksanne.mon.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lilyroksanne.mon.util.MonLogger

class SummaryDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val TAG = "SummaryDbHelper"

    companion object {
        const val DATABASE_VERSION = 2
        const val DATABASE_NAME = "MonMemory.db"

        const val TABLE_SUMMARIES = "summaries"
        const val COLUMN_ID = "_id"
        const val COLUMN_START_TIME = "start_time"
        const val COLUMN_RAW_CONTENT = "raw_content"
        const val COLUMN_SUMMARY_TEXT = "summary_text"
        
        // Tabla para el historial del chat visible
        const val TABLE_CHAT_HISTORY = "chat_history"
        const val COLUMN_SENDER = "sender"
        const val COLUMN_MESSAGE = "message"
        const val COLUMN_TIMESTAMP = "timestamp"
    }

    override fun onCreate(db: SQLiteDatabase) {
        MonLogger.d(TAG, "onCreate: Creando base de datos SQLite '$DATABASE_NAME' versión $DATABASE_VERSION")
        val createTable = """
            CREATE TABLE $TABLE_SUMMARIES (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_START_TIME INTEGER,
                $COLUMN_RAW_CONTENT TEXT,
                $COLUMN_SUMMARY_TEXT TEXT
            )
        """.trimIndent()
        
        val createTableChat = """
            CREATE TABLE $TABLE_CHAT_HISTORY (
                $COLUMN_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COLUMN_SENDER TEXT,
                $COLUMN_MESSAGE TEXT,
                $COLUMN_TIMESTAMP INTEGER
            )
        """.trimIndent()
        
        db.execSQL(createTable)
        db.execSQL(createTableChat)
        MonLogger.d(TAG, "onCreate: Tabla '$TABLE_SUMMARIES' y '$TABLE_CHAT_HISTORY' creadas con éxito.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        MonLogger.d(TAG, "onUpgrade: Actualizando base de datos de versión $oldVersion a $newVersion. Se eliminarán los datos viejos.")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SUMMARIES")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_CHAT_HISTORY")
        onCreate(db)
    }
}
