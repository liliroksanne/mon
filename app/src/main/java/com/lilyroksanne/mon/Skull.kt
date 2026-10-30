package com.lilyroksanne.mon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.os.Bundle as OSBundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
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

    private var modemAudioTrack: AudioTrack? = null
    private var modemJob: Job? = null

    private val sentMessagesHistory = mutableListOf<String>()

    private var aiJob: Job? = null
    private var proactiveJob: Job? = null
    private var isAppInForeground = false

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startListening()
        } else {
            MonLogger.e(TAG, "Permiso de micrófono denegado.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MonLogger.init(this)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sharedPreferences = getSharedPreferences("MonPrefs", Context.MODE_PRIVATE)
        tts = TextToSpeech(this, this)
        initializeSpeechRecognizer()

        val rootLayout = FrameLayout(this)

        val matrixView = MatrixSkullView(this).apply {
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
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                setMargins(40, 120, 40, 40)
            }
            typingVolume = 0.17f
            startDialogue(
                listOf(
                    "...",
                    "¿Qué pasa, Lily? Sabes que odio que me interrumpan, pero por ti hago una excepción."
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

        sttSwitch = Switch(this).apply {
            text = "Micrófono "
            setTextColor(Color.parseColor("#DA70D6"))
            typeface = Typeface.MONOSPACE
            isChecked = savedSttState
            setThumbTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#DA70D6")))
            setTrackTintList(android.content.res.ColorStateList.valueOf(Color.parseColor("#5A189A")))
            setOnCheckedChangeListener { _, isChecked ->
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

            imeOptions = EditorInfo.IME_ACTION_SEND

            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
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
    }

    private fun startModemSound() {
        stopModemSound()
        modemJob = lifecycleScope.launch {
            try {
                val sampleRate = 8000
                val minSize = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_8BIT
                )
                modemAudioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_8BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minSize.coerceAtLeast(2048))
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                modemAudioTrack?.setVolume(0.03f)
                modemAudioTrack?.play()
                val buffer = ByteArray(256)

                while (isActive) {
                    for (i in buffer.indices) {
                        buffer[i] = ((Math.sin(i.toDouble() / 3.0) * 10).toInt()).toByte()
                    }
                    modemAudioTrack?.write(buffer, 0, buffer.size)
                    delay(50L)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun stopModemSound() {
        modemJob?.cancel()
        modemJob = null
        try {
            modemAudioTrack?.stop()
            modemAudioTrack?.release()
        } catch (e: Exception) {}
        modemAudioTrack = null
    }

    private fun initializeSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: OSBundle?) {
                        if (sttSwitch.isChecked && !isAiBusy) {
                            etUserInput.hint = "Escuchando voz..."
                        }
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        if (!isAiBusy) {
                            etUserInput.hint = "Escribe un mensaje..."
                        }
                    }
                    override fun onError(error: Int) {
                        isListening = false
                        if (!isAiBusy) {
                            etUserInput.hint = "Escribe un mensaje..."
                            if (sttSwitch.isChecked && isAppInForeground && !isAiBusy) {
                                lifecycleScope.launch {
                                    delay(1500L)
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
                            etUserInput.setText(spokenText)
                            etUserInput.setSelection(etUserInput.text.length)
                            triggerSend()
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
        }
    }

    private fun checkPermissionAndStartListening() {
        if (isAiBusy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startListening() {
        if (sttSwitch.isChecked && !isListening && isAppInForeground && !isAiBusy) {
            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    // CORREGIDO: Se usa toLanguageTag() para forzar correctamente español de México ("es-MX")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale("es", "MX").toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale("es", "MX").toLanguageTag())
                    putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                }
                speechRecognizer?.startListening(intent)
                isListening = true
            } catch (e: Exception) {
                isListening = false
            }
        }
    }

    private fun stopListening() {
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {}
        isListening = false
        if (!isAiBusy) {
            etUserInput.hint = "Escribe un mensaje..."
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale("es", "MX"))
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                isTtsReady = true
                tts?.setSpeechRate(1.15f)
                tts?.setPitch(0.95f)

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        runOnUiThread {
                            isAiBusy = false
                            setInputState(true)
                        }
                    }
                    override fun onError(utteranceId: String?) {
                        runOnUiThread {
                            isAiBusy = false
                            setInputState(true)
                        }
                    }
                })
            }
        }
    }

    private fun speakLoquendo(text: String) {
        stopModemSound()
        if (ttsSwitch.isChecked && isTtsReady) {
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "LoquendoUtteranceId")
            }
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "LoquendoUtteranceId")
        } else {
            isAiBusy = false
            setInputState(true)
        }
    }

    override fun onResume() {
        super.onResume()
        isAppInForeground = true

        if (sttSwitch.isChecked && !isAiBusy) {
            checkPermissionAndStartListening()
        }

        if (Random.nextFloat() < 0.4f) {
            triggerProactiveInitiative("Saluda a Lily de la nada de forma cínica y cercana, como si estuvieras aburrida esperando.")
        }

        startProactiveTimer()
    }

    override fun onStop() {
        super.onStop()
        isAppInForeground = false
        aiJob?.cancel()
        proactiveJob?.cancel()
        stopListening()
        stopModemSound()
        tts?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        stopModemSound()
        tts?.shutdown()
    }

    private fun startProactiveTimer() {
        proactiveJob?.cancel()
        proactiveJob = lifecycleScope.launch {
            while (isAppInForeground) {
                val randomMinutes = Random.nextLong(7L, 28L)
                val delayMillis = randomMinutes * 60 * 1000L
                delay(delayMillis)

                if (isAppInForeground && !isAiBusy) {
                    triggerProactiveInitiative("Lleva un rato en silencio. Háblale a Lily espontáneamente desde tu personalidad.")
                }
            }
        }
    }

    private fun setInputState(enabled: Boolean) {
        etUserInput.isEnabled = enabled
        if (!enabled) {
            stopListening()
            isAiBusy = true
            etUserInput.hint = "Espera..."
            etUserInput.setHintTextColor(Color.parseColor("#AA93D6"))
            startModemSound()
        } else {
            stopModemSound()
            etUserInput.hint = "Escribe un mensaje..."
            etUserInput.setHintTextColor(Color.parseColor("#AA93D6"))
            if (sttSwitch.isChecked) {
                checkPermissionAndStartListening()
            }
        }
    }

    private fun triggerProactiveInitiative(contextHint: String) {
        if (isAiBusy) return
        aiJob?.cancel()
        setInputState(false)
        dialogueBox.startDialogue(listOf("..."))

        aiJob = lifecycleScope.launch {
            try {
                val response = fetchGeminiResponseAsync(contextHint)

                if (isAppInForeground) {
                    MonLogger.i(TAG, "Alisa tomó la iniciativa proactivamente.")
                    dialogueBox.startDialogue(listOf(response))
                    speakLoquendo(response)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MonLogger.e(TAG, "Error en iniciativa proactiva: ${e.message}")
                stopModemSound()
                isAiBusy = false
                setInputState(true)
            }
        }
    }

    private fun triggerSend() {
        val input = etUserInput.text.toString().trim()
        if (input.isNotEmpty() && !isAiBusy) {
            stopListening()
            hideKeyboard()
            etUserInput.text.clear()

            val dateFormat = SimpleDateFormat("yyMMdd-HHmmss", Locale.getDefault())
            val timestamp = dateFormat.format(Date())
            val linuxLine = "lily[$timestamp]$ $input"

            sentMessagesHistory.add(linuxLine)
            if (sentMessagesHistory.size > 7) {
                sentMessagesHistory.removeAt(0)
            }

            tvLastMessages.visibility = android.view.View.VISIBLE
            tvLastMessages.text = sentMessagesHistory.joinToString("\n")

            simulateAsciiDecoding(input)
            startProactiveTimer()
        }
    }

    private fun simulateAsciiDecoding(finalInput: String) {
        aiJob?.cancel()
        setInputState(false)

        aiJob = lifecycleScope.launch {
            try {
                val asciiChars = "█▓▒░#@$%&*<>[]/\\=?+-10.,§~"
                val length = finalInput.length.coerceAtLeast(1)

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
                        delay(90L)
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

                val response = responseDeferred.await()
                animationJob.cancel()

                if (isAppInForeground) {
                    MonLogger.i(TAG, "Respuesta recibida de Alisa.")
                    dialogueBox.startDialogue(listOf(response))
                    speakLoquendo(response)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                MonLogger.e(TAG, "Error en decodificación ASCII: ${e.message}")
                if (isAppInForeground) {
                    stopModemSound()
                    dialogueBox.startDialogue(listOf("Mierda, algo falló en la red."))
                    speakLoquendo("Mierda, algo falló en la red.")
                }
            }
        }
    }

    private suspend fun fetchGeminiResponseAsync(input: String): String {
        return withContext(Dispatchers.IO) {
            viewModel.geminiService.generateResponse(input)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(etUserInput.windowToken, 0)
    }
}