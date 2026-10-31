package com.lilyroksanne.mon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.lilyroksanne.mon.util.MonLogger
import kotlin.math.min
import kotlin.random.Random

class MatrixSkullView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var matrixColor: Int = Color.parseColor("#8A2BE2")
        set(value) { field = value; updatePaints() }
    var skullColor: Int = Color.parseColor("#DA70D6")
        set(value) { field = value; updatePaints() }
    var matrixAlpha: Int = 85
        set(value) { field = value; updatePaints() }
    var skullAlpha: Int = 255
        set(value) { field = value; updatePaints() }

    var initialFadeDurationMillis: Long = 1500L
    var frameIntervalMillis: Long = 40L
    var matrixChars: String = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@#$%&*+|@-/\\<>"

    private var isModemSoundActive = false

    var minWaitSeconds: Float = 0.15f
    var maxWaitSeconds: Float = 0.45f

    private val asciiRamp = " .:-=+*#%@AYys"

    private val paintText = Paint().apply {
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
        textSize = 24f
    }

    private val paintSkull = Paint().apply {
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
        textSize = 24f
    }
    
    // Pincel para el Glitch Analógico (rojo o blanco)
    private val paintGlitch = Paint().apply {
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
        textSize = 24f
        color = Color.WHITE
    }
    
    // Variables para controlar el glitch profundo y bizarro por inactividad
    private var lastActivityTime = System.currentTimeMillis()
    private var isBizarreIdleGlitchActive = false
    private var bizarreGlitchEndTime = 0L // Cuándo debe terminar el glitch actual

    private var rawBitmaps: List<Bitmap> = emptyList()
    private var grayMapsList: MutableList<Array<IntArray>> = mutableListOf()
    private var currentFrameIndex = 0

    private val handler = Handler(Looper.getMainLooper())
    private var viewCreationTime = System.currentTimeMillis()
    private var nextFrameSwitchTime = 0L

    private var fixedWidth = 0
    private var fixedHeight = 0

    private val runnable = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            if (grayMapsList.isNotEmpty() && now >= nextFrameSwitchTime) {
                currentFrameIndex = (currentFrameIndex + 1) % grayMapsList.size
                val randomSeconds = Random.nextDouble(minWaitSeconds.toDouble(), maxWaitSeconds.toDouble())
                nextFrameSwitchTime = now + (randomSeconds * 1000).toLong()
            }
            invalidate()
            handler.postDelayed(this, frameIntervalMillis)
        }
    }

    init {
        updatePaints()
        handler.post(runnable)
    }

    fun setModemSoundActive(active: Boolean) {
        if (isModemSoundActive != active) {
            MonLogger.d("MatrixSkullView", "setModemSoundActive: Modificando a $active")
        }
        isModemSoundActive = active
        resetIdleTimer()
    }
    
    fun resetIdleTimer() {
        MonLogger.d("MatrixSkullView", "resetIdleTimer: Reseteando tiempo de inactividad")
        lastActivityTime = System.currentTimeMillis()
        isBizarreIdleGlitchActive = false
        bizarreGlitchEndTime = 0L
    }

    private fun updatePaints() {
        paintText.color = matrixColor
        paintText.alpha = matrixAlpha
        paintSkull.color = skullColor
        paintSkull.alpha = skullAlpha
    }

    fun setImageFrames(resIds: List<Int>) {
        val bitmaps = mutableListOf<Bitmap>()
        for (id in resIds) {
            val drawable = ContextCompat.getDrawable(context, id)
            if (drawable != null) {
                bitmaps.add(drawable.toBitmap())
            }
        }
        rawBitmaps = bitmaps
        viewCreationTime = System.currentTimeMillis()
        fixedWidth = 0
        nextFrameSwitchTime = System.currentTimeMillis() + 500L
        invalidate()
    }

    private fun processBitmapsOnce() {
        if (rawBitmaps.isEmpty() || width <= 0 || height <= 0) return

        if (fixedWidth == 0 || fixedHeight == 0) {
            fixedWidth = width
            fixedHeight = height
        }

        val charWidth = paintText.measureText("W")
        val charHeight = paintText.textSize * 1.2f

        val targetCols = (fixedWidth / charWidth).toInt()
        val targetRows = (fixedHeight / charHeight).toInt()

        if (targetCols <= 0 || targetRows <= 0) return

        grayMapsList.clear()
        for (bmp in rawBitmaps) {
            // Evitamos que se estire manteniendo la proporción real de la imagen original
            val bmpAspect = bmp.height.toFloat() / bmp.width.toFloat()
            val scaledWidth = targetCols
            val scaledHeight = (targetCols * bmpAspect * 0.55f).toInt().coerceAtMost(targetRows)

            val scaledBitmap = Bitmap.createScaledBitmap(bmp, scaledWidth, scaledHeight, true)

            // Creamos una cuadrícula completa del tamaño de la pantalla y centramos la imagen procesada
            val grayMap = Array(targetRows) { IntArray(targetCols) { 255 } } // 255 = fondo vacío

            val offsetY = (targetRows - scaledHeight) / 2
            val offsetX = (targetCols - scaledWidth) / 2

            for (y in 0 until scaledHeight) {
                for (x in 0 until scaledWidth) {
                    val pixel = scaledBitmap.getPixel(x, y)
                    val red = Color.red(pixel)
                    val green = Color.green(pixel)
                    val blue = Color.blue(pixel)
                    val gray = (0.299 * red + 0.587 * green + 0.114 * blue).toInt()

                    val targetY = y + offsetY
                    val targetX = x + offsetX
                    if (targetY in grayMap.indices && targetX in grayMap[0].indices) {
                        grayMap[targetY][targetX] = gray
                    }
                }
            }
            grayMapsList.add(grayMap)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)

        if (grayMapsList.isEmpty()) {
            processBitmapsOnce()
            if (grayMapsList.isEmpty()) return
        }

        if (currentFrameIndex >= grayMapsList.size) {
            currentFrameIndex = 0
        }

        val currentGrayMap = grayMapsList[currentFrameIndex]

        val currentTime = System.currentTimeMillis()
        val elapsed = currentTime - viewCreationTime
        val initialFadeFactor = (elapsed / initialFadeDurationMillis.toFloat()).coerceIn(0f, 1f)

        // Verificamos inactividad extrema de Mon
        val idleMillis = currentTime - lastActivityTime
        val idleThresholdMillis = 17 * 1000L // 15 segundos de inactividad para que empiece el glitch bizarro

        // Entramos en ventana de inactividad 
        if (idleMillis > idleThresholdMillis) {
            if (!isBizarreIdleGlitchActive && currentTime > bizarreGlitchEndTime) {
                // Probabilidad de que de la nada inicie un glitch bizarro
                if (Random.nextFloat() < 0.05f) { // Se evalúa en cada frame, así que iniciará pronto
                    isBizarreIdleGlitchActive = true
                    // Durará entre 2 y 7 segundos
                    val duration = Random.nextLong(1000L, 2700L)
                    bizarreGlitchEndTime = currentTime + duration
                    MonLogger.i("MatrixSkullView", "onDraw: INICIANDO glitch bizarro por inactividad. Durará ${duration/1000f} segundos.")
                }
            } else if (isBizarreIdleGlitchActive && currentTime > bizarreGlitchEndTime) {
                // Se acabó el tiempo del glitch
                isBizarreIdleGlitchActive = false
                val delayNext = Random.nextLong(3000L, 7000L)
                bizarreGlitchEndTime = currentTime + delayNext 
                MonLogger.i("MatrixSkullView", "onDraw: TERMINANDO glitch bizarro. Próximo en al menos ${delayNext/1000f} segundos.")
            }
        } else {
            if (isBizarreIdleGlitchActive) {
                 MonLogger.d("MatrixSkullView", "onDraw: Forzando fin de glitch bizarro porque salimos de la ventana (idleMillis = $idleMillis).")
            }
            isBizarreIdleGlitchActive = false
            bizarreGlitchEndTime = 0L
        }

        val charWidth = paintText.measureText("W")
        val charHeight = paintText.textSize * 1.2f

        val rows = currentGrayMap.size
        val cols = if (rows > 0) currentGrayMap[0].size else 0
        
        // Calculamos variables de Glitch Analógico (Módem) antes de recorrer los píxeles
        val analogInterferenceActive = isModemSoundActive && Random.nextFloat() < 0.4f // 40% de los frames tendrán glitch
        val glitchBlockStartY = if (analogInterferenceActive) Random.nextInt(0, rows) else -1
        val glitchBlockHeight = if (analogInterferenceActive) Random.nextInt(2, 10) else 0 // Afecta un bloque de filas
        val analogShift = if (analogInterferenceActive) (Random.nextFloat() * 80 - 40).toInt() else 0 // Desfase horizontal
        val useGlitchColor = analogInterferenceActive && Random.nextFloat() < 0.3f // 30% de las veces será blanco o rojo
        
        // Calculamos variables de Glitch Bizarro por Inactividad Extrema
        val bizarreInterferenceActive = isBizarreIdleGlitchActive && Random.nextFloat() < 0.1f // Parpadeo bizarro al azar
        val bizarreInvertMap = isBizarreIdleGlitchActive && Random.nextBoolean() // Cambiar sombras por luces
        val bizarreAsciiShift = if (isBizarreIdleGlitchActive) Random.nextInt(0, asciiRamp.length) else 0

        for (y in 0 until rows) {
            // Evaluamos si la fila actual cae dentro del bloque afectado por el "tearing" analógico
            val isRowGlitched = analogInterferenceActive && y >= glitchBlockStartY && y < (glitchBlockStartY + glitchBlockHeight)
            // Si está inactiva y bizarra, aplicamos distorsión ondulada usando seno sobre Y
            val bizarreWaveShift = if (bizarreInterferenceActive) (Math.sin(y.toDouble() / 5.0) * 40).toInt() else 0
            
            val currentAnalogShift = if (isRowGlitched) analogShift else 0
            
            for (x in 0 until cols) {
                val baseGray = if (bizarreInvertMap) 255 - currentGrayMap[y][x] else currentGrayMap[y][x]
                
                // Aplicamos el desfase (shift) a la coordenada X si la fila está glitcheada y onda bizarra
                val posX = x * charWidth + currentAnalogShift + bizarreWaveShift
                val posY = y * charHeight + charHeight

                if (Random.nextFloat() < 0.15) {
                    val randomChar = matrixChars[Random.nextInt(matrixChars.length)]
                    canvas.drawText(randomChar.toString(), posX, posY, paintText)
                }

                if (baseGray < 240) {
                    val noise = (Random.nextFloat() * 12 - 6).toInt()
                    val dynamicGray = (baseGray + noise).coerceIn(0, 255)

                    var index = (dynamicGray * (asciiRamp.length - 1)) / 255
                    if (isBizarreIdleGlitchActive) {
                        index = (index + bizarreAsciiShift) % asciiRamp.length
                    }
                    val asciiChar = asciiRamp[index]

                    val constantInterference = if (Random.nextFloat() < 0.25f) {
                        Random.nextFloat() * 0.4f + 0.5f
                    } else {
                        Random.nextFloat() * 0.15f + 0.85f
                    }
                    
                    // Fade out extremo (la calavera casi desaparece mientras piensa)
                    val fadeGlichFactor = if (isModemSoundActive && Random.nextFloat() < 0.75f) {
                        0.1f 
                    } else {
                        1.0f 
                    }
                    
                    // Parpadeo agresivo de inactividad
                    val bizarreFadeFactor = if (bizarreInterferenceActive && Random.nextFloat() < 0.5f) 0.0f else 1.0f

                    val combinedVisibility = initialFadeFactor * constantInterference * fadeGlichFactor * bizarreFadeFactor

                    if (Random.nextFloat() < combinedVisibility) {
                        val activePaint = if (isRowGlitched && useGlitchColor) {
                            paintGlitch.color = if (Random.nextBoolean()) Color.WHITE else Color.RED
                            paintGlitch
                        } else if (bizarreInterferenceActive) {
                            paintGlitch.color = if (Random.nextFloat() < 0.05f) Color.parseColor("#00FF41") else skullColor
                            paintGlitch
                        } else {
                            paintSkull
                        }
                        canvas.drawText(asciiChar.toString(), posX, posY, activePaint)
                    }
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        handler.removeCallbacks(runnable)
    }
}