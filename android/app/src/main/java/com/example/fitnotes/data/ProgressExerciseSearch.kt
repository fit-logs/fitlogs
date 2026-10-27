package com.example.fitnotes.data

import java.util.Locale

data class ProgressExercise(val key: String, val name: String, val aliases: List<String> = emptyList())
fun normaliseSearch(value: String) = value.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
/** Explicit lexical ranking; aliases identify a canonical row, never merge identities. */
fun searchProgressExercises(exercises: List<ProgressExercise>, query: String): List<ProgressExercise> {
    val needle = normaliseSearch(query)
    if (needle.isEmpty()) return exercises.distinctBy { it.key }.sortedBy { normaliseSearch(it.name) }
    val tokens = needle.split(' ')
    return exercises.distinctBy { it.key }.mapNotNull { exercise ->
        val rank = (listOf(exercise.name) + exercise.aliases).map { normaliseSearch(it) }.mapNotNull { name ->
            when { name == needle -> 0; name.startsWith(needle) -> 1; tokens.all { token -> name.split(' ').any { it.contains(token) } } -> 2; else -> null }
        }.minOrNull()
        rank?.let { Triple(exercise, it, normaliseSearch(exercise.name)) }
    }.sortedWith(compareBy({ it.second }, { it.third })).map { it.first }
}
