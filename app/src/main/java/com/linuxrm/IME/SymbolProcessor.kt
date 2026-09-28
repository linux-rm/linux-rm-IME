package com.linuxrm.IME
import android.content.Context
import java.text.BreakIterator
class SymbolProcessor(private val context : Context) {
	private val categories = mutableListOf<String>()
	private val symbolMap = mutableMapOf<String, List<String>>()
	init {
		loadSymbols()
	}
	private fun loadSymbols() {
		context.resources.openRawResource(R.raw.symbols).bufferedReader().useLines { lines ->
			lines.forEach { line ->
				if (line.isNotBlank()) {
					val parts = line.split(',')
					if (parts.size >= 2) {
						val category = parts[0].trim()
						val symbolString = parts[1].trim()
						val symbols = if (symbolString.any { it in '\u0300' .. '\u036F' }) {
							val result = mutableListOf<String>()
							var i = 0
							while (i < symbolString.length) {
								val cp = symbolString.codePointAt(i)
								result.add(String(Character.toChars(cp)))
								i += Character.charCount(cp)
							}
							result
						} else {
							val result = mutableListOf<String>()
							val iterator = BreakIterator.getCharacterInstance()
							iterator.setText(symbolString)
							var start = iterator.first()
							var end = iterator.next()
							while (end != BreakIterator.DONE) {
								result.add(symbolString.substring(start, end))
								start = end
								end = iterator.next()
							}
							result
						}
						if (symbols.isNotEmpty()) {
							categories.add(category)
							symbolMap[category] = symbols
						}
					}
				}
			}
		}
	}
	fun getCategories() : List<String> = categories
	fun getSymbols(categoryIndex : Int) : List<String> = categories.getOrNull(categoryIndex)?.let { symbolMap[it] } ?: emptyList()
}
