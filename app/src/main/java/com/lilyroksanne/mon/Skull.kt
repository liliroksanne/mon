package com.lilyroksanne.mon

import android.Manifest
import android.app.KeyguardManager
import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Bundle as OSBundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.lilyroksanne.mon.util.MonLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class Skull : AppCompatActivity(), TextToSpeech.OnInitListener {

    private val TAG = "SkullActivity"
    private val viewModel: MainViewModel by viewModels()

    private lateinit var dialogueBox: MatrixDialogueBox
    private lateinit var etUserInput: EditText
    private lateinit var bottomWrapper: LinearLayout
    private lateinit var tvLastMessages: TextView
    private lateinit var ttsSwitch: Switch
    private lateinit var sttSwitch: Switch

    private lateinit var sharedPreferences: SharedPreferences

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isAiBusy = false

    private val sentMessagesHistory = mutableListOf<String>()

    private var aiJob: Job? = null
    private var proactiveJob: Job? = null
    private var isAppInForeground = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            MonLogger.d(TAG, "=== Permiso de micrófono concedido por el usuario ===")
            startListening()
        } else {
            MonLogger.e(TAG, "=== Permiso de micrófono denegado por el usuario ===")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MonLogger.init(this)
        MonLogger.d(TAG, "=== onCreate: Iniciando SkullActivity ===")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
            MonLogger.d(TAG, "onCreate: Configurado flags de pantalla de bloqueo para API >= O_MR1")
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
            MonLogger.d(TAG, "onCreate: Configurado flags de pantalla de bloqueo para API antigua")
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sharedPreferences = getSharedPreferences("MonPrefs", Context.MODE_PRIVATE)

        MonLogger.d(TAG, "onCreate: Inicializando TextToSpeech (Loquendo)")
        tts = TextToSpeech(this, this)

        MonLogger.d(TAG, "onCreate: Inicializando SpeechRecognizer (Voz a Texto)")
        initializeSpeechRecognizer()

        val rootLayout = FrameLayout(this)

        val matrixView = MatrixSkullView(this).apply {
            id = android.view.View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            frameIntervalMillis = 40L
            minWaitSeconds = 0.47f
            maxWaitSeconds = 0.9f
            setImageFrames(
                listOf(
                    R.drawable.lily_idle_1,
                    R.drawable.lily_idle_2,
                    R.drawable.lily_idle_3,
                    R.drawable.lily_idle_1,
                )
            )
        }

        dialogueBox = MatrixDialogueBox(this).apply {
            skullView = matrixView
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                setMargins(40, 120, 40, 40)
            }
            typingVolume = 0.127f
            // MODIFICACIÓN: Ya no saluda de forma intrusiva al abrir la app, inicia neutral en idle
            MonLogger.d(TAG, "onCreate: Configurando diálogo inicial neutral (sin saludo automático)")
            startDialogue(
                listOf(
                    "..."
                )
            )
            onDialogueFinished = {}
        }

        bottomWrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM
                setMargins(40, 0, 40, 20)
            }
        }

        val topControlsLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(10, 0, 10, 8)
            }
            gravity = Gravity.END
        }

        val savedVoiceState = sharedPreferences.getBoolean("voice_enabled", true)
        val savedSttState = sharedPreferences.getBoolean("stt_enabled", true)
        MonLogger.d(TAG, "onCreate: Estados guardados -> Voz: $savedVoiceState, STT: $savedSttState")

        sttSwitch = Switch(this).apply {
            text = "Micrófono "
            setTextColor(Color.parseColor("#DA70D6"))
            typeface = Typeface.MONOSPACE
            isChecked = savedSttState
            setThumbTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#DA70D6")))
            setTrackTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#5A189A")))
            setOnCheckedChangeListener { _, isChecked ->
                MonLogger.d(TAG, "sttSwitch: Cambio de estado de micrófono a -> $isChecked")
                sharedPreferences.edit().putBoolean("stt_enabled", isChecked).apply()
                if (isChecked && isAppInForeground && !isAiBusy) {
                    checkPermissionAndStartListening()
                } else {
                    stopListening()
                }
            }
        }

        ttsSwitch = Switch(this).apply {
            text = " Voz Loquendo "
            setTextColor(Color.parseColor("#DA70D6"))
            typeface = Typeface.MONOSPACE
            isChecked = savedVoiceState
            setThumbTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#DA70D6")))
            setTrackTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#5A189A")))
            setOnCheckedChangeListener { _, isChecked ->
                MonLogger.d(TAG, "ttsSwitch: Cambio de estado de voz Loquendo a -> $isChecked")
                sharedPreferences.edit().putBoolean("voice_enabled", isChecked).apply()
            }
        }

        topControlsLayout.addView(sttSwitch)
        topControlsLayout.addView(ttsSwitch)

        tvLastMessages = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(10, 0, 10, 10)
            }
            setTextColor(Color.parseColor("#B19CD9"))
            textSize = 13f
            typeface = Typeface.MONOSPACE
            visibility = android.view.View.GONE
        }

        val inputBackground = GradientDrawable().apply {
            setColor(Color.parseColor("#FF0F051D"))
            setStroke(3, Color.parseColor("#DA70D6"))
            cornerRadius = 32f
        }

        val inputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = inputBackground
            setPadding(30, 20, 20, 20)
            gravity = Gravity.CENTER_VERTICAL
        }

        etUserInput = EditText(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
            }
            hint = "Escribe un mensaje..."
            setHintTextColor(Color.parseColor("#AA93D6"))
            setTextColor(Color.parseColor("#FFFFFF"))

            setBackgroundColor(Color.TRANSPARENT)
            setPadding(10, 10, 10, 10)
            typeface = Typeface.MONOSPACE
            textSize = 22f

            isSingleLine = false
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            maxLines = 4
            filters = arrayOf(android.text.InputFilter.LengthFilter(270))
            imeOptions = EditorInfo.IME_ACTION_SEND

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    MonLogger.d(TAG, "etUserInput: Acción IME_ACTION_SEND detectada desde teclado")
                    triggerSend()
                    true
                } else {
                    false
                }
            }
        }

        val sendButton = Button(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL
                setMargins(15, 0, 0, 0)
            }
            text = "▶"
            setTextColor(Color.parseColor("#FFFFFF"))
            textSize = 18f
            typeface = Typeface.MONOSPACE
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#7B2CBF"))
                cornerRadius = 24f
            }
            setPadding(35, 25, 35, 25)
            setOnClickListener {
                MonLogger.d(TAG, "sendButton: Click en botón de envío manual")
                triggerSend()
            }
        }

        inputContainer.addView(etUserInput)
        inputContainer.addView(sendButton)

        bottomWrapper.addView(topControlsLayout)
        bottomWrapper.addView(tvLastMessages)
        bottomWrapper.addView(inputContainer)

        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val imeHeight = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom

            val params = bottomWrapper.layoutParams as FrameLayout.LayoutParams
            if (imeVisible) {
                params.bottomMargin = imeHeight + 15
            } else {
                params.bottomMargin = navBarHeight.coerceAtLeast(15)
            }
            bottomWrapper.layoutParams = params
            insets
        }

        rootLayout.addView(matrixView)
        rootLayout.addView(dialogueBox)
        rootLayout.addView(bottomWrapper)

        setContentView(rootLayout)

        lifecycleScope.launch {
            MonLogger.d(TAG, "onCreate: Consultando repositorio de memoria para cargar historial inicial")
            val history = viewModel.memoryRepo.getLastChatMessages(12)
            if (history.isNotEmpty()) {
                sentMessagesHistory.addAll(history)
                tvLastMessages.visibility = android.view.View.VISIBLE
                val filteredHistory = sentMessagesHistory.filter { it.startsWith("lily") }
                tvLastMessages.text = filteredHistory.joinToString("\n")
                MonLogger.d(TAG, "onCreate: Historial cargado exitosamente (${history.size} mensajes)")
            } else {
                MonLogger.d(TAG, "onCreate: Historial de chat vacío o nulo")
            }
        }
    }

    private fun initializeSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: OSBundle?) {
                        MonLogger.d(TAG, "SpeechRecognizer onReadyForSpeech")
                        if (sttSwitch.isChecked && !isAiBusy) {
                            etUserInput.hint = "Escuchando voz..."
                        }
                    }
                    override fun onBeginningOfSpeech() {
                        MonLogger.d(TAG, "SpeechRecognizer onBeginningOfSpeech: Detectando voz de usuario")
                    }
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        MonLogger.d(TAG, "SpeechRecognizer onEndOfSpeech: Fin de la locución")
                        if (!isAiBusy) {
                            etUserInput.hint = "Escribe un mensaje..."
                        }
                    }
                    override fun onError(error: Int) {
                        isListening = false
                        MonLogger.e(TAG, "SpeechRecognizer onError: código $error")
                        if (!isAiBusy) {
                            etUserInput.hint = "Escribe un mensaje..."
                            if (sttSwitch.isChecked && isAppInForeground && !isAiBusy) {
                                lifecycleScope.launch {
                                    delay(1500L)
                                    MonLogger.d(TAG, "SpeechRecognizer: Reintentando escucha tras error")
                                    checkPermissionAndStartListening()
                                }
                            }
                        }
                    }
                    override fun onResults(results: OSBundle?) {
                        isListening = false
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty() && !isAiBusy) {
                            val spokenText = matches[0]
                            MonLogger.i(TAG, "SpeechRecognizer onResults: Texto reconocido -> '$spokenText'")
                            etUserInput.setText(spokenText)
                            etUserInput.setSelection(etUserInput.text.length)
                            triggerSend()
                        } else {
                            MonLogger.d(TAG, "SpeechRecognizer onResults: Resultados vacíos o AI ocupada")
                            if (sttSwitch.isChecked && isAppInForeground && !isAiBusy) {
                                lifecycleScope.launch {
                                    delay(1000L)
                                    checkPermissionAndStartListening()
                                }
                            }
                        }
                    }
                    override fun onPartialResults(partialResults: OSBundle?) {
                        val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty() && !isAiBusy) {
                            etUserInput.setText(matches[0])
                            etUserInput.setSelection(etUserInput.text.length)
                        }
                    }
                    override fun onEvent(eventType: Int, params: OSBundle?) {}
                })
            }
            MonLogger.d(TAG, "initializeSpeechRecognizer: SpeechRecognizer creado correctamente")
        } else {
            MonLogger.e(TAG, "initializeSpeechRecognizer: Reconocimiento de voz NO disponible en este dispositivo")
        }
    }

    private fun checkPermissionAndStartListening() {
        if (isAiBusy) {
            MonLogger.d(TAG, "checkPermissionAndStartListening: Omitido, AI ocupada")
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            MonLogger.d(TAG, "checkPermissionAndStartListening: Permiso concedido, iniciando escucha")
            startListening()
        } else {
            MonLogger.d(TAG, "checkPermissionAndStartListening: Permiso requerido, solicitándolo...")
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (sttSwitch.isChecked && !isListening && isAppInForeground && !isAiBusy) {
            try {
                MonLogger.d(TAG, "startListening: Activando intents de reconocimiento de voz")
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("es", "MX").toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale("es", "MX").toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                speechRecognizer?.startListening(intent)
                isListening = true
                MonLogger.d(TAG, "startListening: Escucha activada con éxito")
            } catch (e: Exception) {
                MonLogger.e(TAG, "startListening: Excepción al iniciar escucha", e)
                isListening = false
            }
        } else {
            MonLogger.d(TAG, "startListening: Omitido (sttSwitch=${sttSwitch.isChecked}, isListening=$isListening, isForeground=$isAppInForeground, isAiBusy=$isAiBusy)")
        }
    }

    private fun stopListening() {
        if (isListening) {
            MonLogger.d(TAG, "stopListening: Apagando micrófono activamente")
        }
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            MonLogger.e(TAG, "stopListening: Error deteniendo speechRecognizer", e)
        }
        isListening = false
        if (!isAiBusy) {
            etUserInput.hint = "Escribe un mensaje..."
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            MonLogger.d(TAG, "onInit TTS: SUCCESS")
            val result = tts?.setLanguage(Locale("es", "MX"))
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
                tts?.setSpeechRate(1.15f)
                tts?.setPitch(0.95f)
                MonLogger.d(TAG, "onInit TTS: Configurado idioma es-MX, velocidad 1.15, tono 0.95")

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        MonLogger.d(TAG, "TTS Utterance onStart: id=$utteranceId")
                    }
                    override fun onDone(utteranceId: String?) {
                        MonLogger.d(TAG, "TTS Utterance onDone: id=$utteranceId")
                        runOnUiThread {
                            isAiBusy = false
                            if (isAppInForeground) {
                                setInputState(true)
                            } else {
                                dialogueBox.stopModemSoundLoop()
                            }
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        MonLogger.e(TAG, "TTS Utterance onError: id=$utteranceId")
                        runOnUiThread {
                            isAiBusy = false
                            if (isAppInForeground) {
                                setInputState(true)
                            } else {
                                dialogueBox.stopModemSoundLoop()
                            }
                        }
                    }
                })
            } else {
                MonLogger.e(TAG, "onInit TTS: Idioma no soportado o datos faltantes")
            }
        } else {
            MonLogger.e(TAG, "onInit TTS: Falló la inicialización con estado $status")
        }
    }

    private fun speakLoquendo(text: String) {
        MonLogger.d(TAG, "speakLoquendo: Solicitado decir texto (longitud: ${text.length}, ttsSwitch=${ttsSwitch.isChecked}, isTtsReady=$isTtsReady)")
        dialogueBox.stopModemSoundLoop()
        if (ttsSwitch.isChecked && isTtsReady) {
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "LoquendoUtteranceId")
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "LoquendoUtteranceId")
            MonLogger.d(TAG, "speakLoquendo: Comando speak enviado a motor TTS")
        } else {
            MonLogger.d(TAG, "speakLoquendo: TTS desactivado o no listo, liberando estado AI directamente")
            runOnUiThread {
                isAiBusy = false
                setInputState(true)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true
        MonLogger.d(TAG, "=== onResume: SkullActivity en primer plano ===")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isInPictureInPictureMode) {
            MonLogger.d(TAG, "onResume: Restaurado en modo PiP. Manteniendo estado.")
            return
        }

        if (sttSwitch.isChecked && !isAiBusy) {
            checkPermissionAndStartListening()
        }

        // Reiniciamos el temporizador proactivo en cuanto entra en primer plano (cuenta desde cero el idle)
        startProactiveTimer()
    }

    // =====================================
    // LÓGICA DE PICTURE-IN-PICTURE (PIP)
    // =====================================
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Entrar en modo PiP al presionar el botón de Home o al salir de la app
            val pipParams = PictureInPictureParams.Builder().build()
            enterPictureInPictureMode(pipParams)
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        }
        if (isInPictureInPictureMode) {
            // Ocultar la barra inferior de controles en la ventana minimizada
            bottomWrapper.visibility = android.view.View.GONE
            // Detener el reconocimiento de voz para evitar bugs si se queda trabado
            stopListening()
            MonLogger.d(TAG, "onPictureInPictureModeChanged: Entrando a PiP, ocultando controles.")
        } else {
            // Restaurar los controles al volver a pantalla completa
            bottomWrapper.visibility = android.view.View.VISIBLE
            MonLogger.d(TAG, "onPictureInPictureModeChanged: Saliendo de PiP, restaurando controles.")
            // Reactivar micrófono si corresponde
            if (sttSwitch.isChecked && !isAiBusy) {
                checkPermissionAndStartListening()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        isAppInForeground = false
        MonLogger.d(TAG, "=== onStop: SkullActivity pasó a segundo plano ===")
        stopListening()
        proactiveJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        isAppInForeground = false
        MonLogger.d(TAG, "=== onDestroy: Liberando recursos de SkullActivity ===")
        aiJob?.cancel()
        proactiveJob?.cancel()
        speechRecognizer?.destroy()
        dialogueBox.stopModemSoundLoop()
        tts?.stop()
        tts?.shutdown()
    }

    private fun startProactiveTimer() {
        proactiveJob?.cancel()
        MonLogger.d(TAG, "startProactiveTimer: Cancelado timer proactivo anterior. Reiniciando ciclo de inactividad...")

        proactiveJob = lifecycleScope.launch {
            while (isAppInForeground) {
                // MODIFICACIÓN: Rango de 7 a 10 min de inactividad estricta (11 exclusivo)
                val randomMinutes = Random.nextLong(1L, 3L)
                val delayMillis = randomMinutes * 60 * 1000L
                MonLogger.d(TAG, "startProactiveTimer: Mon esperando $randomMinutes minutos de inactividad (idle)...")

                try {
                    delay(delayMillis)
                } catch (e: CancellationException) {
                    MonLogger.d(TAG, "startProactiveTimer: Timer cancelado durante la espera")
                    throw e
                }

                if (isAppInForeground && !isAiBusy) {
                    MonLogger.d(TAG, "startProactiveTimer: ¡Tiempo cumplido! Han pasado $randomMinutes minutos sin interacción. Iniciando iniciativa proactiva.")

                    // MODIFICACIÓN: Extraer los últimos 2 mensajes para dar contexto exacto
                    val lastTwoMsgs = sentMessagesHistory.takeLast(2).joinToString("\n")
                    MonLogger.d(TAG, "startProactiveTimer: Últimos dos mensajes extraídos para contexto proactivo:\n$lastTwoMsgs")

                    val contextHint = if (lastTwoMsgs.isNotEmpty()) {
                        "Lily no ha dicho nada en los últimos $randomMinutes minutos. El contexto reciente de su conversación fue:\n$lastTwoMsgs\nHáblale a Lily espontáneamente desde tu personalidad."
                    } else {
                        "Lily no ha dicho nada en los últimos $randomMinutes minutos y no hay historial previo registrado. Háblale espontáneamente desde tu personalidad."
                    }

                    triggerProactiveInitiative(contextHint)
                } else {
                    MonLogger.d(TAG, "startProactiveTimer: Se cumplió el tiempo pero la app ya no está en foreground o AI está ocupada (foreground=$isAppInForeground, busy=$isAiBusy)")
                }
            }
        }
    }

    private fun setInputState(enabled: Boolean) {
        MonLogger.d(TAG, "setInputState: Cambiando estado de UI de entrada a -> habilitado=$enabled")
        runOnUiThread {
            etUserInput.isEnabled = enabled
            if (!enabled) {
                stopListening()
                isAiBusy = true
                etUserInput.hint = "Espera..."
                etUserInput.setHintTextColor(Color.parseColor("#AA93D6"))
                dialogueBox.startModemSoundLoop()
                MonLogger.d(TAG, "setInputState: UI bloqueada, sonido de módem encendido")
            } else {
                isAiBusy = false
                dialogueBox.stopModemSoundLoop()
                etUserInput.hint = "Escribe un mensaje..."
                etUserInput.setHintTextColor(Color.parseColor("#AA93D6"))
                MonLogger.d(TAG, "setInputState: UI desbloqueada, sonido de módem apagado")
                if (sttSwitch.isChecked && isAppInForeground) {
                    checkPermissionAndStartListening()
                }
            }
        }
    }

    private fun triggerProactiveInitiative(contextHint: String) {
        if (isAiBusy) {
            MonLogger.d(TAG, "triggerProactiveInitiative: Omitido porque la IA está ocupada.")
            return
        }
        MonLogger.d(TAG, "triggerProactiveInitiative: Disparando iniciativa proactiva con prompt: '$contextHint'")
        aiJob?.cancel()
        setInputState(false)
        dialogueBox.startDialogue(listOf("..."))

        aiJob = lifecycleScope.launch {
            try {
                MonLogger.d(TAG, "triggerProactiveInitiative: Solicitando respuesta asíncrona a Gemini con contexto proactivo...")
                val response = fetchGeminiResponseAsync(contextHint)
                MonLogger.i(TAG, "triggerProactiveInitiative: Respuesta recibida de Gemini -> '$response'")

                val dateFormat = SimpleDateFormat("yyMMdd-HHmmss", Locale.getDefault())
                val timestamp = dateFormat.format(Date())
                val monLine = "mon[$timestamp]$ $response"

                viewModel.memoryRepo.saveChatMessage("mon", monLine)
                addMessageToHistory(monLine)
                MonLogger.d(TAG, "triggerProactiveInitiative: Mensaje proactivo guardado en base de datos")

                if (isAppInForeground) {
                    dialogueBox.startDialogue(listOf(response), slowDown = true)
                }
                speakLoquendo(response)
            } catch (e: CancellationException) {
                MonLogger.d(TAG, "triggerProactiveInitiative: Job cancelado")
                throw e
            } catch (e: Exception) {
                MonLogger.e(TAG, "triggerProactiveInitiative: Excepción procesando iniciativa proactiva", e)
                dialogueBox.stopModemSoundLoop()
                runOnUiThread {
                    isAiBusy = false
                    if (isAppInForeground) {
                        setInputState(true)
                    }
                }
            }
        }
    }

    private fun addMessageToHistory(msg: String) {
        sentMessagesHistory.add(msg)
        if (sentMessagesHistory.size > 12) {
            sentMessagesHistory.removeAt(0)
        }
        MonLogger.d(TAG, "addMessageToHistory: Mensaje agregado al buffer local (Total en memoria: ${sentMessagesHistory.size})")
        runOnUiThread {
            tvLastMessages.visibility = android.view.View.VISIBLE
            val filteredHistory = sentMessagesHistory.filter { it.startsWith("lily") }
            tvLastMessages.text = filteredHistory.joinToString("\n")
        }
    }

    private fun triggerSend() {
        val input = etUserInput.text.toString().trim()
        MonLogger.d(TAG, "triggerSend: Intentando enviar mensaje de usuario. Longitud: ${input.length}")

        if (input.isNotEmpty() && !isAiBusy) {
            stopListening()
            hideKeyboard()
            etUserInput.text.clear()

            val lowerInput = input.lowercase()
            MonLogger.d(TAG, "triggerSend: Texto procesado: '$input'")

            if (lowerInput.contains("silencio") || lowerInput.contains("ya no hables") || lowerInput.contains("apaga tu voz") || lowerInput.contains("desactiva tu voz")) {
                MonLogger.d(TAG, "triggerSend: Comando detectado -> Desactivar voz Loquendo")
                ttsSwitch.isChecked = false
            } else if (lowerInput.contains("quiero escuchar") || lowerInput.contains("activa tu voz") || lowerInput.contains("sigue con tu voz") || lowerInput.contains("te voy a marcar")) {
                MonLogger.d(TAG, "triggerSend: Comando detectado -> Activar voz Loquendo")
                ttsSwitch.isChecked = true
            }

            if (lowerInput.contains("deja de escuchar") || lowerInput.contains("apaga el micrófono") || lowerInput.contains("apaga reconocimiento de voz") || lowerInput.contains("desactiva el micrófono")) {
                MonLogger.d(TAG, "triggerSend: Comando detectado -> Apagar micrófono STT")
                sttSwitch.isChecked = false
            } else if (lowerInput.contains("escúchame") || lowerInput.contains("enciende el micrófono") || lowerInput.contains("activa reconocimiento de voz") || lowerInput.contains("activa el micrófono")) {
                MonLogger.d(TAG, "triggerSend: Comando detectado -> Encender micrófono STT")
                sttSwitch.isChecked = true
            }

            val dateFormat = SimpleDateFormat("yyMMdd-HHmmss", Locale.getDefault())
            val timestamp = dateFormat.format(Date())
            val linuxLine = "lily[$timestamp]$ $input"

            lifecycleScope.launch {
                viewModel.memoryRepo.saveChatMessage("lily", linuxLine)
                MonLogger.d(TAG, "triggerSend: Mensaje de Lily guardado en DB")
            }
            addMessageToHistory(linuxLine)

            // MODIFICACIÓN: Al haber interacción manual, reiniciamos el temporizador proactivo de inactividad
            startProactiveTimer()
            simulateAsciiDecoding(input)
        } else {
            MonLogger.d(TAG, "triggerSend: Omitido (input vacío o AI ocupada)")
        }
    }

    private fun simulateAsciiDecoding(finalInput: String) {
        MonLogger.d(TAG, "simulateAsciiDecoding: Iniciando simulación ASCII y consulta a Gemini")
        aiJob?.cancel()
        setInputState(false)

        aiJob = lifecycleScope.launch {
            try {
                val asciiChars = "█▓▒░#@$%&*<>[]/\\=?+-10.,§~"
                var length = finalInput.length.coerceAtLeast(1)
                if(length>27)
                    length=27

                MonLogger.d(TAG, "simulateAsciiDecoding: Lanzando corrutina de generación Gemini en IO")
                val responseDeferred = async(Dispatchers.IO) {
                    viewModel.geminiService.generateResponse(finalInput)
                }

                val animationJob = launch {
                    while (isActive) {
                        val currentDisplay = (0 until length).map { i ->
                            val ch = finalInput[i]
                            if (ch.isWhitespace()) ' ' else asciiChars.random()
                        }.joinToString("")

                        dialogueBox.startDialogue(listOf(currentDisplay))
                        delay(290L)
                    }
                }

                delay(600L)

                val revealedIndices = mutableSetOf<Int>()
                while (revealedIndices.size < length) {
                    repeat(2) {
                        if (revealedIndices.size < length) {
                            var idx = Random.nextInt(length)
                            while (revealedIndices.contains(idx)) {
                                idx = Random.nextInt(length)
                            }
                            revealedIndices.add(idx)
                        }
                    }

                    val sb = StringBuilder()
                    for (i in 0 until length) {
                        if (revealedIndices.contains(i)) {
                            sb.append(finalInput[i])
                        } else {
                            sb.append(if (finalInput[i].isWhitespace()) ' ' else asciiChars.random())
                        }
                    }
                    dialogueBox.startDialogue(listOf(sb.toString()))
                    delay(100L)
                }

                dialogueBox.startDialogue(listOf(finalInput))

                MonLogger.d(TAG, "simulateAsciiDecoding: Esperando respuesta final de Gemini...")
                val response = responseDeferred.await()
                animationJob.cancel()
                MonLogger.i(TAG, "simulateAsciiDecoding: Respuesta de Gemini obtenida -> '$response'")

                val monDateFormat = SimpleDateFormat("yyMMdd-HHmmss", Locale.getDefault())
                val monTimestamp = monDateFormat.format(Date())
                val monLine = "mon[$monTimestamp]$ $response"

                viewModel.memoryRepo.saveChatMessage("mon", monLine)
                addMessageToHistory(monLine)
                MonLogger.d(TAG, "simulateAsciiDecoding: Respuesta de mon guardada en DB")

                if (isAppInForeground) {
                    dialogueBox.startDialogue(listOf(response), slowDown  = true) // (ajustado al formato existente)
                }

                speakLoquendo(response)

            } catch (e: CancellationException) {
                MonLogger.d(TAG, "simulateAsciiDecoding: Job cancelado")
                throw e
            } catch (e: Exception) {
                MonLogger.e(TAG, "simulateAsciiDecoding: Error crítico procesando respuesta", e)
                dialogueBox.stopModemSoundLoop()
                if (isAppInForeground) {
                    dialogueBox.startDialogue(listOf("Mierda, algo falló en la red."))
                }
                speakLoquendo("Mierda, algo falló en la red.")
            }
        }
    }

    private suspend fun fetchGeminiResponseAsync(input: String): String {
        return withContext(Dispatchers.IO) {
            viewModel.geminiService.generateResponse(input)
        }
    }

    private fun hideKeyboard() {
        MonLogger.d(TAG, "hideKeyboard: Ocultando teclado virtual")
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etUserInput.windowToken, 0)
    }
}