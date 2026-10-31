package com.lilyroksanne.mon.util

import com.lilyroksanne.mon.data.db.SummaryDbHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.content.ContentValues
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Herramienta de depuración para inyectar recuerdos en SQLite en el pasado
 * simulando conversaciones que ocurrieron hace días o semanas.
 */
object MemoryInjector {
    
    // FORMATO DE LA FECHA QUE VAMOS A USAR: "dd/MM/yyyy HH:mm"
    private val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("es", "MX"))

    // Escribe aquí tus chats simulados. 
    // Formato: Pair("FECHA EXACTA", "MENSAJE CRUZO (lily o mon)")
    private val fakeChats = listOf(
        // === HACE 1 MES ===
        Pair("30/09/2026 14:00", "lily[260930-140000]$ Hola Mon, hoy empecé a trabajar en un proyecto secreto de robótica."),
        Pair("30/09/2026 14:01", "mon[260930-140100]$ Vaya, al fin haces algo interesante. ¿De qué trata?"),
        Pair("30/09/2026 14:03", "lily[260930-140300]$ No te puedo decir mucho, pero tiene que ver con IA y reconocimiento de voz."),
        Pair("30/09/2026 14:04", "mon[260930-140400]$ Suena a problemas. Ten cuidado con a quién le cuentas de esto."),

        // === SEMANA PASADA ===
        Pair("23/10/2026 20:30", "lily[261023-203000]$ Qué onda Alisa, ando bien estresada por lo del proyecto, no me sale el código."),
        Pair("23/10/2026 20:32", "mon[261023-203200]$ Relájate Lily, estás a punto de tirar la toalla por una pendejada. Déjalo un rato y ponte a ver una película."),
        Pair("23/10/2026 20:35", "lily[261023-203500]$ Tienes razón, creo que veré Matrix, hace mucho que no la veo."),
        Pair("23/10/2026 20:36", "mon[261023-203600]$ Esa siempre es la opción correcta. Me avisas cuando la termines."),

        // === ANTES DE AYER ===
        Pair("28/10/2026 09:15", "lily[261028-091500]$ Buenos días, hoy fui a desayunar chilaquiles verdes con huevo, los mejores de mi vida."),
        Pair("28/10/2026 09:18", "mon[261028-091800]$ No mientas, los mejores fueron los que probamos en aquel puesto asqueroso cerca de la central."),
        Pair("28/10/2026 09:20", "lily[261028-092000]$ Jajaja tienes razón. Oye, ¿crees que llueva más tarde?"),
        Pair("28/10/2026 09:22", "mon[261028-092200]$ Con este clima de porquería lo dudo, seguro hará un calor insoportable."),

        // === ANTIER ===
        Pair("29/10/2026 18:45", "lily[261029-184500]$ Alisa, ayer conocí a alguien que se llama Paco, me acordé de tu padrastro."),
        Pair("29/10/2026 18:47", "mon[261029-184700]$ No me hables de él, sabes que aunque lo tolero a veces me saca de mis casillas. ¿Qué quería el tal Paco?"),
        Pair("29/10/2026 18:50", "lily[261029-185000]$ Nada, solo es un nuevo compañero de trabajo. Me cayó medio mal la verdad."),
        Pair("29/10/2026 18:52", "mon[261029-185200]$ Ya ves, todos son una mierda allá afuera. Mantén tu distancia."),

                // === AYER ===
                Pair("30/10/2026 18:45", "lily[261029-184500]$ Alisa, ayer conocí a alguien que se llama Paco, me acordé de tu padrastro."),
    Pair("30/10/2026 18:47", "mon[261029-184700]$ No me hables de él, sabes que aunque lo tolero a veces me saca de mis casillas. ¿Qué quería el tal Paco?"),
    Pair("30/10/2026 18:50", "lily[261029-185000]$ Nada, solo es un nuevo compañero de trabajo. Me cayó medio mal la verdad."),
    Pair("30/10/2026 18:52", "mon[261029-185200]$ Ya ves, todos son una mierda allá afuera. Mantén tu distancia.")

    )

    // Textos resumen (simulan lo que hubiera escrito la IA por detrás en el proceso automático)
    private val fakeSummaries = listOf(
        Pair("30/09/2026 14:04", "Lily le confesó a Mon que comenzó a trabajar en un proyecto secreto relacionado con robótica, reconocimiento de voz e Inteligencia Artificial. Mon reaccionó con cautela y le advirtió que tuviera cuidado con quién compartía esa información."),
        Pair("23/10/2026 20:36", "Lily le expresó a Mon su estrés por un problema de código en su proyecto. Mon le aconsejó dejarlo y relajarse viendo una película. Lily decidió que vería Matrix, una idea que Mon aprobó rotundamente."),
        Pair("28/10/2026 09:22", "Lily y Mon hablaron sobre desayunar chilaquiles verdes. Mon recordó con nostalgia unos que comieron juntas en el pasado. Lily preguntó sobre el clima, y Mon opinó de forma pesimista que haría calor."),
        Pair("29/10/2026 18:52", "Lily le contó a Mon que conoció a un compañero de trabajo llamado Paco, lo cual detonó un breve disgusto en Mon por recordarle a su padrastro. Mon le recomendó mantener su distancia y reafirmó su postura de que no confía en la gente exterior.")
    )

    suspend fun injectFakeMemories(dbHelper: SummaryDbHelper) = withContext(Dispatchers.IO) {
        MonLogger.d("MemoryInjector", "Iniciando inyección de memorias falsas...")
        
        try {
            val db = dbHelper.writableDatabase
            
            // 1. Inyectamos los mensajes textuales
            for ((dateStr, rawMsg) in fakeChats) {
                val date = sdf.parse(dateStr)
                if (date != null) {
                    val timestamp = date.time
                    val sender = if (rawMsg.startsWith("lily")) "lily" else "mon"
                    
                    val values = ContentValues().apply {
                        put(SummaryDbHelper.COLUMN_SENDER, sender)
                        put(SummaryDbHelper.COLUMN_MESSAGE, rawMsg)
                        put(SummaryDbHelper.COLUMN_TIMESTAMP, timestamp)
                    }
                    db.insert(SummaryDbHelper.TABLE_CHAT_HISTORY, null, values)
                }
            }
            
            // 2. Inyectamos los resúmenes 
            for ((dateStr, summaryText) in fakeSummaries) {
                val date = sdf.parse(dateStr)
                if (date != null) {
                    val timestamp = date.time
                    
                    val values = ContentValues().apply {
                        put(SummaryDbHelper.COLUMN_START_TIME, timestamp)
                        put(SummaryDbHelper.COLUMN_RAW_CONTENT, "Fake Log Injected")
                        put(SummaryDbHelper.COLUMN_SUMMARY_TEXT, summaryText)
                    }
                    db.insert(SummaryDbHelper.TABLE_SUMMARIES, null, values)
                }
            }
            MonLogger.d("MemoryInjector", "Inyección completada exitosamente.")
            
        } catch (e: Exception) {
            MonLogger.e("MemoryInjector", "Error inyectando memorias falsas", e)
        }
    }
}