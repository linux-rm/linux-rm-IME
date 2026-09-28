package com.linuxrm.IME

import com.linuxrm.IME.core.*
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.*
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.isVisible
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.graphics.toColorInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import android.os.Environment

fun Context.copyAssetsToDir(assetPath : String, targetDir : File) {
	val assetManager = assets
	val files = assetManager.list(assetPath) ?: return
	if (! targetDir.exists()) {
		targetDir.mkdirs()
	}
	for (filename in files) {
		val assetFullPath = if (assetPath.isEmpty()) filename else "$assetPath/$filename"
		val targetFile = File(targetDir, filename)
		if (assetManager.list(assetFullPath)?.isNotEmpty() == true) {
			copyAssetsToDir(assetFullPath, targetFile)
		} else {
			try {
				assetManager.open(assetFullPath).use { input ->
					FileOutputStream(targetFile).use { output ->
						input.copyTo(output)
					}
				}
			} catch (e : IOException) {
				e.printStackTrace()
			}
		}
	}
}

private const val LONG_PRESS_THRESHOLD = 300L

class PCKeyboardIME : InputMethodService() {
	enum class SpecialAction {
		SETTINGS, Fn, HIDE_KEYBOARD, SYMBOL, PASTE, NUM_TOGGLE, CAPS_TOGGLE, KEYCODE_CANDIDATE, SHOW_MORE
	}

	data class Key(val label : String, val shLab : String? = null, val kCode : Int = KeyEvent.KEYCODE_UNKNOWN, val width : Float = 1f, val isModifier : Boolean = false, val spAct : SpecialAction? = null) {
		val isLetter : Boolean get() = kCode in KeyEvent.KEYCODE_A .. KeyEvent.KEYCODE_Z
	}

	private enum class LongPressAction { NONE, REPEAT }

	private val currentCandidates = mutableListOf<String>()
	private val currentPinyinCandidates = mutableListOf<String>()
	private lateinit var keyboardView : KeyboardView
	private lateinit var candidateRecyclerView : RecyclerView
	private lateinit var pinyinCandidateBar : RecyclerView
	private lateinit var keyboardContainer : FrameLayout
	private var fullCandidateRefreshJob : Job? = null
	private lateinit var preEditText : EditText
	private lateinit var symbolProcessor : SymbolProcessor
	private lateinit var rime : Rime
	private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
	private var isSymbolProcessorMode = false
	private var currentCategoryIndex = 0
	private var currentCategoryName = ""
	private var currentModeIndex = 0
	private var shiftPressed = false
	private var ctrlPressed = false
	private var altPressed = false
	private var capsLock = false
	private var numActive = false
	private var fnActive = false
	private var beforeNumModeIndex = 0
	private val typeText = 0
	private val typePassword = 2
	private var currentInputTypeCategory = typeText
	private var isNumberMode = false
	private var isPasswordMode = false
	private var isRawInputMode = false
	private lateinit var prefs : SharedPreferences
	private val oldStickyState = mutableMapOf<Int, Boolean>()
	private val stickyMode = mutableMapOf<Int, Boolean>()
	private lateinit var dropdownContainer : FrameLayout
	private lateinit var dropdownScrollView : ScrollView
	private lateinit var dropdownLayout : LinearLayout
	private var cachedSchemata : List<SchemaItem> = emptyList()
	override fun onCreate() {
		super.onCreate()
		prefs = getSharedPreferences("ime_prefs", MODE_PRIVATE)
		symbolProcessor = SymbolProcessor(this)
		rime = Rime()
		rime.init(this)
		serviceScope.launch {
			rime.messageFlow.collect { message ->
				when (message) {
					is RimeMessage.CommitTextMessage -> {
						message.data.text?.let {
							currentInputConnection?.commitText(it, 1)
							clearCandidates()
						}
					}
					is RimeMessage.CompositionMessage -> {
						val preedit = message.data.preedit ?: ""
						if (preedit.isNotEmpty()) {
							if (isT9Mode()) pinyinCandidateBar.visibility = View.VISIBLE
							preEditText.visibility = View.VISIBLE
							preEditText.setText(preedit)
							val cursor = message.data.cursorPos.coerceAtMost(preedit.length)
							preEditText.setSelection(cursor)
						} else {
							if (! isSymbolProcessorMode) pinyinCandidateBar.visibility = View.GONE
							preEditText.visibility = View.GONE
							preEditText.text.clear()
						}
					}
					is RimeMessage.BulkCandidatesMessage -> {
						if (rime.stopImmediately) return@collect
						putCandidates(message.data.candidates.map { it.text }, emptyList())
						fullCandidateRefreshJob?.cancel()
						fullCandidateRefreshJob = serviceScope.launch {
							delay(240.milliseconds)
							val full = rime.getCandidates(0, 1024)
							putCandidates(full.map { it.text }, emptyList())
						}
					}
					else -> {}
				}
			}
		}
		CoroutineScope(Dispatchers.IO).launch {
			val sharedDir = File(Environment.getExternalStorageDirectory().path, "linux-rm-ime/rime_shared")
			sharedDir.mkdirs()
			if (! File(sharedDir, "default.yaml").exists()) {
				copyAssetsToDir("rime_shared", sharedDir)
			}
			val userDir = File(Environment.getExternalStorageDirectory().path, "linux-rm-ime/rime_user")
			if (! userDir.exists()) userDir.mkdirs()
			withContext(Dispatchers.Main) {
				rime.startup()
				serviceScope.launch {
					cachedSchemata = rime.availableSchemata().toList()
				}
			}
		}
	}

	override fun onDestroy() {
		rime.finalize()
		super.onDestroy()
	}

	override fun onEvaluateFullscreenMode() : Boolean = false
	override fun onEvaluateInputViewShown() : Boolean {
		super.onEvaluateInputViewShown()
		return true
	}

	private val isRimeEnabled : Boolean get() = ! isPasswordMode && ! isNumberMode && ! isSymbolProcessorMode && currentModeIndex != 0
	private fun isT9Mode() = currentModeIndex == 2
	private suspend fun updateRimeUI() {
		val rimeContext = rime.getContext()
		val candidates = rime.getCandidates(0, 1024)
		putCandidates(candidates.map { it.text }, emptyList())
		val displayText = if (isRawInputMode) rimeContext.input
		else rimeContext.composition.preedit ?: ""
		if (displayText.isNotEmpty()) {
			preEditText.visibility = View.VISIBLE
			preEditText.setText(displayText)
			preEditText.setSelection(if (isRawInputMode) rimeContext.caretPos
			else rimeContext.composition.cursorPos.coerceAtMost(displayText.length))
		} else clearCandidates()
		if (rimeContext.input.isEmpty() && displayText.isEmpty()) hideDropdown()
	}

	@SuppressLint("NotifyDataSetChanged") private fun updateSymbolList() {
		val categories = symbolProcessor.getCategories()
		currentCategoryName = if (currentCategoryIndex < categories.size) categories[currentCategoryIndex] else ""
		currentCandidates.clear()
		currentCandidates.addAll(symbolProcessor.getSymbols(currentCategoryIndex))
		isSymbolProcessorMode = true
		preEditText.visibility = View.GONE
		pinyinCandidateBar.visibility = View.VISIBLE
		(pinyinCandidateBar.adapter as? CandidateAdapter)?.notifyDataSetChanged()
		pinyinCandidateBar.scrollToPosition(currentCategoryIndex)
		showDropdown()
	}

