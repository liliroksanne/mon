package com.lilyroksanne.mon

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.lilyroksanne.mon.data.GeminiRepository
import com.lilyroksanne.mon.data.GeminiService
import com.lilyroksanne.mon.data.db.MemoryRepository
import com.lilyroksanne.mon.util.MonLogger

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val TAG = "MainViewModel"

    // Repositorio de SQLite para la memoria
    val memoryRepo = MemoryRepository(application)
    
    // Al instanciar aquí el repositorio, sobrevive a los cambios de configuración
    val geminiService: GeminiService

    init {
        MonLogger.d(TAG, "ViewModel creado e inicializado. Conectando con GeminiService y MemoryRepository")
        geminiService = GeminiRepository(memoryRepo = memoryRepo)
    }

    override fun onCleared() {
        super.onCleared()
        MonLogger.d(TAG, "onCleared: ViewModel destruido (la app se cerró por completo o terminó la actividad).")
    }
}