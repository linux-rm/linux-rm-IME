package com.linuxrm.ime
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.graphics.drawable.toDrawable
class PCKeyboardIME : InputMethodService() {
	enum class SpecialAction {
		SETTINGS, Fn, HIDE_KEYBOARD, ALL, COPY, CUT, PASTE, PG_UP, PG_DOWN, NUM_TOGGLE, CAPS_TOGGLE, KEYCODE_CANDIDATE
	}
	data class Key(val label : String, val shLab : String? = null, val kCode : Int = KeyEvent.KEYCODE_UNKNOWN, val width : Float = 1f, val isModifier : Boolean = false, val spAct : SpecialAction? = null) {
		val isLetter : Boolean
			get() = kCode in KeyEvent.KEYCODE_A .. KeyEvent.KEYCODE_Z
	}
	private val candidateWords = listOf("apple", "google", "linux-rm", "area", "name", "abandon", "aboard", "about", "above", "across", "action", "active", "actual", "adapt", "adjust", "admin", "advanced", "agent", "agile", "algorithm", "android", "application", "architecture", "array", "async", "atomic")
	private lateinit var keyboardView : KeyboardView
	private lateinit var candidateRecyclerView : RecyclerView
	private lateinit var keyboardContainer : FrameLayout
	private var shiftPressed = false
	private var ctrlPressed = false
	private var altPressed = false
	private var metaPressed = false
	private var capsLock = false
	private var numActive = false
	private var fnActive = false
	private val typeText = 0
	private val typePassword = 2
	private var currentInputTypeCategory = typeText
	private var isNumberMode = false
	private val oldStickyState = mutableMapOf<Int, Boolean>()
	private val keyDownTimes = mutableMapOf<Int, Long>()
	private val stickyMode = mutableMapOf<Int, Boolean>()
	inner class KeyboardView(context : Context) : View(context) {
		private val allKeys = mutableListOf<Key>()
		private val keyRect = mutableListOf<Rect>()
		private val pointerMap = mutableMapOf<Int, Int>()
		var candidateRect : Rect? = null
			private set
		private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
		private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.WHITE
			textAlign = Paint.Align.CENTER
		}
		private val shiftHintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
			color = Color.argb(160, 255, 255, 255)
			textAlign = Paint.Align.RIGHT
		}
		init {
			buildMainLayout()
		}
		fun buildMainLayout() {
			allKeys.clear()
			if (numActive) {
				allKeys.clear()
				allKeys.addAll(listOf(Key("Num", spAct = SpecialAction.NUM_TOGGLE), Key("/", kCode = KeyEvent.KEYCODE_SLASH), Key("*", kCode = KeyEvent.KEYCODE_STAR), Key("-", kCode = KeyEvent.KEYCODE_MINUS)))
				allKeys.addAll(listOf(Key("7", kCode = KeyEvent.KEYCODE_7), Key("8", kCode = KeyEvent.KEYCODE_8), Key("9", kCode = KeyEvent.KEYCODE_9), Key("+", kCode = KeyEvent.KEYCODE_PLUS)))
				allKeys.addAll(listOf(Key("4", kCode = KeyEvent.KEYCODE_4), Key("5", kCode = KeyEvent.KEYCODE_5), Key("6", kCode = KeyEvent.KEYCODE_6), Key("⌫", kCode = KeyEvent.KEYCODE_DEL)))
				allKeys.addAll(listOf(Key("1", kCode = KeyEvent.KEYCODE_1), Key("2", kCode = KeyEvent.KEYCODE_2), Key("3", kCode = KeyEvent.KEYCODE_3), Key("⌦", kCode = KeyEvent.KEYCODE_FORWARD_DEL)))
				allKeys.addAll(listOf(Key("#", kCode = KeyEvent.KEYCODE_POUND), Key("0", kCode = KeyEvent.KEYCODE_0), Key(".", kCode = KeyEvent.KEYCODE_PERIOD), Key("↵", kCode = KeyEvent.KEYCODE_ENTER)))
				allKeys.addAll(listOf(Key("Home", kCode = KeyEvent.KEYCODE_MOVE_HOME), Key("←", kCode = KeyEvent.KEYCODE_DPAD_LEFT), Key("→", kCode = KeyEvent.KEYCODE_DPAD_RIGHT), Key("End", kCode = KeyEvent.KEYCODE_MOVE_END)))
				allKeys.addAll(listOf(Key("all", spAct = SpecialAction.ALL), Key("copy", spAct = SpecialAction.COPY), Key("cut", spAct = SpecialAction.CUT), Key("paste", spAct = SpecialAction.PASTE), Key("pgUp", spAct = SpecialAction.PG_UP), Key("pgDn", spAct = SpecialAction.PG_DOWN)))
			} else {
				if (fnActive) allKeys.addAll(listOf(Key("Esc", kCode = KeyEvent.KEYCODE_ESCAPE), Key("F1", kCode = KeyEvent.KEYCODE_F1), Key("F2", kCode = KeyEvent.KEYCODE_F2), Key("F3", kCode = KeyEvent.KEYCODE_F3), Key("F4", kCode = KeyEvent.KEYCODE_F4), Key("F5", kCode = KeyEvent.KEYCODE_F5), Key("F6", kCode = KeyEvent.KEYCODE_F6), Key("F7", kCode = KeyEvent.KEYCODE_F7), Key("F8", kCode = KeyEvent.KEYCODE_F8), Key("F9", kCode = KeyEvent.KEYCODE_F9), Key("F10", kCode = KeyEvent.KEYCODE_F10), Key("F11", kCode = KeyEvent.KEYCODE_F11), Key("F12", kCode = KeyEvent.KEYCODE_F12), Key("Num", spAct = SpecialAction.NUM_TOGGLE, width = 1.5f), Key("⌦", kCode = KeyEvent.KEYCODE_FORWARD_DEL, width = 1.5f)))
				else allKeys.addAll(listOf(Key("Esc", kCode = KeyEvent.KEYCODE_ESCAPE), Key("Error: Failed to load candidate box.", spAct = SpecialAction.KEYCODE_CANDIDATE, width = 12f), Key("Num", spAct = SpecialAction.NUM_TOGGLE, width = 1.5f), Key("⌦", kCode = KeyEvent.KEYCODE_FORWARD_DEL, width = 1.5f)))
				allKeys.addAll(listOf(Key("`", shLab = "~", kCode = KeyEvent.KEYCODE_GRAVE), Key("1", shLab = "!", kCode = KeyEvent.KEYCODE_1), Key("2", shLab = "@", kCode = KeyEvent.KEYCODE_2), Key("3", shLab = "#", kCode = KeyEvent.KEYCODE_3), Key("4", shLab = "$", kCode = KeyEvent.KEYCODE_4), Key("5", shLab = "%", kCode = KeyEvent.KEYCODE_5), Key("6", shLab = "^", kCode = KeyEvent.KEYCODE_6), Key("7", shLab = "&", kCode = KeyEvent.KEYCODE_7), Key("8", shLab = "*", kCode = KeyEvent.KEYCODE_8), Key("9", shLab = "(", kCode = KeyEvent.KEYCODE_9), Key("0", shLab = ")", kCode = KeyEvent.KEYCODE_0), Key("-", shLab = "_", kCode = KeyEvent.KEYCODE_MINUS), Key("=", shLab = "+", kCode = KeyEvent.KEYCODE_EQUALS), Key("⌫", kCode = KeyEvent.KEYCODE_DEL, width = 1.5f)))
				allKeys.addAll(listOf(Key("↹", kCode = KeyEvent.KEYCODE_TAB, width = 1.33f), Key("q", kCode = KeyEvent.KEYCODE_Q), Key("w", kCode = KeyEvent.KEYCODE_W), Key("e", kCode = KeyEvent.KEYCODE_E), Key("r", kCode = KeyEvent.KEYCODE_R), Key("t", kCode = KeyEvent.KEYCODE_T), Key("y", kCode = KeyEvent.KEYCODE_Y), Key("u", kCode = KeyEvent.KEYCODE_U), Key("i", kCode = KeyEvent.KEYCODE_I), Key("o", kCode = KeyEvent.KEYCODE_O), Key("p", kCode = KeyEvent.KEYCODE_P), Key("[", shLab = "{", kCode = KeyEvent.KEYCODE_LEFT_BRACKET), Key("]", shLab = "}", kCode = KeyEvent.KEYCODE_RIGHT_BRACKET)))
				allKeys.addAll(listOf(Key("⇪Caps", spAct = SpecialAction.CAPS_TOGGLE, width = 1.67f), Key("a", kCode = KeyEvent.KEYCODE_A), Key("s", kCode = KeyEvent.KEYCODE_S), Key("d", kCode = KeyEvent.KEYCODE_D), Key("f", kCode = KeyEvent.KEYCODE_F), Key("g", kCode = KeyEvent.KEYCODE_G), Key("h", kCode = KeyEvent.KEYCODE_H), Key("j", kCode = KeyEvent.KEYCODE_J), Key("k", kCode = KeyEvent.KEYCODE_K), Key("l", kCode = KeyEvent.KEYCODE_L), Key(";", shLab = ":", kCode = KeyEvent.KEYCODE_SEMICOLON), Key("'", shLab = "\"", kCode = KeyEvent.KEYCODE_APOSTROPHE), Key("\\", shLab = "|", kCode = KeyEvent.KEYCODE_BACKSLASH)))
				allKeys.addAll(listOf(Key("⇧Shift", kCode = KeyEvent.KEYCODE_SHIFT_LEFT, isModifier = true, width = 2f), Key("z", kCode = KeyEvent.KEYCODE_Z), Key("x", kCode = KeyEvent.KEYCODE_X), Key("c", kCode = KeyEvent.KEYCODE_C), Key("v", kCode = KeyEvent.KEYCODE_V), Key("b", kCode = KeyEvent.KEYCODE_B), Key("n", kCode = KeyEvent.KEYCODE_N), Key("m", kCode = KeyEvent.KEYCODE_M), Key(",", shLab = "<", kCode = KeyEvent.KEYCODE_COMMA), Key(".", shLab = ">", kCode = KeyEvent.KEYCODE_PERIOD), Key("/", shLab = "?", kCode = KeyEvent.KEYCODE_SLASH), Key("Enter↵", kCode = KeyEvent.KEYCODE_ENTER, width = 2f)))
				allKeys.addAll(listOf(Key("Ctrl", kCode = KeyEvent.KEYCODE_CTRL_LEFT, isModifier = true), Key("Fn", spAct = SpecialAction.Fn, width = 0.7f), Key("Alt", kCode = KeyEvent.KEYCODE_ALT_LEFT, isModifier = true, width = 0.8f), Key("space", kCode = KeyEvent.KEYCODE_SPACE, width = 1.5f), Key("Home", kCode = KeyEvent.KEYCODE_MOVE_HOME), Key("↑", kCode = KeyEvent.KEYCODE_DPAD_UP), Key("End", kCode = KeyEvent.KEYCODE_MOVE_END), Key("pgUp", spAct = SpecialAction.PG_UP), Key("\uD83C\uDF10", spAct = SpecialAction.SETTINGS)))
				if (currentInputTypeCategory == typePassword) allKeys.addAll(listOf(Key("all", spAct = SpecialAction.ALL), Key("", width = 0f), Key("", width = 0f), Key("paste", spAct = SpecialAction.PASTE), Key("←", kCode = KeyEvent.KEYCODE_DPAD_LEFT), Key("↓", kCode = KeyEvent.KEYCODE_DPAD_DOWN), Key("→", kCode = KeyEvent.KEYCODE_DPAD_RIGHT), Key("pgDn", spAct = SpecialAction.PG_DOWN), Key("⌵", spAct = SpecialAction.HIDE_KEYBOARD)))
				else allKeys.addAll(listOf(Key("all", spAct = SpecialAction.ALL), Key("copy", spAct = SpecialAction.COPY), Key("cut", spAct = SpecialAction.CUT), Key("paste", spAct = SpecialAction.PASTE), Key("←", kCode = KeyEvent.KEYCODE_DPAD_LEFT), Key("↓", kCode = KeyEvent.KEYCODE_DPAD_DOWN), Key("→", kCode = KeyEvent.KEYCODE_DPAD_RIGHT), Key("pgDn", spAct = SpecialAction.PG_DOWN), Key("⌵", spAct = SpecialAction.HIDE_KEYBOARD)))
			}
		}
		override fun onSizeChanged(w : Int, h : Int, oldw : Int, oldh : Int) {
			super.onSizeChanged(w, h, oldw, oldh)
			layoutKeys(w, h)
		}
		private fun layoutKeys(width : Int, height : Int) {
			buildMainLayout()
			keyRect.clear()
			val rowSizes = if (numActive) listOf(4, 4, 4, 4, 4, 4, 6)
			else if (fnActive) listOf(15, 14, 13, 13, 12, 9, 9)
			else listOf(4, 14, 13, 13, 12, 9, 9)
			val rowsList = mutableListOf<List<Key>>()
			var idx = 0
			for (size in rowSizes) {
				if (idx + size <= allKeys.size) {
					rowsList.add(allKeys.subList(idx, idx + size))
					idx += size
				} else break
			}
			if (idx < allKeys.size) rowsList.add(allKeys.subList(idx, allKeys.size))
			val rowHeight = height / rowsList.size.toFloat()
			var top = 0f
			candidateRect = null
			for (rowIdx in rowsList.indices) {
				val rowKeys = rowsList[rowIdx]
				val sumWidth = rowKeys.sumOf { it.width.toDouble() }.toFloat()
				var left = 0f
				val bottom = top + rowHeight
				for (key in rowKeys) {
					val keyWidth = width * (key.width / sumWidth)
					val rect = Rect(left.toInt(), top.toInt(), (left + keyWidth).toInt(), bottom.toInt())
					keyRect.add(rect)
					if (! fnActive && ! numActive && key.spAct == SpecialAction.KEYCODE_CANDIDATE) candidateRect = rect
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
					key.kCode == KeyEvent.KEYCODE_SPACE -> 0xFF404040.toInt()
					key.isModifier && when (key.kCode) {
						KeyEvent.KEYCODE_SHIFT_LEFT -> shiftPressed
						KeyEvent.KEYCODE_CTRL_LEFT -> ctrlPressed
						KeyEvent.KEYCODE_ALT_LEFT -> altPressed
						KeyEvent.KEYCODE_META_LEFT -> metaPressed
						else -> false
					} -> 0xFF0080FF.toInt()
					key.spAct == SpecialAction.CAPS_TOGGLE && capsLock -> 0xFF0080FF.toInt()
					key.spAct == SpecialAction.NUM_TOGGLE && numActive -> 0xFF0080FF.toInt()
					key.spAct == SpecialAction.Fn && fnActive -> 0xFF0080FF.toInt()
					else -> Color.BLACK
				}
				bgPaint.color = bgColor
				canvas.drawRect(rect, bgPaint)
				val label = getDisplayLabel(key)
				if (label.isNotEmpty()) {
					val maxWidth = rect.width() * 0.8f
					val maxHeight = rect.height() * 0.5f
					var size = maxHeight
					textPaint.textSize = size
					var textWidth = textPaint.measureText(label)
					while (textWidth > maxWidth && size > 1f) {
						size *= maxWidth / textWidth
						textPaint.textSize = size
						textWidth = textPaint.measureText(label)
					}
					size = maxOf(size, 1f)
					textPaint.textSize = size
					val centerX = rect.exactCenterX()
					val centerY = rect.exactCenterY() - (textPaint.descent() + textPaint.ascent()) / 2
					canvas.drawText(label, centerX, centerY, textPaint)
					if (! shiftPressed && key.shLab != null && ! key.isLetter && key.kCode != KeyEvent.KEYCODE_SPACE && ! key.isModifier && key.spAct == null) {
						val hintSize = size * 0.6f
						shiftHintPaint.textSize = hintSize
						val x = rect.right.toFloat() - 4
						val y = rect.top.toFloat() + hintSize + 2
						canvas.drawText(key.shLab, x, y, shiftHintPaint)
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
					val keyIndex = findKeyIndex(event.getX(index), event.getY(index))
					if (keyIndex != - 1) {
						val key = allKeys[keyIndex]
						if (key.spAct == SpecialAction.KEYCODE_CANDIDATE) return false
						pointerMap[event.getPointerId(index)] = keyIndex
						if (key.isModifier) onModifierDown(key)
						invalidate()
					}
				}
				MotionEvent.ACTION_MOVE -> {
					for (i in 0 until event.pointerCount) {
						val pointerId = event.getPointerId(i)
						val keyIndex = pointerMap[pointerId] ?: continue
						val rect = keyRect.getOrNull(keyIndex) ?: continue
						val x = event.getX(i).toInt()
						val y = event.getY(i).toInt()
						if (! rect.contains(x, y)) {
							val key = allKeys[keyIndex]
							if (key.isModifier) {
								onCancelModifier(key)
							}
							pointerMap.remove(pointerId)
							invalidate()
						}
					}
				}
				MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
					val index = event.actionIndex
					val pointerId = event.getPointerId(index)
					val startKeyIndex = pointerMap.remove(pointerId)
					if (startKeyIndex != null) {
						val key = allKeys[startKeyIndex]
						val currentKeyIndex = findKeyIndex(event.getX(index), event.getY(index))
						val isSame = currentKeyIndex == startKeyIndex
						if (key.isModifier) onModifierUp(key)
						else if (isSame) {
							onNormalKeyDown(key)
							performClick()
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
					invalidate()
				}
			}
			return true
		}
		private fun findKeyIndex(x : Float, y : Float) : Int {
			for ((i, rect) in keyRect.withIndex()) if (rect.contains(x.toInt(), y.toInt())) return i
			return - 1
		}
		fun getDisplayLabel(key : Key) : String {
			if (key.spAct != null) {
				return when (key.spAct) {
					SpecialAction.CAPS_TOGGLE -> if (capsLock) "⬆Caps" else "⇪Caps"
					SpecialAction.NUM_TOGGLE -> if (numActive) "NumLock*" else "Num"
					SpecialAction.Fn -> if (fnActive) "Fn*" else "Fn"
					else -> key.label
				}
			}
			if (key.isModifier) {
				val active = when (key.kCode) {
					KeyEvent.KEYCODE_SHIFT_LEFT -> shiftPressed
					KeyEvent.KEYCODE_CTRL_LEFT -> ctrlPressed
					KeyEvent.KEYCODE_ALT_LEFT -> altPressed
					KeyEvent.KEYCODE_META_LEFT -> metaPressed
					else -> false
				}
				return if (active) {
					if (key.kCode == KeyEvent.KEYCODE_SHIFT_LEFT) "⬆Shift" else "${key.label}*"
				} else key.label
			}
			if (key.kCode == KeyEvent.KEYCODE_SPACE) return " "
			val shift = shiftPressed
			val caps = capsLock
			if (key.isLetter) {
				val upper = caps != shift
				return if (upper) key.label.uppercase() else key.label.lowercase()
			}
			if (shift && key.shLab != null) return key.shLab
			return key.label
		}
		override fun onLayout(changed : Boolean, left : Int, top : Int, right : Int, bottom : Int) {
			super.onLayout(changed, left, top, right, bottom)
			if (width > 0 && height > 0) layoutKeys(width, height)
		}
	}
	inner class CandidateAdapter(private val words : List<String>) : RecyclerView.Adapter<CandidateAdapter.ViewHolder>() {
		inner class ViewHolder(val textView : TextView) : RecyclerView.ViewHolder(textView)
		override fun onCreateViewHolder(parent : ViewGroup, viewType : Int) : ViewHolder {
			val tv = TextView(parent.context).apply {
				setTextColor(Color.WHITE)
				gravity = Gravity.CENTER
				setPadding(dp2px(12), 0, dp2px(12), 0)
				setBackgroundColor(Color.BLACK)
				val pressedColor = Color.rgb(0, 128, 255)
				val states = arrayOf(intArrayOf(android.R.attr.state_pressed) to pressedColor, intArrayOf() to Color.TRANSPARENT)
				val drawable = android.graphics.drawable.StateListDrawable()
				states.forEach { (stateSet, color) ->
					drawable.addState(stateSet, color.toDrawable())
				}
				background = drawable
				layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT)
			}
			tv.setOnClickListener {
				val position = (it.tag as? Int) ?: return@setOnClickListener
				val word = words[position]
				currentInputConnection?.commitText(word, 1)
			}
			return ViewHolder(tv)
		}
		override fun onBindViewHolder(holder : ViewHolder, position : Int) {
			val tv = holder.textView
			tv.tag = position
			tv.text = words[position]
			tv.post {
				val w = tv.width
				val h = tv.height
				if (w > 0 && h > 0) adjustTextSize(tv, w, h)
			}
		}
		private fun adjustTextSize(tv : TextView, w : Int, h : Int) {
			val text = tv.text.toString()
			if (text.isEmpty()) return
			val maxWidth = w * 0.8f
			val maxHeight = h * 0.7f
			var size = maxHeight
			val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = tv.typeface }
			paint.textSize = size
			if (paint.measureText(text) > maxWidth) size *= maxWidth / paint.measureText(text)
			size = maxOf(size, dp2px(10).toFloat())
			tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, size)
		}
		override fun getItemCount() : Int = words.size
		private fun dp2px(dp : Int) : Int = (dp * resources.displayMetrics.density).toInt()
	}
	override fun onEvaluateFullscreenMode() : Boolean {
		return false
	}
	override fun onEvaluateInputViewShown() : Boolean {
		super.onEvaluateInputViewShown()
		return true
	}
	override fun onCreateInputView() : View {
		val screenHeight = resources.displayMetrics.heightPixels
		val keyboardHeight = (screenHeight * 0.35).toInt().coerceAtLeast(300)
		val rootLayout = LinearLayout(this).apply {
			orientation = LinearLayout.VERTICAL
			layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
			setBackgroundColor(Color.TRANSPARENT)
		}
		keyboardContainer = FrameLayout(this).apply {
			layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, keyboardHeight)
			setBackgroundColor(Color.BLACK)
		}
		rootLayout.addView(keyboardContainer)
		keyboardView = KeyboardView(this)
		keyboardContainer.addView(keyboardView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
		candidateRecyclerView = RecyclerView(this).apply {
			layoutManager = LinearLayoutManager(this@PCKeyboardIME, LinearLayoutManager.HORIZONTAL, false)
			adapter = CandidateAdapter(candidateWords)
			visibility = View.GONE
		}
		keyboardContainer.addView(candidateRecyclerView)
		keyboardView.post {
			updateCandidatePosition()
		}
		return rootLayout
	}
	private fun updateCandidatePosition() {
		if (currentInputTypeCategory == typePassword) {
			candidateRecyclerView.visibility = View.GONE
			return
		}
		val rect = keyboardView.candidateRect
		if (rect != null && ! fnActive && ! numActive) {
			candidateRecyclerView.layoutParams = FrameLayout.LayoutParams(rect.width(), rect.height()).apply {
				setMargins(rect.left, rect.top, 0, 0)
			}
			candidateRecyclerView.visibility = View.VISIBLE
		} else candidateRecyclerView.visibility = View.GONE
	}
	private fun toggleFn() {
		fnActive = ! fnActive
		keyboardView.buildMainLayout()
		keyboardView.requestLayout()
		keyboardView.invalidate()
		keyboardView.post { updateCandidatePosition() }
	}
	private fun onNormalKeyDown(key : Key) {
		when {
			key.spAct != null -> {
				val conn = currentInputConnection
				when (key.spAct) {
					SpecialAction.SETTINGS -> {
						val intent = packageManager.getLaunchIntentForPackage(packageName)
						intent?.let {
							it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
							startActivity(it)
						}
					}
					SpecialAction.HIDE_KEYBOARD -> requestHideSelf(0)
					SpecialAction.ALL -> {
						sendKeyEvent(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_DOWN, KeyEvent.META_CTRL_ON)
						sendKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_DOWN, KeyEvent.META_CTRL_ON)
						sendKeyEvent(KeyEvent.KEYCODE_A, KeyEvent.ACTION_UP, KeyEvent.META_CTRL_ON)
						sendKeyEvent(KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.ACTION_UP, KeyEvent.META_CTRL_ON)
					}
					SpecialAction.COPY -> {
						val text = conn?.getSelectedText(0) ?: ""
						if (text.isNotEmpty()) {
							val clipboard = getSystemService(ClipboardManager::class.java) !!
							clipboard.setPrimaryClip(ClipData.newPlainText("copied", text))
						}
					}
					SpecialAction.CUT -> {
						val text = conn?.getSelectedText(0) ?: ""
						if (text.isNotEmpty()) {
							val clipboard = getSystemService(ClipboardManager::class.java) !!
							clipboard.setPrimaryClip(ClipData.newPlainText("cut", text))
							conn?.commitText("", 1)
						}
					}
					SpecialAction.PASTE -> {
						val clipboard = getSystemService(ClipboardManager::class.java) !!
						val clip = clipboard.primaryClip
						if (clip != null && clip.itemCount > 0) {
							val text = clip.getItemAt(0).text.toString()
							conn?.commitText(text, 1)
						}
					}
					SpecialAction.PG_UP -> {
						sendKeyEvent(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.ACTION_DOWN, currentMetaState())
						sendKeyEvent(KeyEvent.KEYCODE_PAGE_UP, KeyEvent.ACTION_UP, currentMetaState())
					}
					SpecialAction.PG_DOWN -> {
						sendKeyEvent(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.ACTION_DOWN, currentMetaState())
						sendKeyEvent(KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.ACTION_UP, currentMetaState())
					}
					SpecialAction.NUM_TOGGLE -> {
						if (isNumberMode) return
						numActive = ! numActive
						keyboardView.requestLayout()
						keyboardView.invalidate()
						keyboardView.post { updateCandidatePosition() }
					}
					SpecialAction.CAPS_TOGGLE -> {
						capsLock = ! capsLock
						sendKeyEvent(KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.ACTION_DOWN, currentMetaState())
						sendKeyEvent(KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.ACTION_UP, currentMetaState())
						keyboardView.invalidate()
					}
					SpecialAction.Fn -> {
						toggleFn()
					}
					else -> {}
				}
			}
			else -> {
				if (ctrlPressed || altPressed || metaPressed) {
					val meta = currentMetaState()
					sendKeyEvent(key.kCode, KeyEvent.ACTION_DOWN, meta)
					sendKeyEvent(key.kCode, KeyEvent.ACTION_UP, meta)
				} else {
					val label = keyboardView.getDisplayLabel(key)
					if (label.length == 1 && label[0].code in 32 .. 126) currentInputConnection?.commitText(label, 1)
					else {
						val meta = currentMetaState()
						sendKeyEvent(key.kCode, KeyEvent.ACTION_DOWN, meta)
						sendKeyEvent(key.kCode, KeyEvent.ACTION_UP, meta)
					}
				}
				releaseStickyModifiers()
			}
		}
	}
	private fun onModifierDown(key : Key) {
		val keyCode = key.kCode
		oldStickyState[keyCode] = when (keyCode) {
			KeyEvent.KEYCODE_SHIFT_LEFT -> shiftPressed
			KeyEvent.KEYCODE_CTRL_LEFT -> ctrlPressed
			KeyEvent.KEYCODE_ALT_LEFT -> altPressed
			KeyEvent.KEYCODE_META_LEFT -> metaPressed
			else -> false
		}
		stickyMode[keyCode] = false
		setModifierState(keyCode, true)
		sendKeyEvent(keyCode, KeyEvent.ACTION_DOWN, currentMetaState())
		keyDownTimes[keyCode] = SystemClock.uptimeMillis()
	}
	private fun onModifierUp(key : Key) {
		val keyCode = key.kCode
		val downTime = keyDownTimes.remove(keyCode) ?: return
		val elapsed = SystemClock.uptimeMillis() - downTime
		val oldSticky = oldStickyState.remove(keyCode) ?: false
		if (elapsed < 300) {
			val newSticky = ! oldSticky
			setModifierState(keyCode, newSticky)
			if (newSticky) stickyMode[keyCode] = true
			else {
				stickyMode[keyCode] = false
				sendKeyEvent(keyCode, KeyEvent.ACTION_UP, currentMetaState())
			}
		} else {
			stickyMode[keyCode] = false
			setModifierState(keyCode, false)
			sendKeyEvent(keyCode, KeyEvent.ACTION_UP, currentMetaState())
		}
		keyboardView.invalidate()
	}
	private fun onCancelModifier(key : Key) {
		if (! key.isModifier) return
		val keyCode = key.kCode
		keyDownTimes.remove(keyCode)
		oldStickyState.remove(keyCode)
		stickyMode[keyCode] = false
		setModifierState(keyCode, false)
		sendKeyEvent(keyCode, KeyEvent.ACTION_UP, currentMetaState())
		keyboardView.invalidate()
	}
	private fun setModifierState(keyCode : Int, pressed : Boolean) {
		when (keyCode) {
			KeyEvent.KEYCODE_SHIFT_LEFT -> shiftPressed = pressed
			KeyEvent.KEYCODE_CTRL_LEFT -> ctrlPressed = pressed
			KeyEvent.KEYCODE_ALT_LEFT -> altPressed = pressed
			KeyEvent.KEYCODE_META_LEFT -> metaPressed = pressed
		}
	}
	private fun currentMetaState() : Int {
		var meta = 0
		if (shiftPressed) meta = meta or KeyEvent.META_SHIFT_ON
		if (ctrlPressed) meta = meta or KeyEvent.META_CTRL_ON
		if (altPressed) meta = meta or KeyEvent.META_ALT_ON
		if (metaPressed) meta = meta or KeyEvent.META_META_ON
		if (capsLock) meta = meta or KeyEvent.META_CAPS_LOCK_ON
		if (numActive) meta = meta or KeyEvent.META_NUM_LOCK_ON
		return meta
	}
	private fun sendKeyEvent(keyCode : Int, action : Int, metaState : Int) {
		val conn = currentInputConnection ?: return
		val now = SystemClock.uptimeMillis()
		val event = KeyEvent(now, now, action, keyCode, 0, metaState, 0, 0, KeyEvent.FLAG_SOFT_KEYBOARD, InputDevice.SOURCE_KEYBOARD)
		conn.sendKeyEvent(event)
	}
	private fun releaseStickyModifiers() {
		var changed = false
		listOf(KeyEvent.KEYCODE_SHIFT_LEFT to { shiftPressed }, KeyEvent.KEYCODE_CTRL_LEFT to { ctrlPressed }, KeyEvent.KEYCODE_ALT_LEFT to { altPressed }, KeyEvent.KEYCODE_META_LEFT to { metaPressed }).forEach { (keyCode, stateGetter) ->
			if (stickyMode[keyCode] == true && stateGetter()) {
				stickyMode[keyCode] = false
				setModifierState(keyCode, false)
				sendKeyEvent(keyCode, KeyEvent.ACTION_UP, currentMetaState())
				changed = true
			}
		}
		if (changed) keyboardView.invalidate()
	}
	override fun onStartInputView(editorInfo : EditorInfo?, restarting : Boolean) {
		super.onStartInputView(editorInfo, restarting)
		if (editorInfo == null) {
			currentInputTypeCategory = typeText
			isNumberMode = false
		} else {
			val inputType = editorInfo.inputType
			val category = inputType and InputType.TYPE_MASK_CLASS
			val variation = inputType and InputType.TYPE_MASK_VARIATION
			when (category) {
				InputType.TYPE_CLASS_NUMBER -> {
					currentInputTypeCategory = 1
					isNumberMode = true
					numActive = true
				}
				InputType.TYPE_CLASS_TEXT -> {
					currentInputTypeCategory = if (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) typePassword
					else typeText
					isNumberMode = false
				}
				else -> {
					currentInputTypeCategory = typeText
					isNumberMode = false
				}
			}
		}
		keyboardView.buildMainLayout()
		keyboardView.requestLayout()
		keyboardView.invalidate()
		keyboardView.post { updateCandidatePosition() }
		updateCandidatePosition()
	}
}
