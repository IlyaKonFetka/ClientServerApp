package com.example.server.managers

import android.graphics.Color
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.Log
import com.example.server.data_classes.FileNode
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class FileScanner {


    fun scanDirectory(path: String): FileNode {
        val rootCommand = "su -c ls -lR $path"
        val process = Runtime.getRuntime().exec(rootCommand)
        val inputStream = process.inputStream
        val reader = BufferedReader(InputStreamReader(inputStream))

        val rootNodeName = path.substringAfterLast("/")
        val rootNode = FileNode(rootNodeName, true, 0)
        var currentNode = rootNode

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            if (line!!.startsWith("/")) {
                val directoryPath = line!!.substring(0, line!!.length - 1)
                val relativePath = directoryPath.substringAfter(path)
                currentNode = findOrCreateNode(rootNode, relativePath)
            } else if (line!!.isNotEmpty()) {
                val parts = line!!.split("\\s+".toRegex())
                if (parts.size >= 8) {
                    val permissions = parts[0]
                    val isDirectory = permissions.startsWith("d")
                    val name = parts.last()
                    val size = if (isDirectory) 0 else parts[4].toLong()
                    val fileNode = FileNode(name, isDirectory, size)
                    currentNode.children.add(fileNode)
                }
            }
        }

        return rootNode
    }

    private fun findOrCreateNode(rootNode: FileNode, path: String): FileNode {
        val parts = path.split("/")
        var currentNode = rootNode

        for (part in parts) {
            if (part.isNotEmpty()) {
                val childNode = currentNode.children.find { it.name == part }
                if (childNode != null) {
                    currentNode = childNode
                } else {
                    val newNode = FileNode(part, true, 0)
                    currentNode.children.add(newNode)
                    currentNode = newNode
                }
            }
        }

        return currentNode
    }



    fun compareTreesToSpannableString(oldTree: FileNode?, newTree: FileNode): SpannableString {
        if (oldTree == null){
            return treeToSpannableString(newTree)
        }
        val builder = SpannableStringBuilder()
        compareTreesRecursive(builder, oldTree, newTree, "", true)
        return SpannableString(builder)
    }

    private fun compareTreesRecursive(builder: SpannableStringBuilder, oldNode: FileNode?,
                                      newNode: FileNode, prefix: String, isLast: Boolean) {
        val currentPrefix = if (prefix.isEmpty()) "" else if (isLast) "$prefix└── " else "$prefix├── "
        val lineStart = builder.length
        builder.append(currentPrefix)
        val prefixEnd = builder.length
        builder.append(newNode.name)

        val color = when {
            oldNode == null -> Color.GREEN // Новый файл/директория
            !newNode.isDirectory && oldNode.size != newNode.size -> Color.MAGENTA // Изменённый размер файла
            else -> Color.BLACK // Без изменений
        }

        val nameEnd = builder.length

        if (!newNode.isDirectory) {
            builder.append(" (${convertBytesToString(newNode.size)})")
        }
        val lineEnd = builder.length
        builder.append("\n")

        builder.setSpan(ForegroundColorSpan(color), prefixEnd, nameEnd, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)

        if (!newNode.isDirectory) {
            // Устанавливаем серый цвет для размера файла
            builder.setSpan(ForegroundColorSpan(Color.GRAY), nameEnd, lineEnd, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (newNode.isDirectory) {
            val newPrefix = if (prefix.isEmpty()) "    " else if (isLast) "$prefix    " else "$prefix│   "
            newNode.children.forEachIndexed { index, newChild ->
                val oldChild = oldNode?.children?.find { it.name == newChild.name }
                val isLastChild = index == newNode.children.size - 1
                compareTreesRecursive(builder, oldChild, newChild, newPrefix, isLastChild)
            }
        }
    }
    private fun treeToSpannableString(root: FileNode): SpannableString {
        val builder = SpannableStringBuilder()
        treeToStringRecursive(builder, root, "", true)
        return SpannableString(builder)
    }

    private fun treeToStringRecursive(builder: SpannableStringBuilder, node: FileNode, prefix: String, isLast: Boolean) {
        val currentPrefix = if (prefix.isEmpty()) "" else if (isLast) "$prefix└── " else "$prefix├── "
        val lineStart = builder.length
        builder.append(currentPrefix)
        val prefixEnd = builder.length
        builder.append(node.name)
        val nameEnd = builder.length

        if (!node.isDirectory) {
            builder.append(" (${convertBytesToString(node.size)})")
        }
        val lineEnd = builder.length
        builder.append("\n")

        // Устанавливаем черный цвет для имени файла или директории
        builder.setSpan(ForegroundColorSpan(Color.BLACK), prefixEnd, nameEnd, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)

        if (!node.isDirectory) {
            // Устанавливаем серый цвет для размера файла
            builder.setSpan(ForegroundColorSpan(Color.GRAY), nameEnd, lineEnd, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
        }

        if (node.isDirectory) {
            val newPrefix = if (prefix.isEmpty()) "    " else if (isLast) "$prefix    " else "$prefix│   "
            node.children.forEachIndexed { index, child ->
                val isLastChild = index == node.children.size - 1
                treeToStringRecursive(builder, child, newPrefix, isLastChild)
            }
        }
    }

    fun getDirectorySize(path: String): Long {
        val command = "su -c du -s $path"
        val process = Runtime.getRuntime().exec(command)
        val reader = BufferedReader(InputStreamReader(process.inputStream))

        val output = reader.readLine()
        val size = output.split("\t").getOrNull(0)?.toLongOrNull() ?: 0L

        process.waitFor()
        reader.close()

        return size
    }

    fun convertBytesToString(size: Long): String {
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var sizeInBytes = size.toDouble()
        var unitIndex = 0
        while (sizeInBytes >= 1024 && unitIndex < units.size - 1) {
            sizeInBytes /= 1024
            unitIndex++
        }
        return String.format("%.2f %s", sizeInBytes, units[unitIndex])
    }

    fun treesAreDifferent(oldTree: FileNode, newTree: FileNode): Boolean {
        return !areNodesEqual(oldTree, newTree)
    }

    private fun areNodesEqual(node1: FileNode, node2: FileNode): Boolean {
        if (node1.name != node2.name || node1.isDirectory != node2.isDirectory || node1.size != node2.size) {
            return false
        }

        if (node1.children.size != node2.children.size) {
            return false
        }

        for (i in node1.children.indices) {
            if (!areNodesEqual(node1.children[i], node2.children[i])) {
                return false
            }
        }

        return true
    }
}