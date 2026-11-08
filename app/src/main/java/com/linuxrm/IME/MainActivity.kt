package com.linuxrm.IME

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds
import androidx.core.net.toUri
import androidx.compose.material3.Switch
import androidx.core.content.edit

class MainActivity : ComponentActivity() {
	private val imeId by lazy { "$packageName/.PCKeyboardIME" }
	private var isRedirecting = false
	private val isSetup = mutableStateOf(false)
	private val hasStorage = mutableStateOf(false)
	private val showKeyBorders = mutableStateOf(false)
	private val hapticFeedback = mutableStateOf(false)
	private val prefs by lazy { getSharedPreferences("ime_prefs", MODE_PRIVATE) }
	override fun onCreate(savedInstanceState : Bundle?) {
		super.onCreate(savedInstanceState)
		showKeyBorders.value = prefs.getBoolean("show_key_borders", false)
		hapticFeedback.value = prefs.getBoolean("haptic_feedback", false)
		setContent {
			ExampleTheme {
				MainScreen(
					isSetup = isSetup.value,
					hasStorage = hasStorage.value,
					showKeyBorders = showKeyBorders.value,
					hapticFeedback = hapticFeedback.value,
					onStorageClick = { requestStoragePermission() },
					onSetupClick = { performSetupCheck() },
					onShowKeyBordersChange = {
						showKeyBorders.value = it
						prefs.edit { putBoolean("show_key_borders", it) }
					},
					onHapticFeedbackChange = {
						hapticFeedback.value = it
						prefs.edit { putBoolean("haptic_feedback", it) }
					},
				)
			}
		}
	}

	override fun onResume() {
		super.onResume()
		hasStorage.value = hasStoragePermission()
		showKeyBorders.value = prefs.getBoolean("show_key_borders", false)
		hapticFeedback.value = prefs.getBoolean("haptic_feedback", false)
		performSetupCheck()
	}

	private fun hasStoragePermission() : Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
		Environment.isExternalStorageManager()
	} else {
		checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
	}

	private fun requestStoragePermission() {
		if (hasStoragePermission()) {
			hasStorage.value = true
			return
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			Toast.makeText(application, "请开启「所有文件访问权限」", Toast.LENGTH_LONG).show()
			try {
				startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, "package:$packageName".toUri()))
			} catch (_ : Exception) {
				startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
			}
		} else {
			requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1)
		}
	}

	private fun performSetupCheck() {
		if (isRedirecting) return
		val currentIme = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
		if (currentIme == imeId) {
			isSetup.value = true
			return
		}
		isSetup.value = false
		val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
		val isEnabled = imm.enabledInputMethodList.any { it.id == imeId }
		if (! isEnabled) {
			Toast.makeText(application, "请启用 linux-rm IME", Toast.LENGTH_LONG).show()
			isRedirecting = true
			startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
			lifecycleScope.launch {
				delay(100.milliseconds)
				isRedirecting = false
			}
		} else {
			Toast.makeText(application, "请选择 linux-rm IME 作为默认输入法", Toast.LENGTH_LONG).show()
			isRedirecting = true
			lifecycleScope.launch {
				delay(100.milliseconds)
				imm.showInputMethodPicker()
				delay(100.milliseconds)
				isRedirecting = false
			}
		}
	}
}

