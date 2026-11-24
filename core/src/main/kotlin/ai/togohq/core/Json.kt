package ai.togohq.core

/**
 * A JSON value, with its own writer and reader.
 *
 * **Hand-written rather than `org.json`, on purpose.** Android's bundled
 * `org.json` is only stubs on a plain JVM, where the core's tests and the
 * scenario runner run, and taking a JSON library would be a runtime
 * dependency. It also keeps the one distinction a property value depends on:
 * a property sent as `5999` must not come back as `5999.0`, nor a `true` as
 * `1`, on a row nothing rewrites.
 */
internal sealed class JsonValue {
  object Null : JsonValue()
  data class Bool(val value: Boolean) : JsonValue()
  data class Int(val value: Long) : JsonValue()
  data class Dbl(val value: Double) : JsonValue()
  data class Str(val value: String) : JsonValue()
  data class Arr(val values: List<JsonValue>) : JsonValue()
  data class Obj(val values: Map<String, JsonValue>) : JsonValue() {
    operator fun get(key: String): JsonValue? = values[key]
    fun string(key: String): String? = (values[key] as? Str)?.value
  }

  /** A string, a number, a boolean or null: the four a property value may be. */
  val isPrimitive: Boolean get() = this !is Arr && this !is Obj

  /** Compact JSON. Object keys are sorted, so one value always writes the same bytes. */
  fun serialized(): String = StringBuilder().also { write(it) }.toString()

  /** Indented, for a file a person reads. */
  fun pretty(indent: kotlin.Int = 2): String = StringBuilder().also { writePretty(it, 0, indent) }.toString()

  private fun write(out: StringBuilder) {
    when (this) {
      Null -> out.append("null")
      is Bool -> out.append(if (value) "true" else "false")
      is Int -> out.append(value)
      is Dbl -> out.append(format(value))
      is Str -> quote(value, out)
      is Arr -> {
        out.append('[')
        values.forEachIndexed { i, v ->
          if (i > 0) out.append(',')
          v.write(out)
        }
        out.append(']')
      }
      is Obj -> {
        out.append('{')
        values.keys.sorted().forEachIndexed { i, k ->
          if (i > 0) out.append(',')
          quote(k, out)
          out.append(':')
          values.getValue(k).write(out)
        }
        out.append('}')
      }
    }
  }

  private fun writePretty(out: StringBuilder, depth: kotlin.Int, indent: kotlin.Int) {
    val pad = " ".repeat((depth + 1) * indent)
    val close = " ".repeat(depth * indent)
    when {
      this is Arr && values.isNotEmpty() -> {
        out.append("[\n")
        values.forEachIndexed { i, v ->
          out.append(pad)
          v.writePretty(out, depth + 1, indent)
          out.append(if (i < values.size - 1) ",\n" else "\n")
        }
        out.append(close).append(']')
      }
      this is Obj && values.isNotEmpty() -> {
        out.append("{\n")
        val keys = values.keys.sorted()
        keys.forEachIndexed { i, k ->
          out.append(pad)
          quote(k, out)
          out.append(": ")
          values.getValue(k).writePretty(out, depth + 1, indent)
          out.append(if (i < keys.size - 1) ",\n" else "\n")
        }
        out.append(close).append('}')
      }
      else -> write(out)
    }
  }

  companion object {
    /**
     * A finite double in a round-tripping form. JSON has no NaN or infinity, so
     * those become `null` rather than an unparseable body that would `400` the
     * whole batch.
     */
    fun format(value: Double): String {
      if (!value.isFinite()) return "null"
      if (value == Math.rint(value) && Math.abs(value) < 1e15) return value.toLong().toString()
      return value.toString()
    }

    /**
     * A Kotlin string is UTF-16 and can hold half a surrogate pair — a value cut
     * mid-emoji by `substring()`. UTF-8 cannot encode one, and an encoder that
     * writes `?` or throws would corrupt or lose the batch, so a lone half is
     * written as U+FFFD.
     */
    fun quote(value: String, out: StringBuilder) {
      out.append('"')
      var i = 0
      val n = value.length
      while (i < n) {
        val c = value[i]
        when {
          c == '"' -> out.append("\\\"")
          c == '\\' -> out.append("\\\\")
          c == '\n' -> out.append("\\n")
          c == '\r' -> out.append("\\r")
          c == '\t' -> out.append("\\t")
          c.code < 0x20 -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
          Character.isHighSurrogate(c) -> {
            if (i + 1 < n && Character.isLowSurrogate(value[i + 1])) {
              out.append(c).append(value[i + 1])
              i++
            } else {
              out.append('�')
            }
          }
          Character.isLowSurrogate(c) -> out.append('�')
          else -> out.append(c)
        }
        i++
      }
      out.append('"')
    }

    /** Parse, or throw [JsonParseException]. */
    fun parse(text: String): JsonValue {
      val parser = Parser(text)
      parser.skipWhitespace()
      val value = parser.value()
      parser.skipWhitespace()
      if (!parser.atEnd) throw parser.error("trailing characters")
      return value
    }

    /** UTF-8 bytes; a malformed sequence reads as U+FFFD rather than failing the parse. */
    fun parse(bytes: ByteArray): JsonValue = parse(String(bytes, Charsets.UTF_8))

    fun parseOrNull(text: String): JsonValue? = try { parse(text) } catch (_: JsonParseException) { null }
  }
}

