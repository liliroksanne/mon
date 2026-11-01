package com.lilyroksanne.mon.util

import com.lilyroksanne.mon.data.db.MemoryRepository

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

    private val timeRegex = Regex("(?i)\\b(ayer|antier|mes|ms|ayr|antr|yesterday|days|semana|semana pasada|mes pasado|hace\\s+(\\d+|un|unos|dos|tres|cuatro|cinco|seis|siete|ocho|nueve|diez)\\s+(d[ií]a[s]?|hora[s]?|minuto[s]?))\\b")
    private val semanticRegex = Regex("(?i)\\b(te acuerdas|recordar|recuerdas que|recuerdas de|dije que|te dije|te conté|te conte|hablamos de|hablado de)\\b")

    suspend fun searchContext(prompt: String, currentDate: String): SearchResult {
        MonLogger.d(TAG, "searchContext: Analizando prompt de entrada -> '$prompt'")

        val timeMatch = timeRegex.find(prompt)
        val semanticMatch = semanticRegex.find(prompt)

        MonLogger.d(TAG, "searchContext: Coincidencias detectadas -> TimeMatch: ${timeMatch != null}, SemanticMatch: ${semanticMatch != null}")

        var historicalSummaries = ""
        var targetedPastChat = ""
        var isMisspelled = false
        var quantity = 1

        if (timeMatch != null) {
            val rawTimeStr = timeMatch.value
            MonLogger.i(TAG, "searchContext: Regex de tiempo activado con valor: '$rawTimeStr'")

            val parseResult = parseTimeContext(rawTimeStr.lowercase())
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

        val hasLocalContext = historicalSummaries.isNotEmpty() || targetedPastChat.isNotEmpty()

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
        val enrichedPrompt = "[SysTime:$currentDate]\n[Alisa recuerda este contexto de la memoria profunda:\n$historicalSummaries]\n[Extracto del chat correspondiente:\n$targetedPastChat]\nLily:$prompt"

        return SearchResult(
            finalPrompt = enrichedPrompt,
            hasLocalContext = true,
            isMisspelled = isMisspelled,
            quantity = quantity
        )
    }

    private data class TimeParseData(val startTime: Long, val endTime: Long, val quantity: Int, val isMisspelled: Boolean)

    private fun parseTimeContext(matchedValue: String): TimeParseData {
        MonLogger.d(TAG, "parseTimeContext: Analizando cadena temporal cruda: '$matchedValue'")

        val isMisspelled = matchedValue.contains("dias") && !matchedValue.contains("días")
        if (isMisspelled) {
            MonLogger.w(TAG, "parseTimeContext: [Ortografía detectada] Se escribió 'dias' sin tilde en '$matchedValue'")
        }

        val numberMatch = Regex("\\d+").find(matchedValue)
        val quantity = when {
            numberMatch != null -> {
                val num = numberMatch.value.toInt()
                MonLogger.d(TAG, "parseTimeContext: Número encontrado por dígito: $num")
                num
            }
            matchedValue.contains("dos") -> 2
            matchedValue.contains("tres") -> 3
            matchedValue.contains("cuatro") -> 4
            matchedValue.contains("cinco") -> 5
            matchedValue.contains("seis") -> 6
            matchedValue.contains("siete") -> 7
            matchedValue.contains("ocho") -> 8
            matchedValue.contains("nueve") -> 9
            matchedValue.contains("diez") -> 10
            matchedValue.contains("un") || matchedValue.contains("unos") -> 1
            else -> {
                MonLogger.d(TAG, "parseTimeContext: No se detectó número explícito, asignando cantidad predeterminada: 1")
                1
            }
        }

        val now = System.currentTimeMillis()
        val oneHour = 3600_000L
        val oneDay = 24 * oneHour
        var startTime = 0L
        var endTime = now

        when {
            matchedValue.contains("ayer") || matchedValue.contains("yesterday") ||matchedValue.contains("ayr")||matchedValue.contains("aier")-> {
                MonLogger.d(TAG, "parseTimeContext: Rango calculado para 'ayer'")
                startTime = now - (2 * oneDay); endTime = now - oneDay
            }
            matchedValue.contains("antier") -> {
                MonLogger.d(TAG, "parseTimeContext: Rango calculado para 'antier'")
                startTime = now - (3 * oneDay); endTime = now - (2 * oneDay)
            }
            matchedValue.contains("semana") || matchedValue.contains("week") -> {
                MonLogger.d(TAG, "parseTimeContext: Rango calculado para 'semana pasada'")
                startTime = now - (8 * oneDay); endTime = now - (5 * oneDay)
            }
            matchedValue.contains("mes")||    matchedValue.contains("month") -> {
                MonLogger.d(TAG, "parseTimeContext: Rango calculado para 'mes pasado'")
                startTime = now - (32 * oneDay); endTime = now - (25 * oneDay)
            }
            matchedValue.contains("días") || matchedValue.contains("days")|| matchedValue.contains("dias") || matchedValue.contains("dia") -> {
                MonLogger.d(TAG, "parseTimeContext: Rango calculado para 'hace $quantity días'")
                startTime = now - ((quantity + 1) * oneDay)
                endTime = now - (quantity * oneDay)
            }
            matchedValue.contains("horas")|| matchedValue.contains("hour") || matchedValue.contains("hora") -> {
                MonLogger.d(TAG, "parseTimeContext: Rango calculado para 'hace $quantity horas'")
                startTime = now - ((quantity + 1) * oneHour)
                endTime = now - (quantity * oneHour)
            }
        }

        MonLogger.d(TAG, "parseTimeContext: Cálculo finalizado -> StartTime: $startTime, EndTime: $endTime")
        return TimeParseData(startTime, endTime, quantity, isMisspelled)
    }

    private fun cleanSemanticPrompt(prompt: String): String {
        MonLogger.d(TAG, "cleanSemanticPrompt: Limpiando prompt para búsqueda semántica: '$prompt'")
        val cleanPrompt = prompt.lowercase()
            .replace(Regex("(?i)\\b(te acuerdas|recordar|recuerdas que|recuerdas de|dije que|te dije|te conté|te conte|hablamos de|hablado de)\\b"), "")
            .replace(Regex("[^a-záéíóúñ0-9 ]"), "")
            .trim()

        val keywords = cleanPrompt.split(" ").filter { it.length > 4 }
        val mainKeyword = if (keywords.isNotEmpty()) keywords[0] else ""
        MonLogger.d(TAG, "cleanSemanticPrompt: Prompt limpio resultante: '$cleanPrompt' | Keyword principal elegida: '$mainKeyword'")
        return mainKeyword
    }
}