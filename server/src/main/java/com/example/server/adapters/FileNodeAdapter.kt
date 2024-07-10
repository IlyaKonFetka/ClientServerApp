package com.example.server.adapters

import com.example.server.data_classes.FileNode
import com.google.gson.*
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type

class FileNodeAdapter : JsonSerializer<FileNode>, JsonDeserializer<FileNode> {

    override fun serialize(src: FileNode, typeOfSrc: Type, context: JsonSerializationContext): JsonElement {
        val jsonObject = JsonObject()
        jsonObject.addProperty("name", src.name)
        jsonObject.addProperty("isDirectory", src.isDirectory)
        jsonObject.addProperty("size", src.size)
        jsonObject.add("children", context.serialize(src.children))
        return jsonObject
    }

    override fun deserialize(json: JsonElement, typeOfT: Type, context: JsonDeserializationContext): FileNode {
        val jsonObject = json.asJsonObject
        val name = jsonObject.get("name")?.asString ?: ""
        val isDirectory = jsonObject.get("isDirectory")?.asBoolean ?: false
        val size = jsonObject.get("size")?.asLong ?: 0L
        val children = jsonObject.get("children")?.let {
            context.deserialize<MutableList<FileNode>>(it, object : TypeToken<MutableList<FileNode>>() {}.type)
        } ?: mutableListOf()
        return FileNode(name, isDirectory, size, children)
    }
}