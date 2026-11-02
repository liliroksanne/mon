package com.lilyroksanne.mon.util

import com.lilyroksanne.mon.data.db.MemoryRepository

class UserProfileSearcher(
    private val memoryRepo: MemoryRepository
) {
    private val TAG = "UserProfileSearcher"


    // Palabras clave que indican que el usuario está preguntando por su propia información.
    // Esto actúa como un "Gatillo" para iniciar la búsqueda en el perfil.
    private val profileRegex = Regex("(?i)\\b(mi edad|mis años|mis gustos|me gusta|mi nombre|mis familiares|mi familia|mis papas|mis padres|mi hermano|mi hermana|mi mascota|qué me gusta|como me llamo|cuantos años tengo|de mí|sobre mí|cuál es mi|cual es mi)\\b")

    suspend fun searchUserProfileContext(prompt: String): String {
        MonLogger.d(TAG, "searchUserProfileContext: Analizando prompt -> '$prompt'")

        val match = profileRegex.find(prompt)

        if (match != null) {
            MonLogger.i(TAG, "searchUserProfileContext: Match de perfil de usuario detectado: '${match.value}'")

            // Extraemos las etiquetas relevantes del prompt del usuario
            val keywordsToSearch = extractKeywordsForTags(prompt)
            MonLogger.d(TAG, "searchUserProfileContext: Buscando las siguientes etiquetas en BD: $keywordsToSearch")

            val foundData = mutableSetOf<String>()

            // Buscamos en la base de datos por cada palabra clave extraída
            for (keyword in keywordsToSearch) {
                val data = memoryRepo.searchUserProfile(keyword)
                if (data.isNotEmpty()) {
                    foundData.add(data)
                }
            }

            if (foundData.isNotEmpty()) {
                MonLogger.i(TAG, "searchUserProfileContext: Información de usuario encontrada en BD.")
                // Unimos todos los datos encontrados y eliminamos duplicados
                val finalProfileString = foundData.joinToString("\n")
                return "[Alisa recuerda esta información clave del usuario:\n$finalProfileString]\n"
            } else {
                MonLogger.d(TAG, "searchUserProfileContext: No se encontró información para las etiquetas: $keywordsToSearch.")
            }
        } else {
            MonLogger.d(TAG, "searchUserProfileContext: No hubo match en el perfil de usuario.")
        }

        return "" // Devuelve vacío si no hubo match
    }

    /**
     * Limpia el prompt y extrae las palabras principales para usarlas como etiquetas de búsqueda.
     */
    private fun extractKeywordsForTags(prompt: String): List<String> {
        val stopWords = listOf(
            "te", "acuerdas", "que", "dije", "conte", "hablamos", "de", "recordar", "recuerdas",
            "me", "gusta", "gustaba", "cual", "cuál", "es", "mi", "como", "cuando", "un", "una",
            "el", "la", "los", "las", "para", "por", "sobre", "lo", "le", "en", "a", "mis", "y", "o"
        )

        val cleanPrompt = prompt.lowercase().replace(Regex("[^a-záéíóúñ0-9 ]"), " ")
        val words = cleanPrompt.split(Regex("\\s+")).filter { it.isNotBlank() && it !in stopWords && it.length > 2 }

        val keywords = words.toMutableList()

        // Agregar atajos semánticos si el usuario usa ciertas frases
        val lowerPrompt = prompt.lowercase()
        if (lowerPrompt.contains("edad") || lowerPrompt.contains("años") || lowerPrompt.contains("nací")) keywords.add("edad")
        if (lowerPrompt.contains("llamo") || lowerPrompt.contains("nombre")) keywords.add("nombre")
        if (lowerPrompt.contains("familia") || lowerPrompt.contains("papás")) keywords.add("familia")
        if (lowerPrompt.contains("mascota") || lowerPrompt.contains("perro") || lowerPrompt.contains("gato")) {
            keywords.add("mascota")
            keywords.add("animal")
        }
        if (lowerPrompt.contains("gustos") || lowerPrompt.contains("hobby") || lowerPrompt.contains("pasatiempo")) {
            keywords.add("gustos")
            keywords.add("hobby")
        }
        if (lowerPrompt.contains("color")) keywords.add("color")

        return keywords.distinct()
    }
    private fun extractKeyword(matchedPhrase: String): String {
        val lowerMatch = matchedPhrase.lowercase()
        return when {
            lowerMatch.contains("edad") || lowerMatch.contains("años") -> "edad"
            lowerMatch.contains("gustos") || lowerMatch.contains("gusta") || lowerMatch.contains("hobby")-> "gustos"
            lowerMatch.contains("nombre") || lowerMatch.contains("llamo") -> "nombre"
            lowerMatch.contains("familia") || lowerMatch.contains("papas") || lowerMatch.contains("padres") -> "familia"
            lowerMatch.contains("herman") -> "herman"
            lowerMatch.contains("mascota") || lowerMatch.contains("perro") || lowerMatch.contains("gato") -> "mascota"
            else -> "" // Búsqueda más abierta
        }
    }
}