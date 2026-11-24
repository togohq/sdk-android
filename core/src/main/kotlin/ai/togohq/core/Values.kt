package ai.togohq.core

/**
 * A caller's property map — `Map<String, Any?>`, so Java passes a `HashMap` —
 * as the envelope's values. A string, a number, a boolean or null are the four
 * it admits: no nesting and no lists, which is one of the places that keeps
 * free text and personal data out of event properties. Anything else is
 * reported per key and left out, because the edge would refuse the WHOLE batch
 * it rode in.
 */
internal fun toProps(properties: Map<String, Any?>?, invalid: (key: String?, value: Any?) -> Unit): Props {
  if (properties.isNullOrEmpty()) return emptyMap()
  val out = LinkedHashMap<String, JsonValue>(properties.size)
  for (entry in properties.entries) {
    // A Java HashMap can hold a null key, whatever the Kotlin type says. It
    // has no name to send under, so it is refused like a value that cannot be.
    val key: String? = (entry as Map.Entry<String?, Any?>).key
    if (key == null) {
      invalid(null, entry.value)
      continue
    }
    val converted = toJson(entry.value)
    if (converted == null) invalid(key, entry.value) else out[key] = converted
  }
  return out
}

/** One value, or `null` for a type the envelope cannot carry. */
internal fun toJson(value: Any?): JsonValue? = when (value) {
  null -> JsonValue.Null
  is String -> JsonValue.Str(value)
  is Boolean -> JsonValue.Bool(value)
  is Int -> JsonValue.Int(value.toLong())
  is Long -> JsonValue.Int(value)
  is Short -> JsonValue.Int(value.toLong())
  is Byte -> JsonValue.Int(value.toLong())
  // Through its decimal text, so `0.1f` is sent as 0.1 and not 0.10000000149.
  is Float -> JsonValue.Dbl(value.toString().toDouble())
  is Double -> JsonValue.Dbl(value)
  is Number -> value.toDouble().let { if (it == Math.rint(it) && Math.abs(it) < 9e15) JsonValue.Int(it.toLong()) else JsonValue.Dbl(it) }
  is CharSequence -> JsonValue.Str(value.toString())
  else -> null
}

/** The type name a refusal names: `Map`, `List`, the class otherwise. */
internal fun typeName(value: Any?): String = when (value) {
  is Map<*, *> -> "a map"
  is Collection<*> -> "a list"
  is Array<*> -> "an array"
  null -> "null"
  else -> value.javaClass.simpleName
}
