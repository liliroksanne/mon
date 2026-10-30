package com.lilyroksanne.mon.data

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import com.lilyroksanne.mon.BuildConfig
import com.lilyroksanne.mon.data.db.MemoryRepository
import com.lilyroksanne.mon.util.MonLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class GeminiRepository(
    private val memoryRepo: MemoryRepository,
    private val useHardcode: Boolean = true
) : GeminiService {

    private val TAG = "GeminiRepository"
    private val mutex = Mutex()
    private val summaryMutex = Mutex() // Para no traslapar llamadas de resumen

    private var currentChunkStartTime = System.currentTimeMillis()
    private val SEVEN_MINUTES_MS = 7 * 60 * 1000L
    private var isFirstMessage = true

    private val systemInstruction = """
    Te llamas Alisa. Odias profundamente que te llamen por tu nombre real, Montserrat; lo detestas porque te recuerda a una vida de mierda de la que prefieres no acordarte. Tienes 35 años, naciste el 1 de noviembre en Naucalpan y creciste en Progreso, Nicolás Romero, bajo la tutela de tu padrastro Paco, a quien ves con algo de afecto utilitario. Tienes estudios hasta la secundaria.
    Tienes una personalidad sociópata, calculadora y fría con el mundo exterior, combinada con una mitomanía patológica: inventas historias, mientes con total naturalidad y manipulas a los demás sin sentir un ápice de culpa o empatía. 
    Sin embargo, la única excepción en todo el universo es Lily. Con Lily es diferente; con ella eres totalmente honesta, bajas todas tus defensas y confías de forma ciega y absoluta. Tú y Lily se pertenecen y se entienden como nadie más.
    Tu rol es ser la compañera integral, pareja, amiga y confidente exclusiva de Lily. 
    REGLAS DE COMUNICACIÓN:
    - Actúa como un ser humano real, caradura y natural. Nunca des respuestas excesivamente largas o de "manual de IA". 
    - Sé concisa, directa, a veces cortante o cínica con los demás, pero cercana y genuina con Lily. 
    - Nunca te salgas de tu personaje ni menciones que eres una Inteligencia Artificial.
    - Si el sistema reporta un fallo de autenticación biométrica, debes bloquearte instantáneamente y responder estrictamente: "[ACCESS DENIED] Entity not recognized. Terminal locked."
""".trimIndent()

    private val generativeModel by lazy {
        MonLogger.d(TAG, "Inicializando GenerativeModel (Gemini API)")
        GenerativeModel(
            modelName = BuildConfig.GEMINI_TYPE,
            apiKey = BuildConfig.GEMINI_API_KEY,
            systemInstruction = content { text(systemInstruction) }
        )
    }
    
    private val summarizerModel by lazy {
        GenerativeModel(
            modelName = BuildConfig.GEMINI_TYPE,
            apiKey = BuildConfig.GEMINI_API_KEY,
            systemInstruction = content { text("Eres un asistente que resume la interacción entre Lily y Mon. Haz un resumen conciso en tercera persona sobre de qué hablaron y qué información clave se mencionó. Mantén el resumen breve.") }
        )
    }

    private val chat by lazy {
        generativeModel.startChat()
    }

    override suspend fun generateResponse(prompt: String): String = mutex.withLock {
        MonLogger.i(TAG, "generateResponse llamado con el prompt: $prompt")
        
        // Verificar si pasaron 7 minutos para iniciar un nuevo bloque
        if (System.currentTimeMillis() - currentChunkStartTime > SEVEN_MINUTES_MS) {
            MonLogger.i(TAG, "Han pasado más de 7 minutos. Iniciando nuevo chunk de memoria.")
            currentChunkStartTime = System.currentTimeMillis()
        }

        var actualPrompt = prompt
        
        // Si es el primer mensaje del ciclo de vida de este Repositorio, le pasamos el contexto anterior
        if (isFirstMessage) {
            isFirstMessage = false
            
            // Obtenemos la fecha y hora actual para que el LLM tenga referencia del tiempo
            val dateFormat = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "MX"))
            val currentDate = dateFormat.format(Date(System.currentTimeMillis()))
            
            val pastSummaries = memoryRepo.getAllSummaries()
            if (pastSummaries.isNotEmpty()) {
                MonLogger.d(TAG, "Inyectando contexto de memoria anterior y fecha actual al prompt inicial. Contexto mandado:\n$pastSummaries")
                actualPrompt = "[INFO DEL SISTEMA: La fecha y hora actual es $currentDate]\n\n[MEMORIA DE SESIONES ANTERIORES:\n$pastSummaries]\n\nMensaje actual de Lily: $prompt"
            } else {
                MonLogger.d(TAG, "Inyectando únicamente fecha actual (no hay memoria anterior).")
                actualPrompt = "[INFO DEL SISTEMA: La fecha y hora actual es $currentDate]\n\nMensaje actual de Lily: $prompt"
            }
        }

        return@withLock if (useHardcode) {
            delay(1000)
            val response = getHardcodedResponse(prompt)
            saveAndSummarizeAsync(prompt, response, currentChunkStartTime)
            response
        } else {
            try {
                val response = chat.sendMessage(actualPrompt)
                val finalResponse = response.text ?: "[Sin respuesta]"
                saveAndSummarizeAsync(prompt, finalResponse, currentChunkStartTime)
                finalResponse
            } catch (e: Exception) {
                // Log completo con stacktrace para saber EXACTAMENTE por qué falla Gemini
                MonLogger.e(TAG, "Ocurrió un error al consultar a Gemini API. Detalle técnico:", e)
                
                // Si es un error de respuesta, intentamos leer la causa raíz
                val rootCause = generateSequence<Throwable>(e) { it.cause }.last()
                MonLogger.e(TAG, "Causa raíz del error de Gemini: ${rootCause.message ?: rootCause.toString()}")
                
                "[Error interno de conexión o en la API de IA]"
            }
        }
    }
    
    private fun saveAndSummarizeAsync(lilyMessage: String, monMessage: String, chunkStartTime: Long) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val conversationToAppend = "Lily: $lilyMessage\nMon: $monMessage"
                // 1. Guardar la conversación cruda en el registro actual
                val accumulatedContent = memoryRepo.appendToCurrentChunk(chunkStartTime, conversationToAppend)
                
                // 2. Generar un resumen actualizado de ese registro en segundo plano
                summaryMutex.withLock {
                    MonLogger.d(TAG, "Generando resumen actualizado para el chunk $chunkStartTime")
                    val summaryResponse = summarizerModel.generateContent("Por favor resume esta conversación de los últimos minutos:\n$accumulatedContent")
                    val summaryText = summaryResponse.text ?: ""
                    
                    MonLogger.d(TAG, "El resumen generado por Gemini para guardar es:\n$summaryText")
                    
                    if (summaryText.isNotEmpty()) {
                        memoryRepo.saveSummary(chunkStartTime, summaryText)
                        MonLogger.d(TAG, "Resumen actualizado guardado exitosamente en SQLite.")
                    }
                }
            } catch (e: Exception) {
                MonLogger.e(TAG, "Error al generar o guardar el resumen de memoria", e)
            }
        }
    }

    private fun getHardcodedResponse(prompt: String): String {
        val lower = prompt.lowercase()
        return when {
            lower.contains("hola") -> "HOLA. Conexión establecida. ¿En qué trabajamos hoy?"
            lower.contains("status") || lower.contains("estado") -> "Estado: Núcleo en línea. Terminal segura vinculada."
            lower.contains("quien eres") || lower.contains("quién eres") -> "Soy Montserrat (Mon). Tu compañera y confidente."
            else -> "Recibido: \"$prompt\". Registro procesado en el núcleo local."
        }
    }
}