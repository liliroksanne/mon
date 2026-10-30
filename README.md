# Proyecto "Mon" - Contexto Técnico para IA

## 1. Resumen del Proyecto y Core
* **Identidad:** Mon (Montserrat), compañera digital, asistente y confidente exclusiva de 35 años.
* **Estilo Visual:** Terminal retro-futurista (CMD/Linux). Fondo oscuro (`#0D0008`), texto rosa neón (`#FF4D94` para Mon, `#FFA3C8` para Lily).
* **Regla de Exclusividad:** Aplicación de uso exclusivo para Lily. Ante fallos de autenticación biométrica, el sistema bloquea el acceso.

## 2. Stack Tecnológico
* **Plataforma:** Android Nativo (API Min 26).
* **Lenguaje:** Kotlin 2.0+ con Gradle en Kotlin DSL (`build.gradle.kts`).
* **UI Framework:** **XML Views Estándar** (Descartado Jetpack Compose).
* **Arquitectura:** MVC (Model-View-Controller) tradicional.
* **IA:** SDK oficial de Gemini (`com.google.ai.client.generativeai:generativeai:0.9.0`).

## 3. Estado Actual del Código
* **Interfaz (XML):** Pantalla dividida en dos recuadros (`activity_main.xml`). El recuadro superior (`tvMonOutput`) muestra las respuestas de Mon; el inferior maneja la entrada (`etLilyInput`) y el historial de la usuaria. Utiliza bordes estilo consola (`box_terminal_border.xml`).
* **Controlador:** `MainActivity.kt` gestiona los eventos del teclado virtual (`IME_ACTION_SEND`) y orquesta la comunicación con el modelo.
* **Modelo (Datos):** La lógica de IA reside en `GeminiRepository`. Actualmente opera bajo una bandera `useHardcode = true` simulando latencia y devolviendo respuestas predefinidas para evaluar la UI.

## 4. Próximos Pasos en el Roadmap
1. **Conexión Real con Gemini:** Cambiar la bandera a `useHardcode = false` e insertar la API Key real.
2. **Efectos Visuales:** Implementar la animación de renderizado progresivo (typewriter effect) para las respuestas de Mon.
3. **Memoria y Contexto (SQLite):** Integrar Jetpack Room para almacenar el resumen del contexto histórico de forma local, inyectándolo de forma invisible en el prompt antes de cada llamada.
4. **Seguridad Biométrica:** Implementar `androidx.biometric` al iniciar la Activity principal.