@Composable fun MainScreen(
	isSetup : Boolean,
	hasStorage : Boolean,
	showKeyBorders : Boolean,
	hapticFeedback : Boolean,
	onStorageClick : () -> Unit,
	onSetupClick : () -> Unit,
	onShowKeyBordersChange : (Boolean) -> Unit,
	onHapticFeedbackChange : (Boolean) -> Unit,
) {
	var showShortcutDialog by remember { mutableStateOf(false) }
	AppScaffold(title = "linux-rm IME", showBack = true) { paddingValues ->
		if (! hasStorage || ! isSetup) {
			Box(modifier = Modifier
				.fillMaxSize()
				.padding(paddingValues)
				.screenPadding(), contentAlignment = Alignment.Center) {
				Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
					if (! hasStorage) {
						Text(
							text = "工作目录 /sdcard/linux-rm-ime 需要存储权限",
							style = MaterialTheme.typography.bodyMedium,
							textAlign = TextAlign.Center,
							modifier = Modifier.fillMaxWidth(),
						)
						SpacerGroup()
						Button(onClick = onStorageClick, modifier = Modifier.fillMaxWidth()) {
							Text(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) "授予所有文件访问权限" else "授予存储权限")
						}
					} else {
						Text(
							text = "请将 linux-rm IME 设为默认输入法",
							style = MaterialTheme.typography.bodyMedium,
							textAlign = TextAlign.Center,
							modifier = Modifier.fillMaxWidth(),
						)
						SpacerGroup()
						Button(onClick = onSetupClick, modifier = Modifier.fillMaxWidth()) {
							Text("设置默认输入法 linux-rm IME")
						}
					}
				}
			}
		} else {
			LazyColumn(modifier = Modifier
				.fillMaxSize()
				.padding(paddingValues)
				.screenPadding()
				.imePadding()) {
				item {
					var text by remember {
						mutableStateOf("""Hello! 你好，世界！🌍
这是一个测试输入法的综合文本。
包含英文单词: apple, banana, computer, keyboard, Linux, Android。
包含中文汉字：测试，输入法，功能，候选词，拼音，九键。
数字: 12345，符号: !@#$%^&*()_+-=，还有中文标点：，。！？；
特殊组合: Ctrl+C 复制，Ctrl+V 粘贴，Ctrl+A 全选。
换行符测试：
第一行结束。
第二行开始。
最后一行。
""")
					}
					OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("测试键盘") }, modifier = Modifier
						.fillMaxSize()
						.height(128.dp))
					SpacerGroup()
					GroupedRow(title = "学习快捷键", position = Position.Alone, onClick = { showShortcutDialog = true })
					SpacerGroup()
					Text(text = "Rime 后端设置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = AppDimens.titlePaddingTop, bottom = AppDimens.titlePaddingBottom))
					SpacerGroup()
					Column {
						GroupedRow(title = "重新部署", position = Position.Top, onClick = {})
						GroupedRow(title = "方案", position = Position.Middle, onClick = {})
						GroupedRow(title = "用户词典", position = Position.Middle, onClick = {})
						GroupedRow(title = "配置", position = Position.Bottom, onClick = {})
					}
					SpacerGroup()
					Text(text = "前端设置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = AppDimens.titlePaddingTop, bottom = AppDimens.titlePaddingBottom))
					SpacerGroup()
					Column {
						GroupedRow(
							title = "显示按键边框",
							position = Position.Top,
							trailing = { Switch(checked = showKeyBorders, onCheckedChange = onShowKeyBordersChange) },
							onClick = { onShowKeyBordersChange(! showKeyBorders) },
						)
						GroupedRow(
							title = "震动反馈",
							position = Position.Bottom,
							trailing = { Switch(checked = hapticFeedback, onCheckedChange = onHapticFeedbackChange) },
							onClick = { onHapticFeedbackChange(! hapticFeedback) },
						)
					}
					SpacerGroup()
					GroupedRow(title = "切换输入法", position = Position.Alone, onClick = { })
					SpacerGroup()
				}
			}
		}
		if (showShortcutDialog) {
			AlertDialog(onDismissRequest = { showShortcutDialog = false }, title = { Text("快捷键") }, text = {
				Column(modifier = Modifier
					.fillMaxWidth()
					.verticalScroll(rememberScrollState())) {
					Text(style = MaterialTheme.typography.bodyLarge, text = """
文本编辑
复制: Ctrl+C
剪切: Ctrl+X
粘贴: Ctrl+V
全选: Ctrl+A
撤销: Ctrl+Z
重做: Ctrl+Y 或 Ctrl+Shift+Z, 取决于应用
===
光标移动
Home 跳到行首
End 跳到行尾
Ctrl + Home/End 跳到整个文本开头/结尾
Ctrl + ←/→ 向左右移动一个词
===
文本选择
Shift + ←/→ 逐字符选中
Shift + Ctrl + ←/→ 逐词选中
Shift + Home/End 从光标到行首/尾选中
Shift + Ctrl + Home/End 从光标到全文开头/结尾选中
===
删除: Ctrl + Backspace/Delete 删除光标前/后一个词
===
提示
	Tab 键: 大部分情况下不会插入制表符, 而是将焦点移到下一个输入框, 除非使用专门的文本编辑器
	回车键 Enter 的行为由当前应用决定, 例如聊天框中可能是发送, 备忘录中通常是换行
	系统级别的快捷键如 Alt+Tab, Ctrl+Alt+Del 等不支持(因系统限制)
    """)
				}
			}, confirmButton = {
				TextButton(onClick = { showShortcutDialog = false }) {
					Text("关闭")
				}
			})
		}
	}
}