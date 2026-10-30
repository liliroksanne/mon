package com.lilyroksanne.mon.data

interface GeminiService {
    suspend fun generateResponse(prompt: String): String
}