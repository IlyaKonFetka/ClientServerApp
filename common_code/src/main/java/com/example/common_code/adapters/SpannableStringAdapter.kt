package com.example.common_code.adapters

import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import com.google.gson.JsonArray
import com.google.gson.JsonDeserializationContext
import com.google.gson.JsonDeserializer
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonSerializationContext
import com.google.gson.JsonSerializer
import java.lang.reflect.Type

class SpannableStringAdapter : JsonSerializer<SpannableString>, JsonDeserializer<SpannableString> {

    override fun serialize(src: SpannableString, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val jsonObject = JsonObject()
        jsonObject.addProperty("text", src.toString())

        val spans = src.getSpans(0, src.length, ForegroundColorSpan::class.java)
        val jsonSpans = JsonArray()

        for (span in spans) {
            val jsonSpan = JsonObject()
            jsonSpan.addProperty("start", src.getSpanStart(span))
            jsonSpan.addProperty("end", src.getSpanEnd(span))
            jsonSpan.addProperty("color", span.foregroundColor)
            jsonSpans.add(jsonSpan)
        }

        jsonObject.add("spans", jsonSpans)
        return jsonObject
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): SpannableString {
        val jsonObject = json.asJsonObject
        val text = jsonObject.get("text").asString
        val builder = SpannableStringBuilder(text)

        val jsonSpans = jsonObject.getAsJsonArray("spans")
        for (jsonSpan in jsonSpans) {
            val spanObject = jsonSpan.asJsonObject
            val start = spanObject.get("start").asInt
            val end = spanObject.get("end").asInt
            val color = spanObject.get("color").asInt

            builder.setSpan(ForegroundColorSpan(color), start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        return SpannableString(builder)
    }
}