package com.swufe.homework.util

/** 比较 "v1.2.3" / "1.2" / "1.2.3-beta" 这类版本号。预发布后缀低于正式版。 */
object Version {
    fun isNewer(remote: String, local: String): Boolean = compare(remote, local) > 0

    fun compare(a: String, b: String): Int {
        val (na, pa) = split(a)
        val (nb, pb) = split(b)
        for (i in 0 until maxOf(na.size, nb.size)) {
            val c = na.getOrElse(i) { 0 }.compareTo(nb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            pa == pb -> 0
            pa == null -> 1
            pb == null -> -1
            else -> pa.compareTo(pb)
        }
    }

    private fun split(v: String): Pair<List<Int>, String?> {
        val s = v.trim().removePrefix("v").removePrefix("V")
        val main = s.substringBefore('-').substringBefore('+')
        val pre = s.substringAfter('-', "").substringBefore('+').ifEmpty { null }
        return main.split('.').map { it.toIntOrNull() ?: 0 } to pre
    }
}
