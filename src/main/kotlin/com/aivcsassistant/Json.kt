package com.aivcsassistant

/**
 * Minimal JSON parser for provider output that is expected to hold a single JSON value.
 * It supports objects, arrays, strings, numbers, booleans, and null, which is enough for the
 * CLI responses this plugin consumes (pull request content and the Codex model catalog).
 */
object Json {
    class ParseException(message: String) : Exception(message)

    fun parse(raw: String): Any? {
        val parser = Parser(raw)
        val value = parser.parseValue()
        parser.skipWhitespace()
        if (!parser.isAtEnd()) throw ParseException("Unexpected content after JSON value.")
        return value
    }

    private class Parser(private val raw: String) {
        private var index = 0

        fun isAtEnd(): Boolean = index >= raw.length

        fun skipWhitespace() {
            while (!isAtEnd() && raw[index].isWhitespace()) index++
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (isAtEnd()) throw ParseException("Unexpected end of JSON.")
            return when (raw[index]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> parseLiteral("true", true)
                'f' -> parseLiteral("false", false)
                'n' -> parseLiteral("null", null)
                else -> {
                    if (raw[index] == '-' || raw[index].isDigit()) {
                        parseNumber()
                    } else {
                        throw ParseException("Unexpected JSON token '${raw[index]}'.")
                    }
                }
            }
        }

        private fun parseObject(): Map<String, Any?> {
            expect('{')
            val result = linkedMapOf<String, Any?>()
            skipWhitespace()
            if (peek('}')) {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                result[key] = parseValue()
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek('}') -> {
                        index++
                        return result
                    }
                    else -> throw ParseException("Expected ',' or '}' in JSON object.")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            expect('[')
            val result = mutableListOf<Any?>()
            skipWhitespace()
            if (peek(']')) {
                index++
                return result
            }
            while (true) {
                result.add(parseValue())
                skipWhitespace()
                when {
                    peek(',') -> index++
                    peek(']') -> {
                        index++
                        return result
                    }
                    else -> throw ParseException("Expected ',' or ']' in JSON array.")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val result = StringBuilder()
            while (!isAtEnd()) {
                val char = raw[index++]
                when (char) {
                    '"' -> return result.toString()
                    '\\' -> result.append(parseEscape())
                    else -> result.append(char)
                }
            }
            throw ParseException("Unterminated JSON string.")
        }

        private fun parseEscape(): Char {
            if (isAtEnd()) throw ParseException("Unterminated JSON escape.")
            return when (val escaped = raw[index++]) {
                '"', '\\', '/' -> escaped
                'b' -> '\b'
                'f' -> '\u000C'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> parseUnicodeEscape()
                else -> throw ParseException("Invalid JSON escape '\\$escaped'.")
            }
        }

        private fun parseUnicodeEscape(): Char {
            if (index + 4 > raw.length) throw ParseException("Invalid unicode escape.")
            val hex = raw.substring(index, index + 4)
            index += 4
            return hex.toIntOrNull(16)?.toChar() ?: throw ParseException("Invalid unicode escape '$hex'.")
        }

        private fun parseLiteral(expected: String, value: Any?): Any? {
            if (!raw.startsWith(expected, index)) {
                throw ParseException("Invalid JSON literal.")
            }
            index += expected.length
            return value
        }

        private fun parseNumber(): Double {
            val start = index
            if (peek('-')) index++
            while (!isAtEnd() && raw[index].isDigit()) index++
            if (peek('.')) {
                index++
                while (!isAtEnd() && raw[index].isDigit()) index++
            }
            if (!isAtEnd() && (raw[index] == 'e' || raw[index] == 'E')) {
                index++
                if (!isAtEnd() && (raw[index] == '+' || raw[index] == '-')) index++
                while (!isAtEnd() && raw[index].isDigit()) index++
            }
            return raw.substring(start, index).toDoubleOrNull()
                ?: throw ParseException("Invalid JSON number.")
        }

        private fun expect(expected: Char) {
            skipWhitespace()
            if (isAtEnd() || raw[index] != expected) {
                throw ParseException("Expected '$expected'.")
            }
            index++
        }

        private fun peek(expected: Char): Boolean = !isAtEnd() && raw[index] == expected
    }
}
