package com.eddyizm.tempus.subsonic.jellyfin

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Drift: lenient reading of Jellyfin/Emby responses. The API uses PascalCase but some
 * versions answer in camelCase (e.g. /System/Info/Public on Jellyfin 12): both are tried.
 */
internal object Json {
    fun get(o: JsonObject?, name: String): JsonElement? {
        if (o == null) return null
        val e = o.get(name) ?: o.get(name.replaceFirstChar { it.lowercaseChar() })
        return if (e == null || e.isJsonNull) null else e
    }

    fun str(o: JsonObject?, name: String): String? =
        get(o, name)?.takeIf { it.isJsonPrimitive }?.asString

    fun long(o: JsonObject?, name: String): Long? =
        get(o, name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asLong }.getOrNull() }

    fun int(o: JsonObject?, name: String): Int? =
        get(o, name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asInt }.getOrNull() }

    fun double(o: JsonObject?, name: String): Double? =
        get(o, name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }

    fun bool(o: JsonObject?, name: String): Boolean? =
        get(o, name)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asBoolean }.getOrNull() }

    fun obj(o: JsonObject?, name: String): JsonObject? =
        get(o, name)?.takeIf { it.isJsonObject }?.asJsonObject

    fun arr(o: JsonObject?, name: String): JsonArray? =
        get(o, name)?.takeIf { it.isJsonArray }?.asJsonArray

    fun objects(o: JsonObject?, name: String): List<JsonObject> =
        arr(o, name)?.filter { it.isJsonObject }?.map { it.asJsonObject } ?: emptyList()

    fun strings(o: JsonObject?, name: String): List<String> =
        arr(o, name)?.filter { it.isJsonPrimitive }?.map { it.asString } ?: emptyList()

    /** Adds only non-null values, so Gson on the app side keeps the model defaults. */
    fun JsonObject.put(name: String, value: Any?) {
        when (value) {
            null -> Unit
            is JsonElement -> add(name, value)
            is String -> addProperty(name, value)
            is Number -> addProperty(name, value)
            is Boolean -> addProperty(name, value)
            else -> addProperty(name, value.toString())
        }
    }

    fun array(items: Iterable<JsonElement>): JsonArray = JsonArray().apply { items.forEach { add(it) } }
}
