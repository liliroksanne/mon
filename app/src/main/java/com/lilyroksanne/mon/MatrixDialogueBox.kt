package com.lilyroksanne.mon

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.util.AttributeSet
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import com.lilyroksanne.mon.util.MonLogger

class MatrixDialogueBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val TAG = "MatrixDialogueBox"
    private val textView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var dialogueLines: List<String> = emptyList()
    private var currentLineIndex = 0
    private var currentCharIndex = 0

    // Velocidades base (Modifícalas aquí si lo quieres más lento o más rápido de golpe)
    private var typingSpeedMillis: Long = 77L //   Velocidad normal (rápida para los ... o errores)
    private var slowTypingSpeedMillis: Long = 77L //  Velocidad S ÚPER lenta (simulando voz humana de TTS)
    var onDialogueFinished: (() -> Unit)? = null

    // Volumen del sonido de escritura (tecleo)
    var typingVolume: Float = 0.1f
        set(value) {
            field = value
            updateTypingTrackVolume()
        }

    // AudioTrack para el tecleo de letras (corto)
    private var typingAudioTrack: AudioTrack? = null
    private val typingSampleRate = 8000
    private val beepBuffer = byteArrayOf(
        0, 30, 60, 90, 120, 90, 60, 30, 0, -30, -60, -90, -120, -90, -60, -30
    )

    // AudioTrack y Job separados exclusivamente para el sonido de MÓDEM
    private var modemAudioTrack: AudioTrack? = null
    private var modemCoroutineScope: CoroutineScope? = null
    private var modemJob: Job? = null

    // Referencia a la vista Matrix (calavera) para inyectarle el efecto visual
    var skullView: MatrixSkullView? = null

    init {
        // === ESCALAR LA CAJA DE TEXTO DEPENDIENDO DE LA PANTALLA ===
        val displayMetrics = context.resources.displayMetrics
        val density = displayMetrics.density
        val screenWidth = displayMetrics.widthPixels
        val scaleFactor = (screenWidth / 1080f).coerceAtLeast(0.6f)
        val dpToPx = { dp: Int -> (dp * density).toInt() }

        orientation = VERTICAL
        // Padding responsivo
        setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
        setBackgroundColor(Color.parseColor("#DD11051F"))

        textView = TextView(context).apply {
            setTextColor(Color.parseColor("#DA70D6"))
            // Texto responsivo: original era 29f, se ajusta con base matemática
            textSize = (29f * scaleFactor).coerceAtLeast(16f)
            typeface = Typeface.MONOSPACE
            // Line spacing responsivo
            setLineSpacing(dpToPx(3).toFloat(), 1.0f)
            maxLines = 3
            movementMethod = ScrollingMovementMethod()
            isVerticalScrollBarEnabled = true
        }
        addView(textView)

        setOnClickListener {
            advanceDialogue()
        }

        initTypingAudioTrack()
    }

    private fun initTypingAudioTrack() {
        try {
            val bufferSize = AudioTrack.getMinBufferSize(
                typingSampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_8BIT
            )
            typingAudioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_8BIT)
                        .setSampleRate(typingSampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferSize.coerceAtLeast(512))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            typingAudioTrack?.write(beepBuffer, 0, beepBuffer.size)
            updateTypingTrackVolume()
        } catch (e: Exception) {
            MonLogger.e(TAG, "Error inicializando AudioTrack de tecleo", e)
        }
    }

    private fun updateTypingTrackVolume() {
        try {
            typingAudioTrack?.setVolume(typingVolume)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playSynthBeep() {
        try {
            typingAudioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                    track.reloadStaticData()
                }
                track.play()
            }
        } catch (e: Exception) {}
    }

    // ==========================================
    // SISTEMA DE SONIDO DE MÓDEM INDEPENDIENTE
    // ==========================================
    fun startModemSoundLoop() {
        stopModemSoundLoop()
        skullView?.setModemSoundActive(true)

        modemCoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        modemJob = modemCoroutineScope?.launch {
            try {
                val sampleRate = 44100
                val channelConfig = AudioFormat.CHANNEL_OUT_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_8BIT

                val minSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, audioFormat)
                val bufferSize = minSize.coerceAtLeast(4096)

                modemAudioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(audioFormat)
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelConfig)
                            .build()
                    )
                    .setBufferSizeInBytes(bufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()

                modemAudioTrack?.setVolume(0.15f)
                modemAudioTrack?.play()

                val buffer = ByteArray(512)
                var angle = 0.0

                while (isActive) {
                    for (i in buffer.indices) {
                        val sample = Math.sin(angle) * 127.0 + (Math.random() * 20.0 - 10.0)
                        buffer[i] = sample.toInt().toByte()
                        angle += 0.35
                    }
                    modemAudioTrack?.write(buffer, 0, buffer.size)
                    delay(20L)
                }
            } catch (e: Exception) {
                MonLogger.e(TAG, "Error en loop de sonido de módem", e)
            }
        }
    }

    fun stopModemSoundLoop() {
        skullView?.setModemSoundActive(false)
        modemJob?.cancel()
        modemJob = null
        try {
            modemAudioTrack?.stop()
            modemAudioTrack?.release()
        } catch (e: Exception) {}
        modemAudioTrack = null
        modemCoroutineScope?.cancel()
        modemCoroutineScope = null
    }

    // ==========================================
    // CONTROL DE DIÁLOGOS Y ESCRITURA (DINÁMICO)
    // ==========================================
    fun startDialogue(lines: List<String>, slowDown: Boolean = false) {
        dialogueLines = lines
        currentLineIndex = 0
        textView.scrollTo(0, 0)
        showCurrentLine(slowDown)
    }

    private fun showCurrentLine(slowDown: Boolean) {
        if (currentLineIndex < dialogueLines.size) {
            textView.text = ""
            currentCharIndex = 0
            textView.scrollTo(0, 0)

            val currentLine = dialogueLines[currentLineIndex]
            val calculatedSpeed = calculateDynamicSpeed(currentLine.length, slowDown)

            typeNextChar(calculatedSpeed)
        } else {
            onDialogueFinished?.invoke()
        }
    }

    /**
     * Calcula la velocidad basándose estrictamente en la base configurada.
     * Ahora sí respetará el valor que pongas en `typingSpeedMillis`.
     */
    private fun calculateDynamicSpeed(length: Int, slowDown: Boolean): Long {
        val baseSpeed = if (slowDown) slowTypingSpeedMillis else typingSpeedMillis

        return when {
            length <= 5 -> baseSpeed + 35L    // Palabras muy cortas (más pausadas)
            length in 6..30 -> baseSpeed      // Velocidad estándar
            else -> (baseSpeed * 0.8).toLong() // Textos largos bajan ligeramente el delay para no aburrir, pero usando la base
        }
    }

    private fun typeNextChar(speedMillis: Long) {
        if (currentLineIndex >= dialogueLines.size) return

        val currentLine = dialogueLines[currentLineIndex]
        if (currentCharIndex <= currentLine.length) {
            textView.text = currentLine.substring(0, currentCharIndex)

            // Scroll automático hacia abajo mientras se escribe
            val layout = textView.layout
            if (layout != null) {
                val scrollAmount = layout.getLineTop(textView.lineCount) - textView.height
                if (scrollAmount > 0) {
                    textView.scrollTo(0, scrollAmount)
                } else {
                    textView.scrollTo(0, 0)
                }
            }

            // Reproduce el beep de tecleo omitiendo espacios en blanco
            if (currentCharIndex > 0 && !currentLine[currentCharIndex - 1].isWhitespace()) {
                playSynthBeep()
            }

            currentCharIndex++

            handler.postDelayed({ typeNextChar(speedMillis) }, speedMillis)
        }
    }

    private fun advanceDialogue() {
        if (currentLineIndex >= dialogueLines.size) return

        val currentLine = dialogueLines[currentLineIndex]
        if (currentCharIndex <= currentLine.length) {
            // Si el usuario toca la pantalla, completa el texto de golpe
            handler.removeCallbacksAndMessages(null)
            textView.text = currentLine
            currentCharIndex = currentLine.length + 1

            val layout = textView.layout
            if (layout != null) {
                val scrollAmount = layout.getLineTop(textView.lineCount) - textView.height
                if (scrollAmount > 0) {
                    textView.scrollTo(0, scrollAmount)
                } else {
                    textView.scrollTo(0, 0)
                }
            }
        } else {
            currentLineIndex++
            showCurrentLine(false)
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        stopModemSoundLoop()
        try {
            typingAudioTrack?.release()
        } catch (e: Exception) {}
    }
}