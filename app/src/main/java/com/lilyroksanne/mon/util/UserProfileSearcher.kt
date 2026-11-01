package com.lilyroksanne.mon.util

import com.lilyroksanne.mon.data.db.MemoryRepository

class UserProfileSearcher(
    private val memoryRepo: MemoryRepository
) {
    private val TAG = "UserProfileSearcher"

    // Palabras clave que indican que el usuario está preguntando por su propia información
    private val profileRegex = Regex("(?i)\\b(mi edad|mis años|mis gustos|me gusta|mi nombre|mis familiares|mi familia|mis papas|mis padres|mi hermano|mi hermana|mi mascota|qué me gusta|como me llamo|cuantos años tengo|de mí|sobre mí)\\b")

    suspend fun searchUserProfileContext(prompt: String): String {
        MonLogger.d(TAG, "searchUserProfileContext: Analizando prompt -> '$prompt'")

        val match = profileRegex.find(prompt)

        if (match != null) {
            MonLogger.i(TAG, "searchUserProfileContext: Match de perfil de usuario detectado: '${match.value}'")

            // Extraemos una palabra clave principal para buscar en SQLite.
            // Por ejemplo, si dijo "mis gustos", buscamos "gustos" o algo similar.
            val keyword = extractKeyword(match.value)

            val userProfileData = memoryRepo.searchUserProfile(keyword)

            if (userProfileData.isNotEmpty()) {
                MonLogger.i(TAG, "searchUserProfileContext: Información de usuario encontrada en BD.")
                return "[Alisa recuerda esta información clave del usuario:\n$userProfileData]\n"
            } else {
                MonLogger.d(TAG, "searchUserProfileContext: No se encontró información bajo la palabra '$keyword'.")
            }
        } else {
            MonLogger.d(TAG, "searchUserProfileContext: No hubo match en el perfil de usuario.")
        }

        return "" // Devuelve vacío si no hubo match
    }

    private fun extractKeyword(matchedPhrase: String): String {
        val lowerMatch = matchedPhrase.lowercase()
        return when {
            lowerMatch.contains("edad") || lowerMatch.contains("años") -> "edad"
            lowerMatch.contains("gustos") || lowerMatch.contains("gusta") -> "gustos"
            lowerMatch.contains("nombre") || lowerMatch.contains("llamo") -> "nombre"
            lowerMatch.contains("familia") || lowerMatch.contains("papas") || lowerMatch.contains("padres") -> "familia"
            lowerMatch.contains("herman") -> "herman"
            lowerMatch.contains("mascota") || lowerMatch.contains("perro") || lowerMatch.contains("gato") -> "mascota"
            else -> "" // Búsqueda más abierta
        }
    }
}