	private fun imeSendKeyEvent(keyCode : Int, action : Int, metaState : Int) {
		if (isPasswordMode || numActive || fnActive || currentModeIndex == 0) {
			preEditText.visibility = View.GONE
			preEditText.clearFocus()
			(currentInputConnection ?: return).sendKeyEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, keyCode, 0, metaState, 0, 0, 0, InputDevice.SOURCE_KEYBOARD))
			return
		}
		isRawInputMode = false
		if (preEditText.isVisible) {
			preEditText.requestFocus()
			if (keyCode == KeyEvent.KEYCODE_ENTER && action == KeyEvent.ACTION_DOWN) {
				val text = preEditText.text.toString()
				if (text.isNotEmpty()) {
					currentInputConnection?.commitText(text, 1)
					preEditText.text.clear()
					clearCandidates()
				}
				preEditText.visibility = View.GONE
				preEditText.clearFocus()
				updateCandidatePosition()
				return
			}
			if (isRimeEnabled && action == KeyEvent.ACTION_DOWN) {
				val isModifierOnly = when (keyCode) {
					KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT, KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT -> true
					else -> false
				}
				if (isModifierOnly) {
					return
				}
				val rimeKey = getRimeKeyCode(keyCode)
				val modifiers = KeyModifiers.fromMetaState(metaState).modifiers
				serviceScope.launch {
					val handled = rime.processKey(rimeKey, modifiers)
					if (! handled) {
						val now = SystemClock.uptimeMillis()
						currentInputConnection.sendKeyEvent(KeyEvent(now, now, action, keyCode, 0, metaState, 0, 0, 0, InputDevice.SOURCE_KEYBOARD))
					}
				}
				return
			} else {
				preEditText.dispatchKeyEvent(KeyEvent(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), action, keyCode, 0, metaState, 0, 0, 0, InputDevice.SOURCE_KEYBOARD))
			}
			if (action == KeyEvent.ACTION_UP && (keyCode == KeyEvent.KEYCODE_DEL || keyCode == KeyEvent.KEYCODE_FORWARD_DEL) && preEditText.text.isEmpty()) {
				clearCandidates()
			}
			return
		}
		val now = SystemClock.uptimeMillis()
		currentInputConnection.sendKeyEvent(KeyEvent(now, now, action, keyCode, 0, metaState, 0, 0, 0, InputDevice.SOURCE_KEYBOARD))
	}

	private fun getRimeKeyCode(keyCode : Int) : Int {
		return when (keyCode) {
			KeyEvent.KEYCODE_DEL -> 0xff08
			KeyEvent.KEYCODE_FORWARD_DEL -> 0xffff
			KeyEvent.KEYCODE_ENTER -> 0xff0d
			KeyEvent.KEYCODE_SPACE -> 0x20
			KeyEvent.KEYCODE_DPAD_LEFT -> 0xff51
			KeyEvent.KEYCODE_DPAD_RIGHT -> 0xff53
			KeyEvent.KEYCODE_DPAD_UP -> 0xff52
			KeyEvent.KEYCODE_DPAD_DOWN -> 0xff54
			KeyEvent.KEYCODE_MOVE_HOME -> 0xff50
			KeyEvent.KEYCODE_MOVE_END -> 0xff57
			KeyEvent.KEYCODE_PAGE_UP -> 0xff55
			KeyEvent.KEYCODE_PAGE_DOWN -> 0xff56
			else -> keyCode
		}
	}

	private fun imeCommitText(text : String) {
		if (isPasswordMode || numActive || fnActive || currentModeIndex == 0) {
			preEditText.visibility = View.GONE
			preEditText.clearFocus()
			currentInputConnection?.commitText(text, 1)
			return
		}
		isRawInputMode = false
		if (preEditText.isVisible && text.length == 1) {
			val editable = preEditText.editableText ?: return
			val start = preEditText.selectionStart
			val end = preEditText.selectionEnd
			if (start >= 0 && end >= 0) {
				if (isRimeEnabled) {
					val keyCode = text[0].code
					serviceScope.launch {
						val handled = rime.processKey(keyCode, currentMetaState().toUInt())
						if (! handled) {
							if (withContext(Dispatchers.IO) {
									try {
										val dir = File(Environment.getExternalStorageDirectory().path, "linux-rm-ime")
										if (! dir.exists()) dir.mkdirs()
										File(dir, "test").writeText("")
										true
									} catch (_ : Exception) {
										false
									}
								}) {
								currentInputConnection?.commitText(text, 1)
								preEditText.setText("Rime未部署完成或方案不支持")
							} else preEditText.setText("未获得储存权限 Rime部署失败")
							preEditText.visibility = View.VISIBLE
						}
					}
				} else {
					editable.replace(start, end, text)
					preEditText.setSelection(start + text.length)
				}
			} else editable.append(text)
			return
		}
		if ((! isT9Mode() && text.length == 1 && text[0] in 'a' .. 'z') || (isT9Mode() && text[0] in '0' .. '9')) {
			clearCandidates()
			if (isRimeEnabled) {
				val keyCode = text[0].code
				serviceScope.launch {
					val handled = rime.processKey(keyCode, currentMetaState().toUInt())
					if (! handled) {
						if (withContext(Dispatchers.IO) {
								try {
									val dir = File(Environment.getExternalStorageDirectory().path, "linux-rm-ime")
									if (! dir.exists()) dir.mkdirs()
									File(dir, "test").writeText("")
									true
								} catch (_ : Exception) {
									false
								}
							}) {
							currentInputConnection?.commitText(text, 1)
							preEditText.setText("Rime未部署完成或方案不支持")
						} else preEditText.setText("未获得储存权限 Rime部署失败")

						preEditText.visibility = View.VISIBLE
					}
				}
			} else {
				preEditText.setText(text)
				preEditText.setSelection(text.length)
			}
			updateCandidatePosition()
			return
		}
		currentInputConnection?.commitText(text, 1)
	}

	private fun rebuildKeyboardAndUpdate(commitPreedit : Boolean = false) {
		if (commitPreedit) {
			val text = preEditText.text.toString()
			if (text.isNotEmpty()) currentInputConnection?.commitText(text, 1)
			clearCandidates()
		}
		clearCandidates()
		keyboardView.buildMainLayout()
		keyboardView.requestLayout()
		keyboardView.invalidate()
		keyboardView.post { updateCandidatePosition() }
	}

	@SuppressLint("NotifyDataSetChanged") private fun clearCandidates() {
		isRawInputMode = false
		if (isRimeEnabled) serviceScope.launch { rime.clearComposition() }
		preEditText.text.clear()
		preEditText.clearFocus()
		currentCandidates.clear()
		currentPinyinCandidates.clear()
		(candidateRecyclerView.adapter as? CandidateAdapter)?.notifyDataSetChanged()
		(pinyinCandidateBar.adapter as? CandidateAdapter)?.notifyDataSetChanged()
		preEditText.visibility = View.GONE
		dropdownContainer.visibility = View.GONE
		pinyinCandidateBar.visibility = View.GONE
		candidateRecyclerView.visibility = View.GONE
	}

	@SuppressLint("NotifyDataSetChanged") private fun putCandidates(candidates : List<String>, pinyinCandidates : List<String> = emptyList()) {
		currentCandidates.clear()
		currentCandidates.addAll(candidates)
		currentPinyinCandidates.clear()
		currentPinyinCandidates.addAll(pinyinCandidates)
		(candidateRecyclerView.adapter as? CandidateAdapter)?.notifyDataSetChanged()
		(pinyinCandidateBar.adapter as? CandidateAdapter)?.notifyDataSetChanged()
		candidateRecyclerView.scrollToPosition(0)
		updateCandidatePosition()
	}

	inner class KeyboardView(context : Context) : View(context) {
		private val allKeys = mutableListOf<Key>()
		private val keyRect = mutableListOf<Rect>()
		private val pointerMap = mutableMapOf<Int, Int>()
		var candidateRect : Rect? = null
		private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
		private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.WHITE
			textAlign = Paint.Align.CENTER
		}
		private val shiftHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.argb(160, 255, 255, 255)
			textAlign = Paint.Align.RIGHT
		}
		private val longPressHandler = Handler(Looper.getMainLooper())
		private var longPressRunnable : Runnable? = null
		private var longPressKeyIndex : Int? = null
		private var isLongPressTriggered = false
		private var longPressAction = LongPressAction.NONE
		private var repeatKeyRunnable : Runnable? = null
		private val keyDownTimes = mutableMapOf<Int, Long>()
		private val currentRowSizes = mutableListOf<Int>()

		init {
			buildMainLayout()
		}

		fun buildMainLayout() {
			allKeys.clear()
			currentRowSizes.clear()
			if (numActive) {
				allKeys.addAll(listOf(Key("NumLock*", spAct = SpecialAction.NUM_TOGGLE), Key("/", kCode = KeyEvent.KEYCODE_SLASH), Key("*", kCode = KeyEvent.KEYCODE_STAR), Key("-", kCode = KeyEvent.KEYCODE_MINUS), Key("+", kCode = KeyEvent.KEYCODE_PLUS)).also { currentRowSizes.add(it.size) })
				allKeys.addAll(listOf(Key("1", kCode = KeyEvent.KEYCODE_1), Key("2", kCode = KeyEvent.KEYCODE_2), Key("3", kCode = KeyEvent.KEYCODE_3), Key("⌫", kCode = KeyEvent.KEYCODE_DEL, width = 0.5f), Key("⌦", kCode = KeyEvent.KEYCODE_FORWARD_DEL, width = 0.5f)).also { currentRowSizes.add(it.size) })
				allKeys.addAll(listOf(Key("4", kCode = KeyEvent.KEYCODE_4), Key("5", kCode = KeyEvent.KEYCODE_5), Key("6", kCode = KeyEvent.KEYCODE_6), Key("←", kCode = KeyEvent.KEYCODE_DPAD_LEFT, width = 0.5f), Key("→", kCode = KeyEvent.KEYCODE_DPAD_RIGHT, width = 0.5f)).also { currentRowSizes.add(it.size) })
				allKeys.addAll(listOf(Key("7", kCode = KeyEvent.KEYCODE_7), Key("8", kCode = KeyEvent.KEYCODE_8), Key("9", kCode = KeyEvent.KEYCODE_9), Key("Home", kCode = KeyEvent.KEYCODE_MOVE_HOME, width = 0.5f), Key("End", kCode = KeyEvent.KEYCODE_MOVE_END, width = 0.5f)).also { currentRowSizes.add(it.size) })
				allKeys.addAll(listOf(Key("#", kCode = KeyEvent.KEYCODE_POUND), Key(" ", kCode = KeyEvent.KEYCODE_SPACE), Key("0", kCode = KeyEvent.KEYCODE_0, width = 0.5f), Key(".", kCode = KeyEvent.KEYCODE_PERIOD, width = 0.5f), Key("Enter↵", kCode = KeyEvent.KEYCODE_ENTER, width = 1.5f), Key("*", spAct = SpecialAction.SETTINGS, width = 0.5f)).also { currentRowSizes.add(it.size) })
				allKeys.addAll(listOf(Key("!?😄", spAct = SpecialAction.SYMBOL), Key("paste", spAct = SpecialAction.PASTE), Key("pgUp", kCode = KeyEvent.KEYCODE_PAGE_UP), Key("pgDn", kCode = KeyEvent.KEYCODE_PAGE_DOWN), Key("⌵", spAct = SpecialAction.HIDE_KEYBOARD)).also { currentRowSizes.add(it.size) })
			} else if (isT9Mode()) {
				allKeys.addAll(listOf(Key("", spAct = SpecialAction.KEYCODE_CANDIDATE, width = 8f), Key("▼", spAct = SpecialAction.SHOW_MORE), Key("123#", spAct = SpecialAction.NUM_TOGGLE)).also { currentRowSizes.add(it.size) })
				allKeys.addAll(listOf(Key("1'", kCode = KeyEvent.KEYCODE_1), Key("2ABC", kCode = KeyEvent.KEYCODE_2), Key("3DEF", kCode = KeyEvent.KEYCODE_3), Key("⌫", kCode = KeyEvent.KEYCODE_DEL, width = 0.5f), Key("⌦", kCode = KeyEvent.KEYCODE_FORWARD_DEL, width = 0.5f)).also { currentRowSizes.add(it.size) })
				allKeys.addAll(listOf(Key("4GHI", kCode = KeyEvent.KEYCODE_4), Key("5JKL", kCode = KeyEvent.KEYCODE_5), Key("6MNO", kCode = KeyEvent.KEYCODE_6), Key("←", kCode = KeyEvent.KEYCODE_DPAD_LEFT, width = 0.5f), Key("→", kCode = KeyEvent.KEYCODE_DPAD_RIGHT, width = 0.5f)).also { currentRowSizes.add(it.size) })
				allKeys.addAll(listOf(Key("7PQRS", kCode = KeyEvent.KEYCODE_7), Key("8TUV", kCode = KeyEvent.KEYCODE_8), Key("9WXYZ", kCode = KeyEvent.KEYCODE_9), Key("Home", kCode = KeyEvent.KEYCODE_MOVE_HOME, width = 0.5f), Key("End", kCode = KeyEvent.KEYCODE_MOVE_END, width = 0.5f)).also { currentRowSizes.add(it.size) })
				allKeys.addAll(listOf(Key("#", kCode = KeyEvent.KEYCODE_POUND), Key(" ", kCode = KeyEvent.KEYCODE_SPACE), Key("0", kCode = KeyEvent.KEYCODE_0, width = 0.5f), Key(".", kCode = KeyEvent.KEYCODE_PERIOD, width = 0.5f), Key("Enter↵", kCode = KeyEvent.KEYCODE_ENTER, width = 1.5f), Key("*", spAct = SpecialAction.SETTINGS, width = 0.5f)).also { currentRowSizes.add(it.size) })
				allKeys.addAll(listOf(Key("!?😄", spAct = SpecialAction.SYMBOL), Key("paste", spAct = SpecialAction.PASTE), Key("pgUp", kCode = KeyEvent.KEYCODE_PAGE_UP), Key("pgDn", kCode = KeyEvent.KEYCODE_PAGE_DOWN), Key("⌵", spAct = SpecialAction.HIDE_KEYBOARD)).also { currentRowSizes.add(it.size) })
			} else {
				if (fnActive) allKeys.addAll(listOf(Key("Esc", kCode = KeyEvent.KEYCODE_ESCAPE), Key("F1", kCode = KeyEvent.KEYCODE_F1), Key("F2", kCode = KeyEvent.KEYCODE_F2), Key("F3", kCode = KeyEvent.KEYCODE_F3), Key("F4", kCode = KeyEvent.KEYCODE_F4), Key("F5", kCode = KeyEvent.KEYCODE_F5), Key("F6", kCode = KeyEvent.KEYCODE_F6), Key("F7", kCode = KeyEvent.KEYCODE_F7), Key("F8", kCode = KeyEvent.KEYCODE_F8), Key("F9", kCode = KeyEvent.KEYCODE_F9), Key("F10", kCode = KeyEvent.KEYCODE_F10), Key("F11", kCode = KeyEvent.KEYCODE_F11), Key("F12", kCode = KeyEvent.KEYCODE_F12), Key("⌦", kCode = KeyEvent.KEYCODE_FORWARD_DEL, width = 1.5f), Key("123#", spAct = SpecialAction.NUM_TOGGLE)).also { currentRowSizes.add(it.size) })
				else allKeys.addAll(listOf(Key("", spAct = SpecialAction.KEYCODE_CANDIDATE, width = 8f), Key("▼", spAct = SpecialAction.SHOW_MORE), Key("⌦", kCode = KeyEvent.KEYCODE_FORWARD_DEL), Key("123#", spAct = SpecialAction.NUM_TOGGLE)).also { currentRowSizes.add(it.size) })
				allKeys.addAll(listOf(Key("`", shLab = "~", kCode = KeyEvent.KEYCODE_GRAVE), Key("1", shLab = "!", kCode = KeyEvent.KEYCODE_1), Key("2", shLab = "@", kCode = KeyEvent.KEYCODE_2), Key("3", shLab = "#", kCode = KeyEvent.KEYCODE_3), Key("4", shLab = "$", kCode = KeyEvent.KEYCODE_4), Key("5", shLab = "%", kCode = KeyEvent.KEYCODE_5), Key("6", shLab = "^", kCode = KeyEvent.KEYCODE_6), Key("7", shLab = "&", kCode = KeyEvent.KEYCODE_7), Key("8", shLab = "*", kCode = KeyEvent.KEYCODE_8), Key("9", shLab = "(", kCode = KeyEvent.KEYCODE_9), Key("0", shLab = ")", kCode = KeyEvent.KEYCODE_0), Key("-", shLab = "_", kCode = KeyEvent.KEYCODE_MINUS), Key("=", shLab = "+", kCode = KeyEvent.KEYCODE_EQUALS), Key("⌫", kCode = KeyEvent.KEYCODE_DEL, width = 1.5f)).also { currentRowSizes.add(it.size) })
				allKeys.addAll(listOf(Key("↹", kCode = KeyEvent.KEYCODE_TAB), Key("q", kCode = KeyEvent.KEYCODE_Q), Key("w", kCode = KeyEvent.KEYCODE_W), Key("e", kCode = KeyEvent.KEYCODE_E), Key("r", kCode = KeyEvent.KEYCODE_R), Key("t", kCode = KeyEvent.KEYCODE_T), Key("y", kCode = KeyEvent.KEYCODE_Y), Key("u", kCode = KeyEvent.KEYCODE_U), Key("i", kCode = KeyEvent.KEYCODE_I), Key("o", kCode = KeyEvent.KEYCODE_O), Key("p", kCode = KeyEvent.KEYCODE_P), Key("[", shLab = "{", kCode = KeyEvent.KEYCODE_LEFT_BRACKET), Key("]", shLab = "}", kCode = KeyEvent.KEYCODE_RIGHT_BRACKET), Key("\\", shLab = "|", kCode = KeyEvent.KEYCODE_BACKSLASH)).also { currentRowSizes.add(it.size) })
				allKeys.addAll(listOf(Key("⇪Caps", spAct = SpecialAction.CAPS_TOGGLE, width = 1.1f), Key("a", kCode = KeyEvent.KEYCODE_A), Key("s", kCode = KeyEvent.KEYCODE_S), Key("d", kCode = KeyEvent.KEYCODE_D), Key("f", kCode = KeyEvent.KEYCODE_F), Key("g", kCode = KeyEvent.KEYCODE_G), Key("h", kCode = KeyEvent.KEYCODE_H), Key("j", kCode = KeyEvent.KEYCODE_J), Key("k", kCode = KeyEvent.KEYCODE_K), Key("l", kCode = KeyEvent.KEYCODE_L), Key(";", shLab = ":", kCode = KeyEvent.KEYCODE_SEMICOLON), Key("'", shLab = "\"", kCode = KeyEvent.KEYCODE_APOSTROPHE), Key("Enter↵", kCode = KeyEvent.KEYCODE_ENTER, width = 1.5f)).also { currentRowSizes.add(it.size) })
				allKeys.addAll(listOf(Key("⇧", kCode = KeyEvent.KEYCODE_SHIFT_LEFT, isModifier = true, width = 1.3f), Key("z", kCode = KeyEvent.KEYCODE_Z), Key("x", kCode = KeyEvent.KEYCODE_X), Key("c", kCode = KeyEvent.KEYCODE_C), Key("v", kCode = KeyEvent.KEYCODE_V), Key("b", kCode = KeyEvent.KEYCODE_B), Key("n", kCode = KeyEvent.KEYCODE_N), Key("m", kCode = KeyEvent.KEYCODE_M), Key(",", shLab = "<", kCode = KeyEvent.KEYCODE_COMMA), Key(".", shLab = ">", kCode = KeyEvent.KEYCODE_PERIOD), Key("/", shLab = "?", kCode = KeyEvent.KEYCODE_SLASH), Key("⇧", kCode = KeyEvent.KEYCODE_SHIFT_LEFT, isModifier = true, width = 1.3f)).also { currentRowSizes.add(it.size) })
				allKeys.addAll(listOf(Key("Ctrl", kCode = KeyEvent.KEYCODE_CTRL_LEFT, isModifier = true), Key("Alt", kCode = KeyEvent.KEYCODE_ALT_LEFT, isModifier = true), Key(" ", kCode = KeyEvent.KEYCODE_SPACE, width = 2.5f), Key("Home", kCode = KeyEvent.KEYCODE_MOVE_HOME), Key("↑", kCode = KeyEvent.KEYCODE_DPAD_UP), Key("End", kCode = KeyEvent.KEYCODE_MOVE_END), Key("pgUp", kCode = KeyEvent.KEYCODE_PAGE_UP), Key("*", spAct = SpecialAction.SETTINGS)).also { currentRowSizes.add(it.size) })
				allKeys.addAll(listOf(Key("!?😄", spAct = SpecialAction.SYMBOL), Key("Fn", spAct = SpecialAction.Fn), Key("paste", spAct = SpecialAction.PASTE, width = 2.5f), Key("←", kCode = KeyEvent.KEYCODE_DPAD_LEFT), Key("↓", kCode = KeyEvent.KEYCODE_DPAD_DOWN), Key("→", kCode = KeyEvent.KEYCODE_DPAD_RIGHT), Key("pgDn", kCode = KeyEvent.KEYCODE_PAGE_DOWN), Key("⌵", spAct = SpecialAction.HIDE_KEYBOARD)).also { currentRowSizes.add(it.size) })
			}
		}

		override fun onSizeChanged(w : Int, h : Int, oldw : Int, oldh : Int) {
			super.onSizeChanged(w, h, oldw, oldh)
			layoutKeys(w, h)
		}

		private fun layoutKeys(width : Int, height : Int) {
			buildMainLayout()
			keyRect.clear()
			candidateRect = null
			val rowsList = mutableListOf<List<Key>>()
			var idx = 0
			for (size in currentRowSizes) {
				if (idx + size <= allKeys.size) {
					rowsList.add(allKeys.subList(idx, idx + size))
					idx += size
				} else break
			}
			if (idx < allKeys.size) rowsList.add(allKeys.subList(idx, allKeys.size))
			val rowHeight = height / rowsList.size.toFloat()
			var top = 0f
			for (rowIdx in rowsList.indices) {
				val rowKeys = rowsList[rowIdx]
				val sumWidth = rowKeys.sumOf { it.width.toDouble() }.toFloat()
				var left = 0f
				val bottom = top + rowHeight
				for (key in rowKeys) {
					val keyWidth = width * (key.width / sumWidth)
					val rect = Rect(left.toInt(), top.toInt(), (left + keyWidth).toInt(), bottom.toInt())
					keyRect.add(rect)
					if (key.spAct == SpecialAction.KEYCODE_CANDIDATE) candidateRect = rect
					left += keyWidth
				}
				top += rowHeight
			}
		}

		override fun onDraw(canvas : Canvas) {
			super.onDraw(canvas)
			for ((i, rect) in keyRect.withIndex()) {
				val key = allKeys[i]
				if (! fnActive && ! numActive && key.spAct == SpecialAction.KEYCODE_CANDIDATE) {
					canvas.drawRect(rect, bgPaint)
					continue
				}
				val bgColor = when {
					pointerMap.values.contains(i) -> 0xFF0080FF.toInt()
					key.kCode == KeyEvent.KEYCODE_SPACE || key.kCode == KeyEvent.KEYCODE_ENTER -> 0xFF404040.toInt()
					key.isModifier && when (key.kCode) {
						KeyEvent.KEYCODE_SHIFT_LEFT -> shiftPressed
						KeyEvent.KEYCODE_CTRL_LEFT -> ctrlPressed
						KeyEvent.KEYCODE_ALT_LEFT -> altPressed
						else -> false
					} -> 0xFF0080FF.toInt()
					key.spAct == SpecialAction.CAPS_TOGGLE && capsLock -> 0xFF0080FF.toInt()
					key.spAct == SpecialAction.NUM_TOGGLE && numActive -> 0xFF0080FF.toInt()
					key.spAct == SpecialAction.Fn && fnActive -> 0xFF0080FF.toInt()
					key.spAct != null -> 0xFF181818.toInt()
					else -> Color.BLACK
				}
				bgPaint.color = bgColor
				canvas.drawRect(rect, bgPaint)
				val label = getDisplayLabel(key)
				if (label.isNotEmpty()) {
					var size = rect.height() * 0.65f
					textPaint.textSize = size
					var textWidth = textPaint.measureText(label)
					while (textWidth > rect.width() * 0.8f && size > 1f) {
						size *= rect.width() * 0.8f / textWidth
						textPaint.textSize = size
						textWidth = textPaint.measureText(label)
					}
					size = maxOf(size, 1f)
					textPaint.textSize = size
					canvas.drawText(label, rect.exactCenterX(), rect.exactCenterY() - (textPaint.descent() + textPaint.ascent()) / 2, textPaint)
					if (! shiftPressed && key.shLab != null && ! key.isLetter && key.kCode != KeyEvent.KEYCODE_SPACE && ! key.isModifier && key.spAct == null) {
						shiftHintPaint.textSize = size * 0.65f
						canvas.drawText(key.shLab, rect.right.toFloat() - 4, rect.top.toFloat() + size * 0.5f + 2, shiftHintPaint)
					}
				}
			}
		}

		override fun performClick() : Boolean {
			super.performClick()
			return true
		}

		override fun onTouchEvent(event : MotionEvent) : Boolean {
			when (event.actionMasked) {
				MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
					val index = event.actionIndex
					var keyIndex = - 1
					for ((i, rect) in keyRect.withIndex()) {
						if (rect.contains(event.getX(index).toInt(), event.getY(index).toInt())) {
							keyIndex = i
							break
						}
					}
					if (keyIndex != - 1) {
						val key = allKeys[keyIndex]
						if (key.spAct == SpecialAction.KEYCODE_CANDIDATE) return false
						pointerMap[event.getPointerId(index)] = keyIndex
						if (key.isModifier) {
							val keyCode = key.kCode
							oldStickyState[keyCode] = when (keyCode) {
								KeyEvent.KEYCODE_SHIFT_LEFT -> shiftPressed
								KeyEvent.KEYCODE_CTRL_LEFT -> ctrlPressed
								KeyEvent.KEYCODE_ALT_LEFT -> altPressed
								else -> false
							}
							stickyMode[keyCode] = false
							setModifierState(keyCode, true)
							imeSendKeyEvent(keyCode, KeyEvent.ACTION_DOWN, currentMetaState())
							keyDownTimes[keyCode] = SystemClock.uptimeMillis()
							invalidate()
						} else {
							cancelLongPressTimer()
							longPressKeyIndex = keyIndex
							isLongPressTriggered = false
							longPressAction = LongPressAction.NONE
							val key = allKeys[keyIndex]
							when (key.kCode) {
								KeyEvent.KEYCODE_SPACE -> {
									val runnable = Runnable {
										isLongPressTriggered = true
										cancelLongPressTimer()
										if (isPasswordMode || isNumberMode) return@Runnable
										this@KeyboardView.post {
											val popupMenu = PopupMenu(this@PCKeyboardIME, this@KeyboardView)
											popupMenu.gravity = Gravity.CENTER
											if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) popupMenu.menu.setGroupDividerEnabled(true)
											popupMenu.menu.add(0, - 1, 0, "语言").isEnabled = false
											popupMenu.menu.add(0, 0, 1, "英文")
											popupMenu.menu.add(0, 1, 2, "中文")
											popupMenu.menu.add(0, 2, 3, "中文9键")
											popupMenu.menu.add(1, - 1, 10, "方案").isEnabled = false
											if (cachedSchemata.isNotEmpty()) cachedSchemata.forEachIndexed { index, schema -> popupMenu.menu.add(1, index + 100, 11 + index, schema.name) }
											else popupMenu.menu.add(1, - 2, 100, "加载方案中...").isEnabled = false
											popupMenu.setOnMenuItemClickListener { item ->
												when (item.groupId) {
													0 -> {
														currentModeIndex = item.itemId
														rebuildKeyboardAndUpdate(true)
													}
													1 -> {
														val schemaId = cachedSchemata[item.itemId - 100].id
														serviceScope.launch { rime.selectSchema(schemaId) }
														rebuildKeyboardAndUpdate(true)
													}
												}
												true
											}
											popupMenu.show()
										}
									}
									longPressRunnable = runnable
									longPressHandler.postDelayed(runnable, LONG_PRESS_THRESHOLD)
								}
								in setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_FORWARD_DEL) -> {
									longPressAction = LongPressAction.REPEAT
									val runnable = Runnable {
										isLongPressTriggered = true
										stopRepeatKey()
										val repeatRunnable = object : Runnable {
											override fun run() {
												val wasVisible = preEditText.isVisible
												imeSendKeyEvent(key.kCode, KeyEvent.ACTION_DOWN, currentMetaState())
												imeSendKeyEvent(key.kCode, KeyEvent.ACTION_UP, currentMetaState())
												if (wasVisible && preEditText.text.isEmpty()) {
													stopRepeatKey()
													return
												}
												if (isLongPressTriggered && pointerMap.isNotEmpty()) {
													longPressHandler.postDelayed(this, 50L)
												}
											}
										}
										repeatKeyRunnable = repeatRunnable
										longPressHandler.post(repeatRunnable)
									}
									longPressRunnable = runnable
									longPressHandler.postDelayed(runnable, LONG_PRESS_THRESHOLD)
								}
								else -> longPressKeyIndex = null
							}
						}
						invalidate()
					}
				}
				MotionEvent.ACTION_MOVE -> {
					for (i in 0 until event.pointerCount) {
						val pointerId = event.getPointerId(i)
						val keyIndex = pointerMap[pointerId] ?: continue
						val rect = keyRect.getOrNull(keyIndex) ?: continue
						if (! rect.contains(event.getX(i).toInt(), event.getY(i).toInt())) {
							if (isLongPressTriggered) endLongPress()
							else cancelLongPressTimer()
							pointerMap.remove(pointerId)
							invalidate()
						}
					}
				}
				MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
					val pointerId = event.getPointerId(event.actionIndex)
					val startKeyIndex = pointerMap.remove(pointerId)
					if (startKeyIndex != null) {
						val key = allKeys[startKeyIndex]
						if (key.isModifier) {
							val keyCode = key.kCode
							val downTime = keyDownTimes.remove(keyCode)
							if (downTime != null) {
								val elapsed = SystemClock.uptimeMillis() - downTime
								val oldSticky = oldStickyState.remove(keyCode) ?: false
								if (elapsed < LONG_PRESS_THRESHOLD) {
									val newSticky = ! oldSticky
									setModifierState(keyCode, newSticky)
									if (newSticky) stickyMode[keyCode] = true
									else {
										stickyMode[keyCode] = false
										imeSendKeyEvent(keyCode, KeyEvent.ACTION_UP, currentMetaState())
									}
								} else {
									stickyMode[keyCode] = false
									setModifierState(keyCode, false)
									imeSendKeyEvent(keyCode, KeyEvent.ACTION_UP, currentMetaState())
								}
								invalidate()
							}
						} else {
							val wasLongPress = isLongPressTriggered
							cancelLongPressTimer()
							if (wasLongPress) endLongPress()
							else {
								onNormalKeyDown(key)
								performClick()
							}
						}
						invalidate()
					}
				}
				MotionEvent.ACTION_CANCEL -> {
					for ((_, keyIndex) in pointerMap) {
						val key = allKeys[keyIndex]
						if (key.isModifier) onCancelModifier(key)
					}
					pointerMap.clear()
					cancelLongPressTimer()
					resetLongPressState()
					keyDownTimes.clear()
					invalidate()
				}
			}
			return true
		}

		fun getDisplayLabel(key : Key) : String {
			if (key.spAct != null) {
				return when (key.spAct) {
					SpecialAction.CAPS_TOGGLE -> if (capsLock) "⬆Caps" else "⇪Caps"
					SpecialAction.Fn -> if (fnActive) "Fn*" else "Fn"
					SpecialAction.SETTINGS -> {
						if (isPasswordMode || isNumberMode) "*"
						else when (currentModeIndex) {
							0 -> "🌐EN"
							1 -> "🌐中"
							2 -> "🌐中9键"
							else -> "*"
						}
					}
					SpecialAction.PASTE -> if (currentModeIndex == 0) "paste" else "粘贴"
					else -> key.label
				}
			}
			if (key.isModifier) {
				return if (when (key.kCode) {
						KeyEvent.KEYCODE_SHIFT_LEFT -> shiftPressed
						KeyEvent.KEYCODE_CTRL_LEFT -> ctrlPressed
						KeyEvent.KEYCODE_ALT_LEFT -> altPressed
						else -> false
					}) {
					if (key.kCode == KeyEvent.KEYCODE_SHIFT_LEFT) "⬆" else "${key.label}*"
				} else key.label
			}
			if (key.isLetter) return if (capsLock != shiftPressed) key.label.uppercase() else key.label.lowercase()
			if (shiftPressed && key.shLab != null) return key.shLab
			return key.label
		}

		private fun cancelLongPressTimer() {
			longPressRunnable?.let { longPressHandler.removeCallbacks(it) }
			longPressRunnable = null
		}

		private fun resetLongPressState() {
			isLongPressTriggered = false
			longPressKeyIndex = null
			longPressRunnable = null
			longPressAction = LongPressAction.NONE
			stopRepeatKey()
		}

		private fun endLongPress() {
			if (isLongPressTriggered) {
				when (longPressAction) {
					LongPressAction.REPEAT -> stopRepeatKey()
					else -> {}
				}
			}
			cancelLongPressTimer()
			resetLongPressState()
			invalidate()
		}

		private fun stopRepeatKey() {
			repeatKeyRunnable?.let { longPressHandler.removeCallbacks(it) }
			repeatKeyRunnable = null
		}

		private fun onCancelModifier(key : Key) {
			if (! key.isModifier) return
			val keyCode = key.kCode
			oldStickyState.remove(keyCode)
			stickyMode[keyCode] = false
			setModifierState(keyCode, false)
			imeSendKeyEvent(keyCode, KeyEvent.ACTION_UP, currentMetaState())
			keyDownTimes.remove(keyCode)
			invalidate()
		}

		override fun onLayout(changed : Boolean, left : Int, top : Int, right : Int, bottom : Int) {
			super.onLayout(changed, left, top, right, bottom)
			if (width > 0 && height > 0) layoutKeys(width, height)
		}
	}

	inner class CandidateAdapter(private val words : List<String>, private val isPinyin : Boolean = false) : RecyclerView.Adapter<CandidateAdapter.ViewHolder>() {
		private var sizeCache = 0f

		inner class ViewHolder(val textView : TextView) : RecyclerView.ViewHolder(textView)

		override fun onCreateViewHolder(parent : ViewGroup, viewType : Int) : ViewHolder {
			val tv = TextView(parent.context).apply {
				setTextColor(Color.WHITE)
				gravity = Gravity.CENTER
				setPadding(0, 0, 0, 0)
				isSingleLine = true
				ellipsize = null
				val pressedColor = Color.rgb(0, 128, 255)
				val normalColor = Color.DKGRAY
				val drawable = android.graphics.drawable.StateListDrawable()
				drawable.addState(intArrayOf(android.R.attr.state_pressed), pressedColor.toDrawable())
				drawable.addState(intArrayOf(), normalColor.toDrawable())
				background = drawable
				layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
			}
			tv.setOnClickListener {
				isRawInputMode = false
				val pos = (it.tag as? Int) ?: return@setOnClickListener
				val selected = words[pos]
				if (isSymbolProcessorMode && ! isPinyin) {
					currentInputConnection?.commitText(selected, 1)
					return@setOnClickListener
				}
				if (isPinyin) {
					if (isSymbolProcessorMode) {
						currentCategoryIndex = pos
						updateSymbolList()
					}
					return@setOnClickListener
				}
				if (isRimeEnabled) {
					serviceScope.launch {
						rime.selectCandidate(pos, true)
					}
					return@setOnClickListener
				}
				if (preEditText.isVisible) {
					preEditText.visibility = View.GONE
					preEditText.text.clear()
					preEditText.clearFocus()
				}
				currentInputConnection?.commitText(selected, 1)
			}
			tv.setOnLongClickListener { view ->
				val pos = (view.tag as? Int) ?: return@setOnLongClickListener false
				if (! isRimeEnabled || isSymbolProcessorMode) return@setOnLongClickListener false
				val word = words.getOrNull(pos) ?: return@setOnLongClickListener false
				val popupMenu = PopupMenu(parent.context, view)
				popupMenu.menu.add("删除\"$word\"?").setOnMenuItemClickListener {
					serviceScope.launch {
						rime.deleteCandidate(pos, true)
						updateRimeUI()
					}
					true
				}
				popupMenu.show()
				true
			}
			return ViewHolder(tv)
		}

		override fun onBindViewHolder(holder : ViewHolder, position : Int) {
			val tv = holder.textView
			tv.tag = position
			val word = words[position]
			tv.text = word
			tv.setPadding(dp2px(8), 0, dp2px(8), 0)
			if (isPinyin && isSymbolProcessorMode) tv.setBackgroundColor(if (position == currentCategoryIndex) Color.rgb(0, 128, 255) else Color.DKGRAY)
			if (sizeCache > 0) tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sizeCache)
			else tv.post {
				val finalSize = run {
					val parent = tv.parent as? RecyclerView ?: return@post
					val parentWidth = parent.width
					val parentHeight = parent.height
					if (parentWidth <= 0 || parentHeight <= 0) return@post
					val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tv.typeface }
					var low = 4f
					var high = minOf(parentWidth, parentHeight) * 0.6f
					var best = low
					while (high - low > 4f) {
						val mid = (low + high) / 2
						paint.textSize = mid
						val textWidth = paint.measureText(word)
						if (textWidth <= parentWidth && mid <= parentHeight) {
							best = mid
							low = mid
						} else high = mid
					}
					best = maxOf(best, 6f)
					sizeCache = best
					best
				}
				tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, finalSize)
			}
			val lp = tv.layoutParams
			if (lp !is RecyclerView.LayoutParams) {
				tv.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
			} else {
				lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
				lp.height = ViewGroup.LayoutParams.MATCH_PARENT
				tv.layoutParams = lp
			}
		}

		override fun getItemCount() : Int = words.size
	}

	private fun dp2px(dp : Int) : Int = (dp * resources.displayMetrics.density).toInt()
	@SuppressLint("ClickableViewAccessibility") override fun onCreateInputView() : View {
		val screenHeight = resources.displayMetrics.heightPixels
		val keyboardHeight = (screenHeight * 0.35).toInt()
		val rootLayout = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
			setBackgroundColor(Color.BLACK)
			ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
				val systemBottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom
				view.setPadding(0, 0, 0, systemBottom)
				insets
			}
		}
		preEditText = EditText(this).apply {
			layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(25))
			setText("")
			visibility = View.GONE
			setBackgroundColor(Color.rgb(96, 96, 96))
			setTextColor(Color.WHITE)
			setPadding(dp2px(8), 0, dp2px(8), 0)
		}
		preEditText.setOnTouchListener { _, event ->
			if (event.action == MotionEvent.ACTION_UP) {
				if (! isRawInputMode) {
					isRawInputMode = true
					serviceScope.launch {
						val context = rime.getContext()
						val displayText = context.input
						if (displayText.isNotEmpty()) {
							val selStart = preEditText.selectionStart
							val selEnd = preEditText.selectionEnd
							if (selStart >= 0 && selStart == selEnd) {
								rime.moveCursorPos(selStart)
								updateRimeUI()
							}
						}
					}
				} else {
					serviceScope.launch {
						val selStart = preEditText.selectionStart
						if (selStart >= 0) {
							rime.moveCursorPos(selStart)
							updateRimeUI()
						}
					}
				}
			}
			false
		}
		rootLayout.addView(preEditText, 0)
		pinyinCandidateBar = RecyclerView(this).apply {
			layoutManager = LinearLayoutManager(this@PCKeyboardIME, LinearLayoutManager.HORIZONTAL, false)
			adapter = CandidateAdapter(currentPinyinCandidates, true)
			setBackgroundColor(Color.DKGRAY)
			visibility = View.GONE
			layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp2px(36))
		}
		rootLayout.addView(pinyinCandidateBar, 1)
		keyboardContainer = FrameLayout(this).apply {
			layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight)
			setBackgroundColor(Color.BLACK)
		}
		rootLayout.addView(keyboardContainer, 2)
		keyboardView = KeyboardView(this)
		keyboardContainer.addView(keyboardView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
		candidateRecyclerView = RecyclerView(this).apply {
			layoutManager = LinearLayoutManager(this@PCKeyboardIME, LinearLayoutManager.HORIZONTAL, false)
			adapter = CandidateAdapter(currentCandidates)
			setBackgroundColor(Color.DKGRAY)
			visibility = View.GONE
		}
		keyboardContainer.addView(candidateRecyclerView)
		dropdownContainer = FrameLayout(this).apply {
			layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
			visibility = View.GONE
			setBackgroundColor(Color.BLACK)
			setOnClickListener { hideDropdown() }
		}
		dropdownScrollView = ScrollView(this).apply {
			layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
		}
		dropdownLayout = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
		}
		dropdownScrollView.addView(dropdownLayout)
		dropdownContainer.addView(dropdownScrollView)
		keyboardContainer.addView(dropdownContainer)
		keyboardView.post { updateCandidatePosition() }
		return rootLayout
	}

	@SuppressLint("NotifyDataSetChanged") private fun updateCandidatePosition() {
		val rect = keyboardView.candidateRect
		if (rect != null && ! fnActive && ! numActive && preEditText.isVisible) {
			candidateRecyclerView.visibility = View.VISIBLE
			candidateRecyclerView.layoutParams = FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
				setMargins(rect.left, rect.top, 0, 0)
			}
			(candidateRecyclerView.layoutManager as LinearLayoutManager).orientation = LinearLayoutManager.HORIZONTAL
			candidateRecyclerView.adapter?.notifyDataSetChanged()
		} else {
			candidateRecyclerView.visibility = View.GONE
		}
	}

	private fun showDropdown(refreshCandidates : Boolean = true) {
		if (refreshCandidates && ! isSymbolProcessorMode && isRimeEnabled) {
			fullCandidateRefreshJob?.cancel()
			serviceScope.launch {
				val full = rime.getCandidates(0, 1024)
				putCandidates(full.map { it.text }, emptyList())
				showDropdown(refreshCandidates = false)
			}
			return
		}
		val parentWidth = keyboardContainer.width
		val parentHeight = keyboardContainer.height
		if (parentWidth == 0 || parentHeight == 0) return
		dropdownContainer.removeAllViews()
		val rootLayout = FrameLayout(this).apply {
			layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
			setBackgroundColor(Color.BLACK)
			setPadding(0, 0, 0, 0)
		}
		val rowHeight = parentHeight / 7
		val scrollView = ScrollView(this).apply {
			layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
			setBackgroundColor(Color.BLACK)
			setPadding(0, 0, 0, 0)
		}
		rootLayout.addView(scrollView)
		val innerLayout = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
			setPadding(0, 0, 0, 0)
		}
		scrollView.addView(innerLayout)
		val collapseButton = TextView(this).apply {
			layoutParams = FrameLayout.LayoutParams(rowHeight, rowHeight).apply {
				gravity = Gravity.TOP or Gravity.END
			}
			text = "▲"
			setTextColor(Color.WHITE)
			gravity = Gravity.CENTER
			setBackgroundColor(Color.argb(128, 128, 128, 128))
			isClickable = true
			isFocusable = true
			setOnClickListener { hideDropdown() }
		}
		rootLayout.addView(collapseButton)
		val availableWidthBase = parentWidth - dp2px(8)
		val finalTextSize = minOf(parentWidth, rowHeight) * 0.6f
		val paddingPx = dp2px(4)
		var currentRow = LinearLayout(this).apply {
			orientation = LinearLayout.HORIZONTAL
			layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
			setPadding(0, 0, rowHeight, 0)
			gravity = Gravity.START
		}
		innerLayout.addView(currentRow)
		var currentRowWidth = 0f
		var isFirstRow = true
		for ((index, word) in currentCandidates.withIndex()) {
			val minWidth = if (isSymbolProcessorMode && currentCategoryName == "组合用") dp2px(96) else dp2px(36)
			val addWidth = maxOf(Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = finalTextSize }.measureText(word) + paddingPx * 2, minWidth.toFloat())
			val availableWidth = if (isFirstRow) availableWidthBase - rowHeight else availableWidthBase
			if (currentRowWidth + addWidth > availableWidth) {
				currentRow = LinearLayout(this).apply {
					orientation = LinearLayout.HORIZONTAL
					layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
					setPadding(0, 0, 0, 0)
					gravity = Gravity.START
				}
				innerLayout.addView(currentRow)
				currentRowWidth = 0f
				isFirstRow = false
			}
			val tv = TextView(this).apply {
				text = word
				tag = index
				setTextColor(Color.WHITE)
				gravity = Gravity.CENTER
				setPadding(paddingPx, 0, paddingPx, 0)
				isSingleLine = true
				ellipsize = null
				val pressedColor = Color.rgb(0, 128, 255)
				val normalColor = Color.BLACK
				val drawable = android.graphics.drawable.StateListDrawable()
				drawable.addState(intArrayOf(android.R.attr.state_pressed), pressedColor.toDrawable())
				drawable.addState(intArrayOf(), normalColor.toDrawable())
				background = drawable
				minimumWidth = minWidth
				layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
				setOnClickListener {
					if (isRimeEnabled) {
						val pos = tag as? Int ?: return@setOnClickListener
						serviceScope.launch {
							rime.selectCandidate(pos, true)
							updateRimeUI()
						}
					} else currentInputConnection?.commitText(word, 1)
					hideDropdown()
				}
				setOnLongClickListener { v ->
					val pos = v.tag as? Int ?: return@setOnLongClickListener false
					if (! isRimeEnabled || isSymbolProcessorMode) return@setOnLongClickListener false
					val word = currentCandidates.getOrNull(pos) ?: return@setOnLongClickListener false
					val popupMenu = PopupMenu(this@PCKeyboardIME, v)
					popupMenu.menu.add("删除\"$word\"?").setOnMenuItemClickListener {
						serviceScope.launch {
							rime.deleteCandidate(pos, false)
							updateRimeUI()
							showDropdown()
						}
						true
					}
					popupMenu.show()
					true
				}
			}
			tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, finalTextSize)
			currentRow.addView(tv)
			currentRow.addView(View(this).apply {
				layoutParams = LinearLayout.LayoutParams(dp2px(1), ViewGroup.LayoutParams.MATCH_PARENT)
				setBackgroundColor("#808080".toColorInt())
			})
			currentRowWidth += addWidth
		}
		dropdownContainer.addView(rootLayout)
		dropdownContainer.visibility = View.VISIBLE
	}

	private fun hideDropdown() {
		if (isSymbolProcessorMode) {
			isSymbolProcessorMode = false
			clearCandidates()
			pinyinCandidateBar.adapter = CandidateAdapter(currentPinyinCandidates, true)
			pinyinCandidateBar.visibility = View.GONE
			dropdownContainer.visibility = View.GONE
			updateCandidatePosition()
			return
		}
		dropdownContainer.visibility = View.GONE
		updateCandidatePosition()
	}

	private fun onNormalKeyDown(key : Key) {
		when {
			key.spAct != null -> {
				if (key.spAct != SpecialAction.SHOW_MORE) {
					rime.stopImmediately = true
					clearCandidates()
					serviceScope.launch {
						rime.clearComposition()
						rime.stopImmediately = false
					}
				}
				when (key.spAct) {
					SpecialAction.SETTINGS -> {
						if (isPasswordMode || isNumberMode) return
						fnActive = false
						capsLock = false
						numActive = false
						currentModeIndex = (currentModeIndex + 1) % 3
						rebuildKeyboardAndUpdate(true)
					}
					SpecialAction.HIDE_KEYBOARD -> requestHideSelf(0)
					SpecialAction.PASTE -> {
						val clipboard = getSystemService(ClipboardManager::class.java) !!
						val clip = clipboard.primaryClip
						if (clip != null && clip.itemCount > 0) {
							imeCommitText(clip.getItemAt(0).text.toString())
						}
					}
					SpecialAction.NUM_TOGGLE -> {
						if (isNumberMode) return
						if (! numActive) {
							beforeNumModeIndex = currentModeIndex
							numActive = true
						} else {
							numActive = false
							currentModeIndex = beforeNumModeIndex
						}
						rebuildKeyboardAndUpdate(true)
					}
					SpecialAction.CAPS_TOGGLE -> {
						capsLock = ! capsLock
						imeSendKeyEvent(KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.ACTION_DOWN, currentMetaState())
						imeSendKeyEvent(KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.ACTION_UP, currentMetaState())
						keyboardView.invalidate()
					}
					SpecialAction.Fn -> {
						fnActive = ! fnActive
						rebuildKeyboardAndUpdate(true)
					}
					SpecialAction.SHOW_MORE -> showDropdown()
					SpecialAction.SYMBOL -> {
						val text = preEditText.text.toString()
						if (text.isNotEmpty()) currentInputConnection?.commitText(text, 1)
						isSymbolProcessorMode = true
						clearCandidates()
						candidateRecyclerView.visibility = View.GONE
						preEditText.visibility = View.GONE
						preEditText.clearFocus()
						pinyinCandidateBar.adapter = CandidateAdapter(symbolProcessor.getCategories(), true)
						pinyinCandidateBar.visibility = View.VISIBLE
						(pinyinCandidateBar.layoutParams as LinearLayout.LayoutParams).height = dp2px(36)
						updateSymbolList()
					}
					else -> {}
				}
			}
			else -> {
				if (ctrlPressed || altPressed) {
					imeSendKeyEvent(key.kCode, KeyEvent.ACTION_DOWN, currentMetaState())
					imeSendKeyEvent(key.kCode, KeyEvent.ACTION_UP, currentMetaState())
				} else {
					val label = keyboardView.getDisplayLabel(key)
					if (isT9Mode() && key.kCode in KeyEvent.KEYCODE_0 .. KeyEvent.KEYCODE_9) imeCommitText(label[0].toString())
					else if (label.length == 1 && label[0].code in 32 .. 126) imeCommitText(label)
					else {
						imeSendKeyEvent(key.kCode, KeyEvent.ACTION_DOWN, currentMetaState())
						imeSendKeyEvent(key.kCode, KeyEvent.ACTION_UP, currentMetaState())
					}
				}
				val isNavKey = key.kCode in setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN)
				if (isNavKey) releaseStickyModifiers(KeyEvent.KEYCODE_SHIFT_LEFT)
				else releaseStickyModifiers()
			}
		}
	}

	private fun setModifierState(keyCode : Int, pressed : Boolean) {
		when (keyCode) {
			KeyEvent.KEYCODE_SHIFT_LEFT -> shiftPressed = pressed
			KeyEvent.KEYCODE_CTRL_LEFT -> ctrlPressed = pressed
			KeyEvent.KEYCODE_ALT_LEFT -> altPressed = pressed
		}
	}

	private fun currentMetaState() : Int {
		var meta = 0
		if (shiftPressed) meta = meta or KeyEvent.META_SHIFT_ON
		if (ctrlPressed) meta = meta or KeyEvent.META_CTRL_ON
		if (altPressed) meta = meta or KeyEvent.META_ALT_ON
		if (capsLock) meta = meta or KeyEvent.META_CAPS_LOCK_ON
		if (numActive) meta = meta or KeyEvent.META_NUM_LOCK_ON
		return meta
	}

	private fun releaseStickyModifiers(exceptKeyCode : Int? = null) {
		var changed = false
		listOf(KeyEvent.KEYCODE_SHIFT_LEFT to { shiftPressed }, KeyEvent.KEYCODE_CTRL_LEFT to { ctrlPressed }, KeyEvent.KEYCODE_ALT_LEFT to { altPressed }).forEach { (keyCode, stateGetter) ->
			if (exceptKeyCode == keyCode) return@forEach
			if (stickyMode[keyCode] == true && stateGetter()) {
				stickyMode[keyCode] = false
				setModifierState(keyCode, false)
				imeSendKeyEvent(keyCode, KeyEvent.ACTION_UP, currentMetaState())
				changed = true
			}
		}
		if (changed) keyboardView.invalidate()
	}

	override fun onStartInputView(editorInfo : EditorInfo?, restarting : Boolean) {
		rime.stopImmediately = false
		clearCandidates()
		isSymbolProcessorMode = false
		listOf(KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_META_LEFT).forEach { keyCode ->
			if (stickyMode[keyCode] == true) {
				stickyMode[keyCode] = false
				setModifierState(keyCode, false)
				imeSendKeyEvent(keyCode, KeyEvent.ACTION_UP, currentMetaState())
			}
		}
		super.onStartInputView(editorInfo, restarting)
		preEditText.setText("")
		if (editorInfo == null) {
			currentInputTypeCategory = typeText
			isNumberMode = false
			isPasswordMode = false
		} else {
			val inputType = editorInfo.inputType
			val category = inputType and InputType.TYPE_MASK_CLASS
			val variation = inputType and InputType.TYPE_MASK_VARIATION
			when (category) {
				InputType.TYPE_CLASS_NUMBER -> {
					currentInputTypeCategory = 1
					isNumberMode = true
					isPasswordMode = false
				}
				InputType.TYPE_CLASS_TEXT -> {
					currentInputTypeCategory = if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) typePassword else typeText
					isNumberMode = false
					isPasswordMode = (currentInputTypeCategory == typePassword)
				}
				else -> {
					currentInputTypeCategory = typeText
					isNumberMode = false
					isPasswordMode = false
				}
			}
		}
		if (isPasswordMode) {
			currentModeIndex = 0
			numActive = false
		} else if (isNumberMode) numActive = true
		rebuildKeyboardAndUpdate(false)
		preEditText.clearFocus()
	}
}
