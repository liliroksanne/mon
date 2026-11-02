package com.lilyroksanne.mon.util

import android.content.Context
import android.net.Uri
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
 * Organiza los logs creando una carpeta por día (YYYY-MM-DD) y un archivo independiente por cada corrida (mon_log_HH-mm-ss.txt).
 * Añade colores dinámicos en Logcat según la clase que emite el log.
 */
object MonLogger {
    private const val GLOBAL_TAG = "MonLog"
    private var logFile: File? = null
    private var appContext: Context? = null
    private var customFolderUri: Uri? = null

    private var currentDayFolderName: String = ""
    private var currentRunFileName: String = ""

    // ================= COLOR CONFIGURATION =================
    private val colorMap = mutableMapOf<String, String>()

    // Lista de colores ANSI amigables para Logcat
    private val ansiColors = listOf(
        "\u001B[31m", // Rojo
        "\u001B[32m", // Verde
        "\u001B[33m", // Amarillo
        "\u001B[34m", // Azul
        "\u001B[35m", // Magenta
        "\u001B[36m", // Cyan
        "\u001B[90m", // Gris oscuro
        "\u001B[91m", // Rojo claro
        "\u001B[92m", // Verde claro
        "\u001B[93m", // Amarillo claro
        "\u001B[94m", // Azul claro
        "\u001B[95m", // Magenta claro
        "\u001B[96m"  // Cyan claro
    )
    private const val ANSI_RESET = "\u001B[0m"
    private var colorIndex = 0

    private fun getColorForClass(className: String): String {
        return colorMap.getOrPut(className) {
            val color = ansiColors[colorIndex % ansiColors.size]
            colorIndex++
            color
        }
    }
    // =======================================================

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    private val scope = CoroutineScope(Dispatchers.IO)
    private val mutex = Mutex()

    /**
     * Inicializa el logger para que cree la estructura por día y por corrida.
     */
    fun init(context: Context) {
        appContext = context.applicationContext

        val now = Date()
        currentDayFolderName = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(now)
        currentRunFileName = "mon_log_" + SimpleDateFormat("HH-mm-ss", Locale.getDefault()).format(now) + ".txt"

        // 1. Obtener directorio base de logs de la app
        val baseDir = context.getExternalFilesDir("Logs") ?: File(context.filesDir, "Logs")

        // 2. Crear subcarpeta por día: ej. "2026-10-31"
        val dayFolder = File(baseDir, currentDayFolderName)
        if (!dayFolder.exists()) {
            dayFolder.mkdirs()
        }

        // 3. Crear archivo de log único para esta corrida: ej. "mon_log_18-12-27.txt"
        logFile = File(dayFolder, currentRunFileName)

        // Cargar URI guardada previamente si existe en SharedPreferences
        val prefs = context.getSharedPreferences("MonPrefs", Context.MODE_PRIVATE)
        val savedUri = prefs.getString("custom_log_folder_uri", null)
        if (!savedUri.isNullOrEmpty()) {
            setCustomLogFolder(savedUri)
        }

        d("MonLogger", "==================== NUEVA CORRIDA DE LA APP ====================")
        d("MonLogger", "Logger inicializado. Guardando log de esta corrida en: ${logFile?.absolutePath}")
    }

    fun setCustomLogFolder(uriString: String) {
        try {
            customFolderUri = Uri.parse(uriString)
            d("MonLogger", "Carpeta SAF de logs establecida: $uriString")
        } catch (e: Exception) {
            Log.e(GLOBAL_TAG, "Error al parsear URI de SAF", e)
        }
    }

    private fun writeToFile(level: String, className: String, message: String, throwable: Throwable? = null) {
        val targetFile = logFile ?: return

        scope.launch {
            mutex.withLock {
                try {
                    val timestamp = dateFormat.format(Date())
                    // Nota: No guardamos los códigos de color ANSI en el archivo de texto físico para evitar caracteres raros.
                    val logLine = "$timestamp $level/[$className]: $message" + (throwable?.let { "\n${Log.getStackTraceString(it)}" } ?: "") + "\n"

                    // 1. Guardar en el archivo local de esta corrida
                    FileWriter(targetFile, true).use { writer ->
                        writer.append(logLine)
                    }

                    // 2. Guardar en SAF (si el usuario tiene configurada una carpeta externa elegida)
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

            // 1. Crear/Buscar la carpeta del día directamente (sin carpeta de modelo de teléfono)
            var dayFolder = tree.findFile(currentDayFolderName)
            if (dayFolder == null || !dayFolder.isDirectory) {
                dayFolder = tree.createDirectory(currentDayFolderName)
            }

            // 2. Crear/Buscar el archivo de log para esta corrida específica
            var logDoc = dayFolder?.findFile(currentRunFileName)
            if (logDoc == null) {
                logDoc = dayFolder?.createFile("text/plain", currentRunFileName)
            }

            logDoc?.uri?.let { docUri ->
                ctx.contentResolver.openOutputStream(docUri, "wa")?.use { out ->
                    out.write(logLine.toByteArray())
                }
            }
        } catch (e: Exception) {
            Log.e(GLOBAL_TAG, "Error escribiendo en carpeta SAF", e)
        }
    }

    fun d(className: String, message: String) {
        val color = getColorForClass(className)
        Log.d(GLOBAL_TAG, "$color[$className] $message$ANSI_RESET")
        writeToFile("DEBUG", className, message)
    }

    fun w(className: String, message: String) {
        val color = getColorForClass(className)
        Log.w(GLOBAL_TAG, "$color[$className] $message$ANSI_RESET")
        writeToFile("WARNING", className, message)
    }

    fun i(className: String, message: String) {
        val color = getColorForClass(className)
        Log.i(GLOBAL_TAG, "$color[$className] $message$ANSI_RESET")
        writeToFile("INFO", className, message)
    }

    fun e(className: String, message: String, throwable: Throwable? = null) {
        val color = getColorForClass(className)
        if (throwable != null) {
            Log.e(GLOBAL_TAG, "$color[$className] $message$ANSI_RESET", throwable)
        } else {
            Log.e(GLOBAL_TAG, "$color[$className] $message$ANSI_RESET")
        }
        writeToFile("ERROR", className, message, throwable)
    }
}