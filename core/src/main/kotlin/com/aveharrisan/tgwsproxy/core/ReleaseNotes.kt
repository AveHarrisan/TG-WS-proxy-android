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

    /** 1.10.0 новее 1.9.2: сравниваем по числам, а не строкой. */
    fun isNewer(candidate: String, current: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val a = parts(candidate)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
