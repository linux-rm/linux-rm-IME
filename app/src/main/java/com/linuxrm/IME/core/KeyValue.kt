/*
 * SPDX-FileCopyrightText: 2015 - 2024 Rime community
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.linuxrm.IME.core
import android.view.KeyCharacterMap
import android.view.KeyEvent
@JvmInline
value class KeyValue(
    val value: Int,
) {
    val keyCode get() = value
    override fun toString() = "0x" + value.toString(16).padStart(4, '0')
    companion object {
        fun fromKeyEvent(event: KeyEvent): KeyValue {
            val charCode = event.unicodeChar
            if (charCode != 0 &&
                charCode != '\t'.code &&
                charCode != '\n'.code &&
                charCode != KeyCharacterMap.HEX_INPUT.code &&
                charCode != KeyCharacterMap.PICKER_DIALOG_INPUT.code
            ) {
                return KeyValue(charCode)
            }
	        return KeyValue(event.keyCode)
        }
    }
}
