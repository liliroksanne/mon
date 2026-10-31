package com.lilyroksanne.mon.util

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Módulo centralizado para imprimir logs de la app.
 * Utiliza el tag general "MonLog" para filtrar en Logcat
 * y guarda un registro en un archivo de texto dentro del almacenamiento de la app y SAF.
 */
object MonLogger {
    private const val GLOBAL_TAG = "MonLog"
    private var logFile: File? = null
    private var appContext: Context? = null
    private var customFolderUri: Uri? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

    // Bandera para asegurarnos de marcar el inicio de una nueva corrida de la app en el archivo
    private var isFirstLogThisRun = true

    private val scope = CoroutineScope(Dispatchers.IO)
    private val mutex = Mutex()

    /**
     * Inicializa el logger para que pueda escribir en un archivo local en el dispositivo.
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        val directory = context.getExternalFilesDir(null) ?: context.filesDir
        logFile = File(directory, "mon_device_logs.txt")

        // Limpiamos el archivo si supera los 2MB para no saturar
        if (logFile?.exists() == true && logFile!!.length() > 2 * 1024 * 1024) {
            logFile?.delete()
        }

        isFirstLogThisRun = true
        d("MonLogger", "==================== NUEVA CORRIDA DE LA APP ====================")
        d("MonLogger", "Logger inicializado. Guardando en: ${logFile?.absolutePath}")
    }

    fun setCustomLogFolder(uriString: String) {
        try {
            customFolderUri = Uri.parse(uriString)
            d("MonLogger", "Carpeta customizada de logs establecida: $uriString")
        } catch (e: Exception) {
            Log.e(GLOBAL_TAG, "Error al parsear URI", e)
        }
    }

    private fun writeToFile(level: String, className: String, message: String, throwable: Throwable? = null) {
        if (logFile == null) return // Aún no se inicializa con el contexto

        scope.launch {
            mutex.withLock {
                try {
                    val timestamp = dateFormat.format(Date())
                    val logLine = "$timestamp $level/[$className]: $message" + (throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: "") + "\n"

                    // 1. Guardar en el log local de la app
                    FileWriter(logFile, true).use { writer ->
                        writer.append(logLine)
                    }

                    // 2. Guardar en la carpeta que el usuario escogió (SAF)
                    writeToSafLog(logLine)
                } catch (e: Exception) {
                    Log.e(GLOBAL_TAG, "Error al escribir en el archivo de logs", e)
                }
            }
        }
    }

    private fun writeToSafLog(logLine: String) {
        val uri = customFolderUri ?: return
        val ctx = appContext ?: return
        try {
            val tree = DocumentFile.fromTreeUri(ctx, uri) ?: return

            // Reemplazar espacios y caracteres raros del modelo del dispositivo
            val deviceName = Build.MODEL.replace(Regex("[^a-zA-Z0-9_-]"), "_")

            var deviceFolder = tree.findFile(deviceName)
            if (deviceFolder == null) {
                deviceFolder = tree.createDirectory(deviceName)
            }

            // Usamos un nombre de archivo fijo por dispositivo para que persista y se acumule en la misma corrida/historial,
            // en lugar de cambiar con la fecha exacta (lo que creaba múltiples archivos diarios).
            val logFileName = "mon_session_log.log"
            var logDoc = deviceFolder?.findFile(logFileName)

            if (logDoc == null) {
                logDoc = deviceFolder?.createFile("text/plain", logFileName)
            }

            logDoc?.uri?.let { docUri ->
                // Si es la primera vez que escribimos en esta ejecución, podemos inyectar una línea divisoria de sesión opcional
                val contentToWrite = if (isFirstLogThisRun) {
                    isFirstLogThisRun = false
                    "\n\n--- [ NUEVA SESIÓN / CORRIDA DE APP: ${dateFormat.format(Date())} ] ---\n$logLine"
                } else {
                    logLine
                }

                // "wa" = write append (escribe al final del mismo archivo sin sobrescribirlo)
                ctx.contentResolver.openOutputStream(docUri, "wa")?.use { out ->
                    out.write(contentToWrite.toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.e(GLOBAL_TAG, "Error escribiendo a la carpeta seleccionada", e)
        }
    }

    fun d(className: String, message: String) {
        Log.d(GLOBAL_TAG, "[$className] $message")
        writeToFile("DEBUG", className, message)
    }
    fun w(className: String, message: String) {
        Log.d(GLOBAL_TAG, "[$className] $message")
        writeToFile("WARNING", className, message)
    }
    fun i(className: String, message: String) {
        Log.i(GLOBAL_TAG, "[$className] $message")
        writeToFile("INFO", className, message)
    }

    fun e(className: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(GLOBAL_TAG, "[$className] $message", throwable)
        } else {
            Log.e(GLOBAL_TAG, "[$className] $message")
        }
        writeToFile("ERROR", className, message, throwable)
    }
}