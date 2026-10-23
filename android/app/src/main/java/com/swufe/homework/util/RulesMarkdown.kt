package com.swufe.homework.util

/**
 * 「贡献值规则」页：原样展示 docs/points-rules.md 的上半部分（`---` 之前，不含「实现说明」）。
 * 只处理该文件用到的几种语法：#/## 标题、> 引用、表格、- 列表、**粗体**。
 */
sealed interface RulesBlock {
    data class Title(val text: String) : RulesBlock
    data class Heading(val text: String) : RulesBlock
    data class Quote(val text: String) : RulesBlock
    data class Bullet(val text: String) : RulesBlock
    data class Para(val text: String) : RulesBlock
    data class Table(val header: List<String>, val rows: List<List<String>>) : RulesBlock
}

object RulesMarkdown {
    /** 上半部分：第一条单独的 `---` 之前。 */
    fun upperPart(md: String): String {
        val lines = md.replace("\r\n", "\n").lines()
        val cut = lines.indexOfFirst { it.trim() == "---" }
        return (if (cut >= 0) lines.take(cut) else lines).joinToString("\n").trimEnd()
    }

    fun parse(md: String): List<RulesBlock> {
        val out = mutableListOf<RulesBlock>()
        val lines = md.lines()
        var i = 0
        while (i < lines.size) {
            val l = lines[i].trim()
            when {
                l.isEmpty() -> i++
                l.startsWith("## ") -> { out += RulesBlock.Heading(l.removePrefix("## ").trim()); i++ }
                l.startsWith("# ") -> { out += RulesBlock.Title(l.removePrefix("# ").trim()); i++ }
                l.startsWith(">") -> { out += RulesBlock.Quote(l.removePrefix(">").trim()); i++ }
                l.startsWith("- ") -> { out += RulesBlock.Bullet(l.removePrefix("- ").trim()); i++ }
                l.startsWith("|") -> {
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trim().startsWith("|")) {
                        val cells = lines[i].trim().trim('|').split("|").map { it.trim() }
                        if (!cells.all { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' } }) rows += cells
                        i++
                    }
                    if (rows.isNotEmpty()) out += RulesBlock.Table(rows.first(), rows.drop(1))
                }
                else -> { out += RulesBlock.Para(l); i++ }
            }
        }
        return out
    }

    /** 拆出 **粗体** 片段：返回 (文字, 是否粗体)。 */
    fun spans(text: String): List<Pair<String, Boolean>> {
        val parts = text.split("**")
        return parts.mapIndexedNotNull { idx, s -> if (s.isEmpty()) null else s to (idx % 2 == 1) }
    }
}
