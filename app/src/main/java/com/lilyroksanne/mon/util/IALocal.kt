package com.lilyroksanne.mon.util

import android.content.Context
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.flow.collect

class IALocal(private val context: Context) {
    private val TAG = "IALocal"
    private var generativeModel: GenerativeModel? = null

    init {
        try {
            MonLogger.d(TAG, "Inicializando ML Kit GenAI Prompt API (Gemini Nano local)")
            generativeModel = Generation.getClient()
            MonLogger.i(TAG, "Cliente de IA Local (Gemini Nano) instanciado correctamente.")
        } catch (e: Exception) {
            MonLogger.e(TAG, "Error al inicializar la IA local (Gemini Nano)", e)
        }
    }

    suspend fun summarizeLocally(accumulatedContent: String): String {
        MonLogger.d(TAG, "summarizeLocally: Solicitando resumen a IA local (Gemini Nano)...")

        if (accumulatedContent.isBlank()) {
            MonLogger.w(TAG, "summarizeLocally: El contenido acumulado está vacío.")
            return ""
        }

        val model = generativeModel
        if (model == null) {
            MonLogger.e(TAG, "summarizeLocally: El motor de IA local no está inicializado.")
            return ""
        }

        return try {
            val status = model.checkStatus()
            val statusDescription = getStatusDescription(status)
            MonLogger.d(TAG, "Estado de Gemini Nano local: $statusDescription")

            when (status) {
                FeatureStatus.AVAILABLE -> {
                    val prompt = "Resume la siguiente plática entre Lily y Mon brevemente en tercera persona, destacando los puntos clave:\n\n$accumulatedContent"
                    MonLogger.d(TAG, "Enviando prompt a Gemini Nano local...")

                    val request = generateContentRequest(TextPart(prompt)) {
                        temperature = 0.2f
                        maxOutputTokens = 256
                    }

                    val response = model.generateContent(request)
                    val cleanResponse = response.candidates.firstOrNull()?.text?.trim() ?: ""

                    MonLogger.i(TAG, "IA Local (Gemini Nano) generó el resumen con éxito: '$cleanResponse'")
                    cleanResponse
                }

                FeatureStatus.DOWNLOADABLE -> {
                    MonLogger.i(TAG, "Gemini Nano está disponible para descarga. Iniciando descarga en segundo plano...")
                    // Iniciar la descarga del modelo en el dispositivo
                    model.download().collect { downloadStatus ->
                        when (downloadStatus) {
                            is DownloadStatus.DownloadStarted -> MonLogger.d(TAG, "Descarga de Gemini Nano iniciada. Bytes a descargar: ${downloadStatus.bytesToDownload}")
                            is DownloadStatus.DownloadProgress -> MonLogger.d(TAG, "Progreso de descarga: ${downloadStatus.totalBytesDownloaded} bytes")
                            DownloadStatus.DownloadCompleted -> MonLogger.i(TAG, "¡Descarga de Gemini Nano completada exitosamente! Estará listo para el siguiente resumen.")
                            is DownloadStatus.DownloadFailed -> MonLogger.e(TAG, "Fallo al descargar Gemini Nano: ${downloadStatus.e.message}")
                        }
                    }
                    ""
                }

                FeatureStatus.DOWNLOADING -> {
                    MonLogger.i(TAG, "Gemini Nano se está descargando en segundo plano. Se usará el respaldo mientras finaliza.")
                    ""
                }

                else -> {
                    MonLogger.w(TAG, "Gemini Nano no está soportado en este dispositivo/emulador ($statusDescription). Se usará respaldo.")
                    ""
                }
            }
        } catch (e: Exception) {
            MonLogger.e(TAG, "Error durante la inferencia local con Gemini Nano", e)
            ""
        }
    }

    private fun getStatusDescription(status: Int): String {
        return when (status) {
            FeatureStatus.UNAVAILABLE -> "UNAVAILABLE (0): El dispositivo/emulador no soporta AICore o Gemini Nano."
            FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE (1): Gemini Nano es compatible pero requiere descargar los pesos del modelo."
            FeatureStatus.DOWNLOADING -> "DOWNLOADING (2): El modelo Gemini Nano se está descargando en este momento."
            FeatureStatus.AVAILABLE -> "AVAILABLE (3): Gemini Nano está completamente instalado y listo para usarse localmente."
            else -> "DESCONOCIDO ($status)"
        }
    }

    fun close() {
        try {
            generativeModel?.close()
        } catch (e: Exception) {
            MonLogger.e(TAG, "Error al cerrar GenerativeModel de IA local", e)
        }
    }

    /**
     * Extrae información personal de Lily (si la hay) en el mensaje.
     * Retorna el texto en formato "CATEGORÍA|VALOR" o "EMPTY".
     */
    suspend fun extractLilyProfile(message: String): String {
        val model = generativeModel ?: return "EMPTY"

        return try {
            if (model.checkStatus() == FeatureStatus.AVAILABLE) {
                val prompt = """
                    Analiza el siguiente mensaje de Lily. ¿Menciona algo nuevo o importante sobre su información personal (su nombre real, fecha de nacimiento, familia, gustos, disgustos, trabajo o aficiones)?
                    Si menciona algo personal, responde estrictamente en este formato: CATEGORÍA|VALOR
                    Ejemplo: gustos|Le gusta el color azul
                    Si NO menciona nada personal, responde estrictamente la palabra: EMPTY
                    
                    Mensaje de Lily: "$message"
                """.trimIndent()

                val request = generateContentRequest(TextPart(prompt)) {
                    temperature = 0.1f // Muy bajo para que sea estricto
                    maxOutputTokens = 128
                }

                val response = model.generateContent(request)
                response.candidates.firstOrNull()?.text?.trim() ?: "EMPTY"
            } else {
                "EMPTY"
            }
        } catch (e: Exception) {
            "EMPTY"
        }
    }


}