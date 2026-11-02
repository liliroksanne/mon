package com.lilyroksanne.mon.util

import com.lilyroksanne.mon.data.db.MemoryRepository
import java.util.Calendar

data class SearchResult(
    val finalPrompt: String,
    val hasLocalContext: Boolean,
    val isMisspelled: Boolean,
    val quantity: Int
)

class ContextSearcher(
    private val memoryRepo: MemoryRepository
) {
    private val TAG = "ContextSearcher"

    // Instanciamos el buscador de perfil de usuario
    private val userProfileSearcher = UserProfileSearcher(memoryRepo)

    private val timeRegex = Regex("(?i)\\b(ayer|antier|mes|ms|ayr|antr|yesterday|days|semana|semana pasada|mes pasado|hace\\s+(\\d+|un|unos|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez)\\s+(d[ií]a[s]?|hora[s]?|minuto[s]?))\\b")

    // Un Regex semántico mucho más abarcador, para "te acuerdas que te dije que..."
    private val semanticRegex = Regex("(?i)\\b(te acuerdas|recordar|recuerdas que|recuerdas de|dije que|te dije|te cont[eé]|hablamos de|hablado de)\\b")

    suspend fun searchContext(prompt: String, currentDate: String): SearchResult {
        MonLogger.d(TAG, "searchContext: Analizando prompt de entrada -> '$prompt'")

        val timeMatch = timeRegex.find(prompt)
        val semanticMatch = semanticRegex.find(prompt)

        // 1. Buscamos primero en el perfil del usuario (nuevo requisito)
        val userProfileContext = userProfileSearcher.searchUserProfileContext(prompt)

        MonLogger.d(TAG, "searchContext: Coincidencias detectadas -> TimeMatch: ${timeMatch != null}, SemanticMatch: ${semanticMatch != null}, UserProfile: ${userProfileContext.isNotEmpty()}")

        var historicalSummaries = ""
        var targetedPastChat = ""
        var isMisspelled = false
        var quantity = 1

        if (timeMatch != null) {
            val rawTimeStr = timeMatch.value
            MonLogger.i(TAG, "searchContext: Regex de tiempo activado con valor: '$rawTimeStr'")

            // Le pasamos el prompt completo a parseTimeContext para que detecte si dice "en la noche", "mañana", etc.
            val parseResult = parseTimeContext(rawTimeStr.lowercase(), prompt)
            isMisspelled = parseResult.isMisspelled
            quantity = parseResult.quantity

            MonLogger.d(TAG, "searchContext: Resultados de parseo temporal -> Cantidad: $quantity, Mal escrito: $isMisspelled, StartTime: ${parseResult.startTime}, EndTime: ${parseResult.endTime}")

            MonLogger.d(TAG, "searchContext: Consultando resúmenes históricos en SQLite por rango de tiempo...")
            historicalSummaries = memoryRepo.getSummariesFromTimeRange(parseResult.startTime, parseResult.endTime, 10)
            MonLogger.d(TAG, "searchContext: Resúmenes históricos obtenidos (longitud: ${historicalSummaries.length})")

            MonLogger.d(TAG, "searchContext: Consultando mensajes de chat en SQLite por rango de tiempo...")
            targetedPastChat = memoryRepo.getChatMessagesFromTimeRange(parseResult.startTime, parseResult.endTime, 12).joinToString("\n")
            MonLogger.d(TAG, "searchContext: Extractos de chat obtenidos (longitud: ${targetedPastChat.length})")

        } else if (semanticMatch != null) {
            MonLogger.i(TAG, "searchContext: Regex semántico activado.")
            val mainKeyword = cleanSemanticPrompt(prompt)
            MonLogger.d(TAG, "searchContext: Keyword principal extraída para búsqueda semántica: '$mainKeyword'")

            if (mainKeyword.isNotEmpty()) {
                MonLogger.d(TAG, "searchContext: Buscando resúmenes por keyword en SQLite...")
                historicalSummaries = memoryRepo.searchSummariesByKeyword(mainKeyword, 5)
                MonLogger.d(TAG, "searchContext: Resúmenes semánticos encontrados (longitud: ${historicalSummaries.length})")

                MonLogger.d(TAG, "searchContext: Buscando historial de chat por keyword en SQLite...")
                targetedPastChat = memoryRepo.searchChatHistoryByKeyword(mainKeyword, 8).joinToString("\n")
                MonLogger.d(TAG, "searchContext: Historial de chat semántico encontrado (longitud: ${targetedPastChat.length})")
            } else {
                MonLogger.w(TAG, "searchContext: La keyword semántica quedó vacía. Obteniendo todos los resúmenes generales como respaldo.")
                historicalSummaries = memoryRepo.getAllSummaries()
            }
        } else {
            MonLogger.d(TAG, "searchContext: Ningún regex de tiempo o semántico hizo match.")
        }

        val hasLocalContext = historicalSummaries.isNotEmpty() || targetedPastChat.isNotEmpty() || userProfileContext.isNotEmpty()

        if (!hasLocalContext) {
            MonLogger.w(TAG, "searchContext: ⚠️ La base de datos local no arrojó resultados para esta consulta.")
            return SearchResult(
                finalPrompt = "[SysTime:$currentDate]\nLily:$prompt",
                hasLocalContext = false,
                isMisspelled = isMisspelled,
                quantity = quantity
            )
        }

        MonLogger.i(TAG, "searchContext: ✅ Contexto local encontrado exitosamente en SQLite. Construyendo prompt enriquecido.")

        // Concatenamos todo lo encontrado (Perfil de usuario + Memoria histórica + Chat)
        val contextBuilder = StringBuilder("[SysTime:$currentDate]\n")
        if (userProfileContext.isNotEmpty()) {
            contextBuilder.append("$userProfileContext\n")
        }
        if (historicalSummaries.isNotEmpty()) {
            contextBuilder.append("[Alisa recuerda este contexto de la memoria profunda:\n$historicalSummaries]\n")
        }
        if (targetedPastChat.isNotEmpty()) {
            contextBuilder.append("[Extracto del chat correspondiente:\n$targetedPastChat]\n")
        }
        contextBuilder.append("Lily:$prompt")

        return SearchResult(
            finalPrompt = contextBuilder.toString(),
            hasLocalContext = true,
            isMisspelled = isMisspelled,
            quantity = quantity
        )
    }

    private data class TimeParseData(val startTime: Long, val endTime: Long, val quantity: Int, val isMisspelled: Boolean)

    private fun parseTimeContext(matchedValue: String, fullPrompt: String): TimeParseData {
        MonLogger.d(TAG, "parseTimeContext: Analizando cadena temporal cruda: '$matchedValue'")

        val isMisspelled = matchedValue.contains("dias") && !matchedValue.contains("días")
        if (isMisspelled) {
            MonLogger.w(TAG, "parseTimeContext: [Ortografía detectada] Se escribió 'dias' sin tilde en '$matchedValue'")
        }

        val numberMatch = Regex("\\d+").find(matchedValue)
        val quantity = when {
            numberMatch != null -> numberMatch.value.toInt()
            matchedValue.contains("dos") -> 2
            matchedValue.contains("tres") -> 3
            matchedValue.contains("cuatro") -> 4
            matchedValue.contains("cinco") -> 5
            matchedValue.contains("seis") -> 6
            matchedValue.contains("siete") -> 7
            matchedValue.contains("ocho") -> 8
            matchedValue.contains("nueve") -> 9
            matchedValue.contains("diez") -> 10
            else -> 1
        }

        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val midnightToday = cal.timeInMillis

        val oneHour = 3600_000L
        val oneDay = 24 * oneHour
        var startTime = 0L
        var endTime = System.currentTimeMillis()

        when {
            matchedValue.contains("ayer") || matchedValue.contains("yesterday") || matchedValue.contains("ayr") || matchedValue.contains("aier") -> {
                startTime = midnightToday - oneDay
                endTime = midnightToday
            }
            matchedValue.contains("antier") -> {
                startTime = midnightToday - (2 * oneDay)
                endTime = midnightToday - oneDay
            }
            matchedValue.contains("semana") || matchedValue.contains("week") -> {
                startTime = midnightToday - (7 * oneDay)
                endTime = midnightToday
            }
            matchedValue.contains("mes") || matchedValue.contains("month") -> {
                startTime = midnightToday - (30 * oneDay)
                endTime = midnightToday
            }
            matchedValue.contains("días") || matchedValue.contains("days") || matchedValue.contains("dias") || matchedValue.contains("dia") -> {
                startTime = System.currentTimeMillis() - ((quantity + 1) * oneDay)
                endTime = System.currentTimeMillis() - (quantity * oneDay)
            }
            matchedValue.contains("horas") || matchedValue.contains("hour") || matchedValue.contains("hora") -> {
                startTime = System.currentTimeMillis() - ((quantity + 1) * oneHour)
                endTime = System.currentTimeMillis() - (quantity * oneHour)
            }
        }

        // Modificadores de parte del día ("en la mañana", "en la noche", etc.)
        val promptLower = fullPrompt.lowercase()
        if (promptLower.contains("mañana")) {
            // Desde las 6 AM hasta las 12 PM de ese día de inicio
            endTime = startTime + (12 * oneHour)
            startTime = startTime + (6 * oneHour)
            MonLogger.d(TAG, "parseTimeContext: Se aplicó modificador de 'mañana'")
        } else if (promptLower.contains("tarde")) {
            // Desde las 12 PM hasta las 7 PM (19h)
            endTime = startTime + (19 * oneHour)
            startTime = startTime + (12 * oneHour)
            MonLogger.d(TAG, "parseTimeContext: Se aplicó modificador de 'tarde'")
        } else if (promptLower.contains("noche")) {
            // Desde las 7 PM (19h) hasta las 4 AM del día siguiente
            endTime = startTime + (24 * oneHour) + (4 * oneHour)
            startTime = startTime + (19 * oneHour)
            MonLogger.d(TAG, "parseTimeContext: Se aplicó modificador de 'noche'")
        }

        MonLogger.d(TAG, "parseTimeContext: Cálculo finalizado -> StartTime: $startTime, EndTime: $endTime")
        return TimeParseData(startTime, endTime, quantity, isMisspelled)
    }

    private fun cleanSemanticPrompt(prompt: String): String {
        MonLogger.d(TAG, "cleanSemanticPrompt: Limpiando prompt para búsqueda semántica: '$prompt'")

        // Removemos palabras conectoras (stopwords) comunes para quedarnos con el núcleo de la idea
        val stopWords = listOf(
            "te", "acuerdas", "que", "dije", "conte", "hablamos", "de", "recordar", "recuerdas",
            "me", "gustaba", "gusta", "tal", "y", "como", "cuando", "un", "una", "el", "la",
            "los", "las", "para", "por", "sobre", "lo", "le", "en", "a"
        )

        val cleanPrompt = prompt.lowercase().replace(Regex("[^a-záéíóúñ0-9 ]"), " ")
        val words = cleanPrompt.split(Regex("\\s+")).filter { it.isNotBlank() && it !in stopWords && it.length > 2 }

        // Buscamos la palabra más larga (normalmente el sujeto o la palabra con más carga semántica)
        // Ejemplo: "te acuerdas que te dije que me gustaba el chocolate" -> nos quedamos con "chocolate"
        val mainKeyword = words.maxByOrNull { it.length } ?: ""

        MonLogger.d(TAG, "cleanSemanticPrompt: Prompt limpio resultante: '$words' | Keyword principal elegida: '$mainKeyword'")
        return mainKeyword
    }
}