// SPDX-License-Identifier: AGPL-3.0-only
// Adapted from Liam Morrow and LiftLog contributors, exercise-fuzzy-match.ts,
// revision e7004215562994be7e1640172b4d42145ed76a07. See THIRD_PARTY_NOTICES.md.
package com.example.fitnotes.data

import java.text.Normalizer
import java.util.Locale

fun fuzzyMatchScore(query: String, candidate: String): Double? {
    fun normalized(value: String) = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("[\\u0300-\\u036f]"), "").lowercase(Locale.ROOT).trim()
    val q = normalized(query); val c = normalized(candidate)
    if (q.isEmpty()) return null
    var cursor = 0; var first = -1; var last = -1; var gaps = 0
    for (index in c.indices) {
        if (cursor >= q.length) break
        if (c[index] != q[cursor]) continue
        if (first == -1) first = index
        if (last != -1) gaps += index - last - 1
        last = index; cursor++
    }
    if (cursor != q.length || first == -1 || last == -1) return null
    val compactness = q.length.toDouble() / (last - first + 1)
    val coverage = q.length.toDouble() / c.length
    return (if (c == q) 3.0 else 0.0) + (if (c.startsWith(q)) 1.5 else 0.0) + compactness * 5 + coverage * 2 - gaps * 0.1
}
