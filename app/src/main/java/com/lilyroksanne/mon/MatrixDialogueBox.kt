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

class MatrixDialogueBox @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    private val textView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private var dialogueLines: List<String> = emptyList()
    private var currentLineIndex = 0
    private var currentCharIndex = 0
    private var typingSpeedMillis: Long  = 24L // Velocidad normal (rápida para los ... o errores)
    private var slowTypingSpeedMillis: Long = 95L // Velocidad SUPER lenta que encaja con voz humana
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
        orientation = VERTICAL
        setPadding(45, 35, 45, 35)
        setBackgroundColor(Color.parseColor("#DD11051F"))

        textView = TextView(context).apply {
            setTextColor(Color.parseColor("#DA70D6"))
            textSize = 29f
            typeface = Typeface.MONOSPACE
            setLineSpacing(8f, 1.0f)
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
            e.printStackTrace()
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
                val sampleRate = 44100 // Compatible con S25 Ultra
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
                e.printStackTrace()
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
    // CONTROL DE DIÁLOGOS Y ESCRITURA
    // ==========================================
    fun startDialogue(lines: List<String>, slowDown: Boolean = false) {
        // Si slowDown es verdadero, ajustamos la velocidad del handler para simular que está "hablando"
        val currentSpeed = if (slowDown) slowTypingSpeedMillis else typingSpeedMillis
        
        dialogueLines = lines
        currentLineIndex = 0
        textView.scrollTo(0, 0)
        showCurrentLine(currentSpeed)
    }

    private fun showCurrentLine(speedMillis: Long) {
        if (currentLineIndex < dialogueLines.size) {
            textView.text = ""
            currentCharIndex = 0
            textView.scrollTo(0, 0)
            typeNextChar(speedMillis)
        } else {
            onDialogueFinished?.invoke()
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

            // Reproduce el beep de tecleo (separado del sonido de módem)
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
            // Si el usuario toca la pantalla y salta la línea actual, el resto ya sale instantáneo (speed 0) 
            // o a la velocidad por defecto si quieres que siga normal, en este caso lo dejamos con la original por facilidad
            showCurrentLine(typingSpeedMillis)
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