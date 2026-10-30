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
    var matrixChars: String = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789@#$%&*+-/\\<>"

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

        val charWidth = paintText.measureText("W")
        val charHeight = paintText.textSize * 1.2f

        val rows = currentGrayMap.size
        val cols = if (rows > 0) currentGrayMap[0].size else 0

        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val baseGray = currentGrayMap[y][x]
                val posX = x * charWidth
                val posY = y * charHeight + charHeight

                if (Random.nextFloat() < 0.15) {
                    val randomChar = matrixChars[Random.nextInt(matrixChars.length)]
                    canvas.drawText(randomChar.toString(), posX, posY, paintText)
                }

                if (baseGray < 240) {
                    val noise = (Random.nextFloat() * 12 - 6).toInt()
                    val dynamicGray = (baseGray + noise).coerceIn(0, 255)

                    val index = (dynamicGray * (asciiRamp.length - 1)) / 255
                    val asciiChar = asciiRamp[index]

                    val constantInterference = if (Random.nextFloat() < 0.25f) {
                        Random.nextFloat() * 0.4f + 0.5f
                    } else {
                        Random.nextFloat() * 0.15f + 0.85f
                    }

                    val combinedVisibility = initialFadeFactor * constantInterference

                    if (Random.nextFloat() < combinedVisibility) {
                        canvas.drawText(asciiChar.toString(), posX, posY, paintSkull)
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