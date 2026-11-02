package com.lilyroksanne.mon.util

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

/**
 * Utilidad para detectar teclados físicos (QWERTY o numérico 12 teclas T9),
 * deshabilitar el teclado en pantalla (soft keyboard) cuando se interactúa por touch o foco,
 * y gestionar la escritura multitap (estilo teléfono clásico) con teclados numéricos.
 * Especialmente adaptado para teléfonos flip / feature phones Android como el GIONEE D999.
 */
object HardwareKeyboardHelper {

    private const val TAG = "HardwareKeyboardHelper"

    // Constantes de tipo de teclado numérico de InputDevice.getKeyboardType()
    private const val KEYBOARD_TYPE_NON_ALPHANUMERIC = 1
    private const val KEYBOARD_TYPE_ALPHANUMERIC = 2

    enum class KeyboardType {
        NO_KEYBOARD,
        QWERTY,
        NUMERIC_12KEY
    }

    fun logDeviceInfo(context: Context) {
        val config = context.resources.configuration
        MonLogger.d(TAG, "==================================================")
        MonLogger.d(TAG, "=== INFORMACIÓN DE HARDWARE Y TECLADO DISPOSITIVO ===")
        MonLogger.d(TAG, "Modelo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.PRODUCT}, ${Build.DEVICE})")
        MonLogger.d(TAG, "Versión Android SDK: ${Build.VERSION.SDK_INT}")
        MonLogger.d(TAG, "Config.keyboard: ${config.keyboard} (1=NOKEYS, 2=QWERTY, 3=12KEY)")
        MonLogger.d(TAG, "Config.keyboardHidden: ${config.keyboardHidden} (1=NO, 2=YES)")
        MonLogger.d(TAG, "Config.hardKeyboardHidden: ${config.hardKeyboardHidden} (1=NO, 2=YES)")
        MonLogger.d(TAG, "Config.navigation: ${config.navigation} (1=NONAV, 2=DPAD, 3=TRACKBALL, 4=WHEEL)")

        val deviceIds = InputDevice.getDeviceIds()
        MonLogger.d(TAG, "InputDevices conectados (${deviceIds.size} dispositivos):")
        for (id in deviceIds) {
            val device = InputDevice.getDevice(id) ?: continue
            val isKeyboardSource = (device.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
            val isDpadSource = (device.sources and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
            MonLogger.d(TAG, " -> ID=$id: Name='${device.name}', Virtual=${device.isVirtual}, " +
                    "Sources=0x${Integer.toHexString(device.sources)} [isKeyboard=$isKeyboardSource, isDpad=$isDpadSource], " +
                    "KeyboardType=${device.keyboardType}")
        }
        MonLogger.d(TAG, "==================================================")
    }

    fun hasHardwareKeyboard(context: Context): Boolean {
        val config = context.resources.configuration

        if (config.keyboard != Configuration.KEYBOARD_NOKEYS &&
            config.hardKeyboardHidden != Configuration.HARDKEYBOARDHIDDEN_YES
        ) {
            return true
        }

        val modelUpper = Build.MODEL.uppercase()
        val manufacturerUpper = Build.MANUFACTURER.uppercase()
        if (modelUpper.contains("D999") || modelUpper.contains("GIONEE") ||
            manufacturerUpper.contains("GIONEE") || config.navigation == Configuration.NAVIGATION_DPAD
        ) {
            return true
        }

        val deviceIds = InputDevice.getDeviceIds()
        for (id in deviceIds) {
            val device = InputDevice.getDevice(id) ?: continue
            if (device.isVirtual) continue

            val sources = device.sources
            val isKeyboard = (sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
            val isDpad = (sources and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
            val nameLower = device.name.lowercase()

            val isKeypadDevice = nameLower.contains("kpd") || nameLower.contains("keypad") ||
                    nameLower.contains("gpio") || nameLower.contains("keyboard") || nameLower.contains("qwerty")

            if ((isKeyboard || isDpad || isKeypadDevice) && device.keyboardType != InputDevice.KEYBOARD_TYPE_NONE) {
                return true
            }
        }

        return false
    }

    fun getHardwareKeyboardType(context: Context): KeyboardType {
        logDeviceInfo(context)
        val config = context.resources.configuration

        if (config.keyboard == Configuration.KEYBOARD_12KEY) {
            return KeyboardType.NUMERIC_12KEY
        }
        if (config.keyboard == Configuration.KEYBOARD_QWERTY) {
            return KeyboardType.QWERTY
        }

        val deviceIds = InputDevice.getDeviceIds()
        var foundType: KeyboardType = KeyboardType.NO_KEYBOARD

        for (id in deviceIds) {
            val device = InputDevice.getDevice(id) ?: continue
            if (device.isVirtual) continue

            val nameLower = device.name.lowercase()
            val sources = device.sources
            val isKbSource = (sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
            val isDpadSource = (sources and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
            val isKeypadName = nameLower.contains("kpd") || nameLower.contains("keypad") ||
                    nameLower.contains("gpio") || nameLower.contains("mtk") || nameLower.contains("t9")

            if (isKbSource || isDpadSource || isKeypadName) {
                when (device.keyboardType) {
                    KEYBOARD_TYPE_ALPHANUMERIC -> return KeyboardType.QWERTY
                    KEYBOARD_TYPE_NON_ALPHANUMERIC -> foundType = KeyboardType.NUMERIC_12KEY
                    else -> {
                        if (isKeypadName || isDpadSource) {
                            foundType = KeyboardType.NUMERIC_12KEY
                        }
                    }
                }
            }
        }

        if (foundType != KeyboardType.NO_KEYBOARD) {
            return foundType
        }

        val modelUpper = Build.MODEL.uppercase()
        val manufacturerUpper = Build.MANUFACTURER.uppercase()
        val isGioneeOrFlip = modelUpper.contains("D999") || modelUpper.contains("GIONEE") ||
                manufacturerUpper.contains("GIONEE") || config.navigation == Configuration.NAVIGATION_DPAD ||
                config.hardKeyboardHidden == Configuration.HARDKEYBOARDHIDDEN_NO

        if (isGioneeOrFlip) {
            return KeyboardType.NUMERIC_12KEY
        }

        return KeyboardType.NUMERIC_12KEY // Predeterminar a NUMERIC_12KEY para teléfonos flip
    }

    fun setupHardwareKeyboard(
        editText: EditText,
        onEnterPressed: (() -> Unit)? = null,
        force12KeyMode: Boolean = false,
        forceDisableSoftKeyboard: Boolean = true
    ) {
        val context = editText.context
        val hwType = getHardwareKeyboardType(context)
        val hasHw = hasHardwareKeyboard(context)

        MonLogger.d(TAG, "setupHardwareKeyboard: hwType=$hwType, hasHw=$hasHw")

        // Previene la aparición del teclado virtual
        editText.showSoftInputOnFocus = false

        editText.setOnTouchListener { v, _ ->
            editText.showSoftInputOnFocus = false
            val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(v.windowToken, 0)
            false
        }

        editText.setOnFocusChangeListener { v, hasFocus ->
            if (hasFocus) {
                val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
            }
        }

        val is12Key = force12KeyMode || hwType == KeyboardType.NUMERIC_12KEY || hwType == KeyboardType.NO_KEYBOARD

        val listener = MultiTapKeyListener(
            editText = editText,
            is12KeyEnabled = is12Key,
            onEnterPressed = onEnterPressed
        )
        editText.setOnKeyListener(listener)
    }

    class MultiTapKeyListener(
        private val editText: EditText,
        private val is12KeyEnabled: Boolean = true,
        private val timeoutMs: Long = 1000L,
        private val onEnterPressed: (() -> Unit)? = null
    ) : View.OnKeyListener {

        private var lastKeyCode: Int = -1
        private var lastPressTime: Long = 0L
        private var currentIndex: Int = 0
        private var lastInsertedPos: Int = -1
        private var isUppercase: Boolean = false

        private val keyCharMap: Map<Int, List<Char>> = mapOf(
            KeyEvent.KEYCODE_1 to listOf('.', ',', '!', '?', '-', '@', '1'),
            KeyEvent.KEYCODE_NUMPAD_1 to listOf('.', ',', '!', '?', '-', '@', '1'),

            KeyEvent.KEYCODE_2 to listOf('a', 'b', 'c', 'á', '2'),
            KeyEvent.KEYCODE_NUMPAD_2 to listOf('a', 'b', 'c', 'á', '2'),

            KeyEvent.KEYCODE_3 to listOf('d', 'e', 'f', 'é', '3'),
            KeyEvent.KEYCODE_NUMPAD_3 to listOf('d', 'e', 'f', 'é', '3'),

            KeyEvent.KEYCODE_4 to listOf('g', 'h', 'i', 'í', '4'),
            KeyEvent.KEYCODE_NUMPAD_4 to listOf('g', 'h', 'i', 'í', '4'),

            KeyEvent.KEYCODE_5 to listOf('j', 'k', 'l', '5'),
            KeyEvent.KEYCODE_NUMPAD_5 to listOf('j', 'k', 'l', '5'),

            KeyEvent.KEYCODE_6 to listOf('m', 'n', 'ñ', 'o', 'ó', '6'),
            KeyEvent.KEYCODE_NUMPAD_6 to listOf('m', 'n', 'ñ', 'o', 'ó', '6'),

            KeyEvent.KEYCODE_7 to listOf('p', 'q', 'r', 's', '7'),
            KeyEvent.KEYCODE_NUMPAD_7 to listOf('p', 'q', 'r', 's', '7'),

            KeyEvent.KEYCODE_8 to listOf('t', 'u', 'v', 'ú', '8'),
            KeyEvent.KEYCODE_NUMPAD_8 to listOf('t', 'u', 'v', 'ú', '8'),

            KeyEvent.KEYCODE_9 to listOf('w', 'x', 'y', 'z', '9'),
            KeyEvent.KEYCODE_NUMPAD_9 to listOf('w', 'x', 'y', 'z', '9'),

            KeyEvent.KEYCODE_0 to listOf(' ', '0'),
            KeyEvent.KEYCODE_NUMPAD_0 to listOf(' ', '0')
        )

        override fun onKey(v: View?, keyCode: Int, event: KeyEvent?): Boolean {
            if (event == null) return false

            val keyName = KeyEvent.keyCodeToString(keyCode)
            val actionStr = if (event.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"
            MonLogger.d(TAG, "MultiTapKeyListener.onKey: action=$actionStr, keyCode=$keyCode ($keyName)")

            if (event.action != KeyEvent.ACTION_DOWN) {
                return false
            }

            // Tecla Enter física / Centro D-Pad
            if (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                MonLogger.d(TAG, "MultiTapKeyListener: Enter/DPAD_CENTER -> Enviando mensaje")
                onEnterPressed?.invoke()
                return true
            }

            // TECLA DERECHA / BORRAR (KEYCODE_SOFT_RIGHT, KEYCODE_DEL, KEYCODE_CLEAR, KEYCODE_BACK)
            val isDeleteKey = keyCode == KeyEvent.KEYCODE_SOFT_RIGHT ||
                    keyCode == KeyEvent.KEYCODE_DEL ||
                    keyCode == KeyEvent.KEYCODE_CLEAR ||
                    (keyCode == KeyEvent.KEYCODE_BACK && (editText.isFocused && !editText.text.isNullOrEmpty()))

            if (isDeleteKey) {
                resetState()
                val editable = editText.text
                if (!editable.isNullOrEmpty()) {
                    val selStart = editText.selectionStart.coerceAtLeast(0)
                    val selEnd = editText.selectionEnd.coerceAtLeast(0)

                    if (selStart != selEnd) {
                        MonLogger.d(TAG, "MultiTapKeyListener: Borrando selección")
                        editable.delete(selStart, selEnd)
                    } else if (selStart > 0) {
                        MonLogger.d(TAG, "MultiTapKeyListener: Borrando carácter previo")
                        editable.delete(selStart - 1, selStart)
                    }
                }
                return true
            }

            if (!is12KeyEnabled) {
                return false
            }

            // Asterisco (*) cambia Mayúsculas / Minúsculas
            if (keyCode == KeyEvent.KEYCODE_STAR || keyCode == KeyEvent.KEYCODE_NUMPAD_MULTIPLY) {
                isUppercase = !isUppercase
                resetState()
                MonLogger.d(TAG, "MultiTapKeyListener: Star (*) -> Mayúsculas=$isUppercase")
                return true
            }

            val chars = keyCharMap[keyCode] ?: run {
                resetState()
                return false
            }

            val now = System.currentTimeMillis()
            val editable = editText.text ?: return false
            val selStart = editText.selectionStart.coerceAtLeast(0)
            val selEnd = editText.selectionEnd.coerceAtLeast(0)

            if (keyCode == lastKeyCode && (now - lastPressTime) < timeoutMs && lastInsertedPos != -1) {
                // Misma tecla: Rotar carácter T9
                currentIndex = (currentIndex + 1) % chars.size
                var charToInsert = chars[currentIndex]
                if (isUppercase && charToInsert.isLetter()) {
                    charToInsert = charToInsert.uppercaseChar()
                }

                val replaceStart = lastInsertedPos
                val replaceEnd = (lastInsertedPos + 1).coerceAtMost(editable.length)

                if (replaceStart in 0 until editable.length && replaceStart < replaceEnd) {
                    editable.replace(replaceStart, replaceEnd, charToInsert.toString())
                    editText.setSelection((replaceStart + 1).coerceAtMost(editable.length))
                } else {
                    editable.replace(selStart, selEnd, charToInsert.toString())
                    lastInsertedPos = selStart
                    editText.setSelection((selStart + 1).coerceAtMost(editable.length))
                }
            } else {
                // Nueva tecla: Insertar primer carácter
                currentIndex = 0
                var charToInsert = chars[currentIndex]
                if (isUppercase && charToInsert.isLetter()) {
                    charToInsert = charToInsert.uppercaseChar()
                }

                val insertPos = selStart
                editable.replace(selStart, selEnd, charToInsert.toString())
                lastInsertedPos = insertPos
                editText.setSelection((insertPos + 1).coerceAtMost(editable.length))
            }

            lastKeyCode = keyCode
            lastPressTime = now
            return true
        }

        fun resetState() {
            lastKeyCode = -1
            lastPressTime = 0L
            currentIndex = 0
            lastInsertedPos = -1
        }
    }
}