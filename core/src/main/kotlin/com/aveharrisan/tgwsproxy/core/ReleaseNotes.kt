package com.aveharrisan.tgwsproxy.core

/** Разбор выпусков GitHub для обновления внутри приложения. */
object ReleaseNotes {
    /** Пункты списка из описания выпуска; перенесённые строки склеиваем с пунктом. */
    fun parse(body: String): List<String> {
        val out = ArrayList<String>()
        for (raw in body.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("- ") || line.startsWith("* ") -> out += line.substring(2).trim()
                line.isNotEmpty() && !line.startsWith("#") && out.isNotEmpty() && raw.startsWith(" ") ->
                    out[out.size - 1] = out.last() + " " + line
            }
        }
        return out.map { it.replace("**", "").replace("`", "") }
    }

    /** Раздел «## 1.1.7 — дата» из CHANGELOG.md: от заголовка версии до следующего «## ». */
    fun changelogSection(changelog: String, version: String): String {
        val lines = changelog.lines()
        val start = lines.indexOfFirst { it.startsWith("## ") && it.removePrefix("## ").trim().substringBefore(' ') == version }
        if (start < 0) return ""
        val end = (start + 1 until lines.size).firstOrNull { lines[it].startsWith("## ") } ?: lines.size
        return lines.subList(start + 1, end).joinToString("\n").trim()
    }

    /** Версия из адреса страницы выпуска: …/releases/tag/v1.1.7 → 1.1.7. */
    fun versionFromTagUrl(url: String): String? =
        Regex("/releases/tag/v?([0-9][0-9A-Za-z.\\-]*)").find(url)?.groupValues?.get(1)

    /** 1.10.0 новее 1.9.2: сравниваем по числам, а не строкой. */
    fun isNewer(candidate: String, current: String): Boolean = compare(candidate, current) > 0

    /** Бета — версия с суффиксом после дефиса: 1.1.11-beta2. */
    fun isBeta(version: String): Boolean = version.removePrefix("v").contains('-')

    /**
     * Порядок версий: числа, затем выпуск без суффикса новее беты с теми же числами
     * (1.1.11 > 1.1.11-beta2), а беты между собой — по номеру (beta10 > beta2).
     */
    fun compare(a: String, b: String): Int {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val x = parts(a)
        val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        val sa = a.removePrefix("v").substringAfter('-', "")
        val sb = b.removePrefix("v").substringAfter('-', "")
        if (sa.isEmpty() || sb.isEmpty()) return (if (sa.isEmpty()) 1 else 0) - (if (sb.isEmpty()) 1 else 0)
        fun split(s: String) = Regex("^(.*?)(\\d*)$").find(s)!!.destructured.let { (t, n) -> t to (n.toIntOrNull() ?: 0) }
        val (ta, na) = split(sa)
        val (tb, nb) = split(sb)
        return if (ta != tb) ta.compareTo(tb) else na.compareTo(nb)
    }

    /** Все версии из ленты выпусков GitHub (releases.atom), включая беты. */
    fun versionsFromAtom(atom: String): List<String> =
        Regex("/releases/tag/v?([0-9][0-9A-Za-z.\\-]*)").findAll(atom).map { it.groupValues[1] }.distinct().toList()
}
