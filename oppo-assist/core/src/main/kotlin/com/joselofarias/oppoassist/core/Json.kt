package com.joselofarias.oppoassist.core

class JsonException(message: String) : Exception(message)

sealed class JsonValue {
    data class Str(val value: String) : JsonValue()
    data class Bool(val value: Boolean) : JsonValue()
    data class Num(val value: Long) : JsonValue()
    data class Arr(val value: List<JsonValue>) : JsonValue()
    data class Obj(val value: Map<String, JsonValue>) : JsonValue()
    data object Null : JsonValue()

    fun asString(): String? = (this as? Str)?.value
    fun asObj(): Map<String, JsonValue>? = (this as? Obj)?.value
}

fun jstr(value: String): JsonValue = JsonValue.Str(value)
fun jbool(value: Boolean): JsonValue = JsonValue.Bool(value)
fun jnum(value: Long): JsonValue = JsonValue.Num(value)
fun jarr(values: List<JsonValue>): JsonValue = JsonValue.Arr(values)
fun jobj(vararg pairs: Pair<String, JsonValue>): JsonValue = JsonValue.Obj(linkedMapOf(*pairs))

object JsonCodec {
    fun parse(text: String): JsonValue = Parser(text).parse()

    fun stringify(value: JsonValue): String = buildString { write(value) }

    private fun StringBuilder.write(value: JsonValue) {
        when (value) {
            is JsonValue.Str -> append(escape(value.value))
            is JsonValue.Bool -> append(if (value.value) "true" else "false")
            is JsonValue.Num -> append(value.value)
            JsonValue.Null -> append("null")
            is JsonValue.Arr -> {
                append('[')
                value.value.forEachIndexed { index, item ->
                    if (index > 0) append(',')
                    write(item)
                }
                append(']')
            }
            is JsonValue.Obj -> {
                append('{')
                var index = 0
                value.value.forEach { (key, item) ->
                    if (index > 0) append(',')
                    append(escape(key))
                    append(':')
                    write(item)
                    index++
                }
                append('}')
            }
        }
    }

    private fun escape(text: String): String = buildString {
        append('"')
        text.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private class Parser(private val text: String) {
        private var i = 0

        fun parse(): JsonValue {
            val value = readValue()
            skip()
            if (i != text.length) throw JsonException("sobra")
            return value
        }

        private fun readValue(): JsonValue {
            skip()
            if (i >= text.length) throw JsonException("vacio")
            return when (text[i]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> JsonValue.Str(readString())
                't' -> literal("true", JsonValue.Bool(true))
                'f' -> literal("false", JsonValue.Bool(false))
                'n' -> literal("null", JsonValue.Null)
                else -> readNumber()
            }
        }

        private fun readObject(): JsonValue {
            expect('{')
            val map = linkedMapOf<String, JsonValue>()
            skip()
            if (peek('}')) {
                i++
                return JsonValue.Obj(map)
            }
            while (true) {
                skip()
                if (i >= text.length || text[i] != '"') throw JsonException("clave")
                val key = readString()
                skip()
                expect(':')
                map[key] = readValue()
                skip()
                when {
                    peek('}') -> {
                        i++
                        return JsonValue.Obj(map)
                    }
                    peek(',') -> i++
                    else -> throw JsonException("objeto")
                }
            }
        }

        private fun readArray(): JsonValue {
            expect('[')
            val list = mutableListOf<JsonValue>()
            skip()
            if (peek(']')) {
                i++
                return JsonValue.Arr(list)
            }
            while (true) {
                list += readValue()
                skip()
                when {
                    peek(']') -> {
                        i++
                        return JsonValue.Arr(list)
                    }
                    peek(',') -> i++
                    else -> throw JsonException("arreglo")
                }
            }
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (i < text.length) {
                val ch = text[i++]
                when (ch) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (i >= text.length) throw JsonException("escape")
                        when (val esc = text[i++]) {
                            '"', '\\', '/' -> out.append(esc)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > text.length) throw JsonException("unicode")
                                val hex = text.substring(i, i + 4)
                                i += 4
                                val code = hex.toIntOrNull(16) ?: throw JsonException("unicode")
                                out.append(code.toChar())
                            }
                            else -> throw JsonException("escape")
                        }
                    }
                    else -> {
                        if (ch.code < 0x20) throw JsonException("control")
                        out.append(ch)
                    }
                }
            }
            throw JsonException("cadena")
        }

        private fun readNumber(): JsonValue {
            val start = i
            if (peek('-')) throw JsonException("numero")
            if (!peekDigit()) throw JsonException("numero")
            while (i < text.length && text[i].isDigit()) i++
            if (i < text.length && (text[i] == '.' || text[i] == 'e' || text[i] == 'E')) {
                throw JsonException("numero")
            }
            val raw = text.substring(start, i)
            if (raw.length > 1 && raw.startsWith('0')) throw JsonException("numero")
            val number = raw.toLongOrNull() ?: throw JsonException("numero")
            return JsonValue.Num(number)
        }

        private fun literal(word: String, value: JsonValue): JsonValue {
            if (!text.startsWith(word, i)) throw JsonException("literal")
            i += word.length
            return value
        }

        private fun skip() {
            while (i < text.length && text[i].isWhitespace()) i++
        }

        private fun expect(ch: Char) {
            if (i >= text.length || text[i] != ch) throw JsonException("se esperaba $ch")
            i++
        }

        private fun peek(ch: Char): Boolean = i < text.length && text[i] == ch

        private fun peekDigit(): Boolean = i < text.length && text[i].isDigit()
    }
}
