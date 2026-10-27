package com.example.fitnotes.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class RunImportPreview(val candidates: List<HealthCandidate>, val knownKeys: Set<String>, val ignoredKeys: Set<String>, val matches: Map<String, List<RunRecord>>) {
    val earliest get() = candidates.minOfOrNull { runLocalDate(it.session.start, it.session.startOffsetSeconds) }
    val latest get() = candidates.maxOfOrNull { runLocalDate(it.session.start, it.session.startOffsetSeconds) }
    val present get() = candidates.count { it.session.key in knownKeys }
    val ignored get() = candidates.count { it.session.key in ignoredKeys }
    val new get() = candidates.count { it.session.key !in knownKeys && it.session.key !in ignoredKeys && matches[it.session.key].isNullOrEmpty() }
    val review get() = matches.values.count { it.isNotEmpty() }
}
data class RunImportState(val working: Boolean = false, val stage: String = "", val preview: RunImportPreview? = null,
    val message: String = "Choose a period to preview available Garmin runs.", val coverage: String = "")

suspend fun readRunningHistory(page: suspend (String?) -> HealthPage<HealthCandidate>, enrich: suspend (HealthCandidate) -> HealthCandidate,
    progress: (Int) -> Unit): List<HealthCandidate> {
    val records = linkedMapOf<String, HealthCandidate>(); val seen = mutableSetOf<String>(); var token: String? = null
    do {
        currentCoroutineContext().ensureActive()
        val batch = page(token)
        for (candidate in batch.records) { currentCoroutineContext().ensureActive(); if (candidate.session.key !in records) records[candidate.session.key] = enrich(candidate) }
        progress(records.size); token = batch.next?.takeIf { it.isNotEmpty() }
        require(token == null || seen.add(token)) { "Health Connect repeated a page. Please retry." }
    } while (token != null)
    return records.values.toList()
}
