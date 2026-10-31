Mon - Asistente Virtual Cyberpunk
Mon (Alisa) es un asistente virtual para Android diseñado exclusivamente para su creadora, "Lily". A diferencia de los asistentes corporativos genéricos, Mon tiene una personalidad fuertemente definida: es cínica, calculadora, fría y sociópata con el resto del mundo, pero muestra una lealtad y honestidad absoluta únicamente hacia Lily.
El proyecto destaca por su interfaz estilo hacker/cyberpunk (ASCII Matrix), su sistema de memoria persistente a corto y largo plazo (SQLite), y su funcionamiento en segundo plano con soporte para ser invocada vía Google Assistant ("Ok Google, abre a Mon").
🧠 Flujo General del Proyecto
El ciclo de vida de una interacción en Mon sigue el siguiente flujo de procesamiento:
1. Entrada de Usuario y UI (Capa de Presentación)
Todo comienza en  Skull.kt, la actividad principal.
•
La app puede ser lanzada con la pantalla bloqueada a través de Google Assistant gracias a flags de WAKE_LOCK y DISABLE_KEYGUARD.
•
El usuario (Lily) puede comunicarse escribiendo en la caja de texto o hablando por el micrófono usando el SpeechRecognizer nativo de Android.
•
La UI incluye MatrixSkullView, que dibuja una calavera con arte ASCII. Esta vista maneja efectos visuales como:
◦
Modem Glitch: Tearing analógico cuando la IA está procesando (pensando).
◦
Bizarre Idle Glitch: Comportamiento errático y aleatorio si se deja la app inactiva por varios minutos.
2. Análisis del Contexto (Capa de Lenguaje Natural)
Antes de mandar el mensaje a la nube, la app filtra la oración en  ContextSearcher.kt.
•
Se usan expresiones regulares (Regex) para analizar la intención del usuario de forma local.
•
Flujo Normal: Si el mensaje es casual (ej. "Hola"), se avanza directamente.
•
Flujo de Recuerdo (Episódico/Semántico): Si Lily escribe palabras de tiempo ("ayer", "hace 3 horas") o semánticas ("¿te acuerdas de...?"), el ContextSearcher realiza los cálculos matemáticos para descubrir el timestamp exacto del pasado al que se refiere, o extrae la palabra clave principal de la oración.
3. Recuperación de Memoria (Capa de Datos Local)
 MemoryRepository.kt interactúa con SQLite (SummaryDbHelper).
•
Si el ContextSearcher solicitó memoria, el repositorio extrae los Resúmenes históricos y los Extractos exactos del Chat correspondientes a la fecha calculada o la palabra clave solicitada.
•
Si es simplemente el primer mensaje de la sesión, el repositorio solo trae la memoria reciente (últimos 3 resúmenes y 4 líneas de chat) para darle un contexto básico de arranque.
4. Procesamiento de IA (Capa de Lógica / Nube)
 GeminiRepository.kt junta todos estos pedazos y se los envía a Gemini 1.5 Flash.
•
Se construye un prompt enriquecido y oculto que contiene: [Instrucciones de Personalidad] + [Tiempo del Sistema Actual] + [Memoria Inyectada por ContextSearcher] + [Mensaje de Lily].
•
Mientras Gemini genera la respuesta, de forma asíncrona, se disparan tareas en segundo plano (summarizerModel) para tomar todo el chat acumulado de los últimos 7 minutos y guardarlo como un "Resumen" en SQLite, manteniendo así la memoria de Mon fresca y sin gastar miles de tokens enviando el historial completo.
5. Salida de Respuesta y Retroalimentación
Una vez que Gemini devuelve el texto generado:
•
 MatrixDialogueBox.kt dibuja la respuesta en pantalla. Utiliza un efecto de "máquina de escribir" que se sincroniza a una velocidad de lectura lenta (135ms por letra) para asemejar el ritmo de voz humano. Además, sintetiza pequeños "beeps" electrónicos.
•
En paralelo, el motor TTS (Text-to-Speech con voz Loquendo) narra la respuesta.
•
Los mensajes finales se guardan en el historial textual de SQLite para futuras búsquedas semánticas.
•
La aplicación entra en modo espera (Idle) y su temporizador proactivo inicia la cuenta regresiva. Si pasan entre 7 y 10 minutos sin que Lily interactúe con el celular, Mon tomará la iniciativa de hablarle sola quejándose de la inactividad o retomando el último tema.
🛠️ Herramientas y Módulos Clave
•
Gemini SDK: Cerebro lógico de IA, configurado con prompts de sistema restrictivos.
•
Picture-in-Picture (PiP): Mon puede encogerse a una mini-ventana flotante si minimizas la app, ocultando teclado y botones pero garantizando que el diálogo y la voz sigan fluyendo mientras usas otras aplicaciones.
•
MonLogger: Sistema de depuración personalizado que guarda logs localmente usando Storage Access Framework (SAF) para auditar qué hace la IA por debajo.
•
MemoryInjector: Herramienta de depuración para desarrollo (DevTool) creada para inyectar recuerdos falsos con timestamps antiguos en SQLite y así poder probar la respuesta de memoria episódica de Mon sin tener que esperar días en tiempo real.