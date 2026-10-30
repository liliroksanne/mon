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
    private var typingSpeedMillis: Long = 15L // ACELERADO: De 30L a 15L para que escriba mucho más rápido
    var onDialogueFinished: (() -> Unit)? = null

    // Propiedad para ajustar el volumen del sonido de escritura (0.3f = 30% del volumen, por debajo de la voz)
    var typingVolume: Float = 0.3f
        set(value) {
            field = value
            updateTrackVolume()
        }

    private var audioTrack: AudioTrack? = null
    private val sampleRate = 8000
    private val beepBuffer = byteArrayOf(
        0, 30, 60, 90, 120, 90, 60, 30, 0, -30, -60, -90, -120, -90, -60, -30
    )

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

        initAudioTrack()
    }

    private fun initAudioTrack() {
        try {
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_8BIT
            )
            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
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
                .setBufferSizeInBytes(bufferSize.coerceAtLeast(512))
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()

            audioTrack?.write(beepBuffer, 0, beepBuffer.size)
            updateTrackVolume()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateTrackVolume() {
        try {
            audioTrack?.setVolume(typingVolume)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun playSynthBeep() {
        try {
            audioTrack?.let { track ->
                if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                    track.stop()
                    track.reloadStaticData()
                }
                track.play()
            }
        } catch (e: Exception) {}
    }

    fun startDialogue(lines: List<String>) {
        dialogueLines = lines
        currentLineIndex = 0
        showCurrentLine()
    }

    private fun showCurrentLine() {
        if (currentLineIndex < dialogueLines.size) {
            textView.text = ""
            currentCharIndex = 0
            typeNextChar()
        } else {
            onDialogueFinished?.invoke()
        }
    }

    private fun typeNextChar() {
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

            // CORREGIDO: Línea unificada sin división para evitar errores de compilación
            if (currentCharIndex > 0 && !currentLine[currentCharIndex - 1].isWhitespace()) {
                playSynthBeep()
            }

            currentCharIndex++
            handler.postDelayed({ typeNextChar() }, typingSpeedMillis)
        }
    }

    private fun advanceDialogue() {
        if (currentLineIndex >= dialogueLines.size) return
        
        val currentLine = dialogueLines[currentLineIndex]
        if (currentCharIndex <= currentLine.length) {
            handler.removeCallbacksAndMessages(null)
            textView.text = currentLine
            currentCharIndex = currentLine.length + 1
            
            // Scroll automático hacia abajo al mostrar todo el texto de golpe
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
            showCurrentLine()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacksAndMessages(null)
        try {
            audioTrack?.release()
        } catch (e: Exception) {}
    }
}