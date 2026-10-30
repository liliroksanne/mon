package com.lilyroksanne.mon.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.lilyroksanne.mon.util.MonLogger

class SummaryDbHelper(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    private val TAG = "SummaryDbHelper"

    companion object {
        const val DATABASE_VERSION = 1
        const val DATABASE_NAME = "MonMemory.db"

        const val TABLE_SUMMARIES = "summaries"
        const val COLUMN_ID = "_id"
        const val COLUMN_START_TIME = "start_time"
        const val COLUMN_RAW_CONTENT = "raw_content"
        const val COLUMN_SUMMARY_TEXT = "summary_text"
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
        db.execSQL(createTable)
        MonLogger.d(TAG, "onCreate: Tabla '$TABLE_SUMMARIES' creada con éxito.")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        MonLogger.d(TAG, "onUpgrade: Actualizando base de datos de versión $oldVersion a $newVersion. Se eliminarán los datos viejos.")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_SUMMARIES")
        onCreate(db)
    }
}
