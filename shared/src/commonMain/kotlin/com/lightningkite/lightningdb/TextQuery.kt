package com.lightningkite.lightningdb

import kotlin.math.min


data class TextQuery(
    val exact: Set<String> = setOf(),
    val loose: Set<String> = setOf(),
    val reject: Set<String> = setOf(),
) {
    companion object {
        fun fromString(string: String): TextQuery {
            val exact: HashSet<String> = HashSet()
            val loose: HashSet<String> = HashSet()
            val reject: HashSet<String> = HashSet()
            var inQuotes = false
            var isReject = false
            val building = StringBuilder()
            fun register(exactMode: Boolean) {
                if(building.length == 0) return
                val str = building.toString()
                building.clear()
                if (isReject) reject += str
                else if (exactMode) exact += str
                else loose += str
                isReject = false
            }
            for (char in string) {
                if (inQuotes) {
                    if (char == '\"') {
                        inQuotes = false
                        register(true)
                    } else {
                        building.append(char.lowercaseChar())
                    }
                } else {
                    when (char) {
                        ' ' -> register(false)
                        '"' -> inQuotes = true
                        '-' -> if (building.length > 0) building.append(char) else isReject = true
                        else -> building.append(char.lowercaseChar())
                    }
                }
            }
            register(false)

            return TextQuery(
                exact = exact,
                loose = loose,
                reject = reject,
            )
        }
    }

    fun fuzzyPresent(input: String, off: Int = 2): Boolean {
        val words = input.split(' ', '\n', '\t')
        return exact.all {
            input.contains(it)
        } && loose.all { l ->
            words.any { w -> levenshtein(l.lowercase(), w.lowercase()) <= off }
        } && reject.none {
            input.contains(it)
        }
    }

    private fun levenshtein(lhs : CharSequence, rhs : CharSequence) : Int {
        if(lhs == rhs) { return 0 }
        if(lhs.isEmpty()) { return rhs.length }
        if(rhs.isEmpty()) { return lhs.length }

        val lhsLength = lhs.length + 1
        val rhsLength = rhs.length + 1

        var cost = Array(lhsLength) { it }
        var newCost = Array(lhsLength) { 0 }

        for (i in 1..<rhsLength) {
            newCost[0] = i

            for (j in 1..<lhsLength) {
                val match = if(lhs[j - 1] == rhs[i - 1]) 0 else 1

                val costReplace = cost[j - 1] + match
                val costInsert = cost[j] + 1
                val costDelete = newCost[j - 1] + 1

                newCost[j] = min(min(costInsert, costDelete), costReplace)
            }

            val swap = cost
            cost = newCost
            newCost = swap
        }

        return cost[lhsLength - 1]
    }
}