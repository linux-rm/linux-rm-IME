package com.linuxrm.ime
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
class MainActivity : AppCompatActivity() {
	private lateinit var btnSetup : Button
	private lateinit var editText : EditText
	private var imeId = ""
	private var isRedirecting = false
	private val specialKeyLabels = mapOf(KeyEvent.KEYCODE_TAB to "<Tab>", KeyEvent.KEYCODE_ESCAPE to "<Esc>", KeyEvent.KEYCODE_F1 to "<F1>", KeyEvent.KEYCODE_F2 to "<F2>", KeyEvent.KEYCODE_F3 to "<F3>", KeyEvent.KEYCODE_F4 to "<F4>", KeyEvent.KEYCODE_F5 to "<F5>", KeyEvent.KEYCODE_F6 to "<F6>", KeyEvent.KEYCODE_F7 to "<F7>", KeyEvent.KEYCODE_F8 to "<F8>", KeyEvent.KEYCODE_F9 to "<F9>", KeyEvent.KEYCODE_F10 to "<F10>", KeyEvent.KEYCODE_F11 to "<F11>", KeyEvent.KEYCODE_F12 to "<F12>")
	override fun onCreate(savedInstanceState : Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(R.layout.activity_main)
		imeId = "$packageName/.PCKeyboardIME"
		btnSetup = findViewById(R.id.btnSetup)
		editText = findViewById(R.id.editText)
		editText.apply {
			isSingleLine = false
			hint = "输入法已就绪，在此测试"
			setTypeface(Typeface.MONOSPACE)
			visibility = View.GONE
			setOnKeyListener { _, keyCode, event ->
				if (event.action == KeyEvent.ACTION_DOWN) {
					val hasModifier = event.isCtrlPressed || event.isAltPressed || event.isShiftPressed || event.isMetaPressed
					if (! hasModifier && specialKeyLabels.containsKey(keyCode)) {
						val label = specialKeyLabels[keyCode] ?: return@setOnKeyListener false
						text?.insert(selectionStart, label)
						return@setOnKeyListener true
					}
				}
				false
			}
		}
		btnSetup.setOnClickListener {
			performSetupCheck()
		}
	}
	override fun onResume() {
		super.onResume()
		btnSetup.performClick()
	}
	override fun onPause() {
		super.onPause()
		isRedirecting = false
	}
	private fun performSetupCheck() {
		if (isRedirecting) return
		val currentIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
		if (currentIme == imeId) {
			if (btnSetup.visibility != View.GONE) {
				btnSetup.visibility = View.GONE
				editText.visibility = View.VISIBLE
				editText.requestFocus()
			}
			return
		}
		btnSetup.visibility = View.VISIBLE
		editText.visibility = View.GONE
		val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
		val isEnabled = imm.enabledInputMethodList.any { it.id == imeId }
		if (! isEnabled) {
			Toast.makeText(this, "Enable linux-rm IME.", Toast.LENGTH_LONG).show()
			isRedirecting = true
			startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
		} else {
			Toast.makeText(this, "Choose linux-rm IME as the default input method.", Toast.LENGTH_LONG).show()
			isRedirecting = true
			Handler(Looper.getMainLooper()).postDelayed({
				imm.showInputMethodPicker()
				Handler(Looper.getMainLooper()).postDelayed({
					isRedirecting = false
				}, 500)
			}, 300)
		}
	}
}