internal class JsonParseException(message: String) : Exception(message)

private class Parser(private val s: String) {
  private var i = 0
  val atEnd: Boolean get() = i >= s.length

  fun error(message: String) = JsonParseException("JSON: $message at index $i")

  fun skipWhitespace() {
    while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
  }

  fun value(): JsonValue {
    if (i >= s.length) throw error("unexpected end")
    return when (s[i]) {
      '{' -> obj()
      '[' -> arr()
      '"' -> JsonValue.Str(string())
      't' -> { literal("true"); JsonValue.Bool(true) }
      'f' -> { literal("false"); JsonValue.Bool(false) }
      'n' -> { literal("null"); JsonValue.Null }
      else -> number()
    }
  }

  private fun literal(word: String) {
    if (!s.startsWith(word, i)) throw error("expected $word")
    i += word.length
  }

  private fun obj(): JsonValue {
    i++
    val result = LinkedHashMap<String, JsonValue>()
    skipWhitespace()
    if (i < s.length && s[i] == '}') { i++; return JsonValue.Obj(result) }
    while (true) {
      skipWhitespace()
      if (i >= s.length || s[i] != '"') throw error("expected a key")
      val key = string()
      skipWhitespace()
      if (i >= s.length || s[i] != ':') throw error("expected :")
      i++
      skipWhitespace()
      result[key] = value()
      skipWhitespace()
      if (i >= s.length) throw error("unterminated object")
      when (s[i]) {
        ',' -> i++
        '}' -> { i++; return JsonValue.Obj(result) }
        else -> throw error("expected , or }")
      }
    }
  }

  private fun arr(): JsonValue {
    i++
    val result = ArrayList<JsonValue>()
    skipWhitespace()
    if (i < s.length && s[i] == ']') { i++; return JsonValue.Arr(result) }
    while (true) {
      skipWhitespace()
      result.add(value())
      skipWhitespace()
      if (i >= s.length) throw error("unterminated array")
      when (s[i]) {
        ',' -> i++
        ']' -> { i++; return JsonValue.Arr(result) }
        else -> throw error("expected , or ]")
      }
    }
  }

  fun string(): String {
    i++
    val out = StringBuilder()
    while (i < s.length) {
      val c = s[i]
      if (c == '"') { i++; return sanitized(out) }
      if (c == '\\') {
        i++
        if (i >= s.length) break
        val e = s[i++]
        when (e) {
          '"' -> out.append('"')
          '\\' -> out.append('\\')
          '/' -> out.append('/')
          'b' -> out.append('\b')
          'f' -> out.append('\u000C')
          'n' -> out.append('\n')
          'r' -> out.append('\r')
          't' -> out.append('\t')
          'u' -> out.append(hex4().toChar())
          else -> throw error("bad escape")
        }
        continue
      }
      out.append(c)
      i++
    }
    throw error("unterminated string")
  }

  private fun hex4(): kotlin.Int {
    if (i + 4 > s.length) throw error("bad \\u escape")
    val code = s.substring(i, i + 4).toIntOrNull(16) ?: throw error("bad \\u escape")
    i += 4
    return code
  }

  /** A lone surrogate either way round — from `\uD800` alone, say — becomes U+FFFD. */
  private fun sanitized(raw: StringBuilder): String {
    val out = StringBuilder(raw.length)
    var k = 0
    while (k < raw.length) {
      val c = raw[k]
      if (Character.isHighSurrogate(c) && k + 1 < raw.length && Character.isLowSurrogate(raw[k + 1])) {
        out.append(c).append(raw[k + 1])
        k += 2
        continue
      }
      out.append(if (Character.isSurrogate(c)) '�' else c)
      k++
    }
    return out.toString()
  }

  private fun number(): JsonValue {
    val start = i
    var integer = true
    if (i < s.length && s[i] == '-') i++
    while (i < s.length) {
      val c = s[i]
      if (c in '0'..'9') {
        i++
      } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
        integer = false
        i++
      } else {
        break
      }
    }
    val text = s.substring(start, i)
    if (integer) text.toLongOrNull()?.let { return JsonValue.Int(it) }
    val d = text.toDoubleOrNull() ?: throw error("bad number $text")
    return JsonValue.Dbl(d)
  }
}
