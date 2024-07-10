package com.example.server.data_classes

data class FileNode(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val children: MutableList<FileNode> = mutableListOf()
){
    override fun toString(): String {
        return buildString { appendNode(this, "", true) }
    }

    private fun appendNode(sb: StringBuilder, prefix: String, isTail: Boolean) {
        sb.append(prefix).append(if (isTail) "└── " else "├── ").append(name)
        if (!isDirectory) sb.append(" (").append(size).append(" bytes)")
        sb.append('\n')

        for (i in 0 until children.size) {
            children[i].appendNode(
                sb,
                prefix + if (isTail) "    " else "│   ",
                i == children.size - 1
            )
        }
    }
}
