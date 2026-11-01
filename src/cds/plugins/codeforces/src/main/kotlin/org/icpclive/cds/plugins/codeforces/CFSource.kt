package org.icpclive.cds.plugins.codeforces

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.*
import org.icpclive.cds.*
import org.icpclive.cds.api.languages
import org.icpclive.cds.util.getLogger
import org.icpclive.cds.ktor.createHttpClient
import org.icpclive.cds.plugins.codeforces.api.data.*
import org.icpclive.cds.plugins.codeforces.api.results.*
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val logger by getLogger()

/** Transport errors can include signed URLs; keep query parameters out of logs. */
internal fun reloadFailureDescription(error: Exception): String =
    "${error.javaClass.simpleName}: ${error.message?.substringBefore('?').orEmpty()}"


/** Raw payload persistence, scoped to one contest. Arrays are upserted by original ID. */
public interface CFHistoryStore {
    public suspend fun read(resource: String): JsonElement?
    public suspend fun merge(resource: String, payload: JsonElement): JsonElement
}

public class MemoryCFHistoryStore : CFHistoryStore {
    private val values = mutableMapOf<String, JsonElement>()
    override suspend fun read(resource: String): JsonElement? = values[resource]
    override suspend fun merge(resource: String, payload: JsonElement): JsonElement {
        val merged = if (resource == "standings") payload else {
            val records = linkedMapOf<String, JsonElement>()
            (values[resource] as? JsonArray).orEmpty().forEach { records[it.jsonObject.getValue("id").jsonPrimitive.content] = it }
            payload.jsonArray.forEach { records[it.jsonObject.getValue("id").jsonPrimitive.content] = it }
            JsonArray(records.values.toList())
        }
        values[resource] = merged
        return merged
    }
}

/** Completion runs after downstream consumers have processed every event in a reload. */
public fun CFSettings.createSource(
    history: CFHistoryStore = MemoryCFHistoryStore(),
    baseUrl: String = "https://codeforces.com/api/",
    includeUnofficial: Boolean = false,
    individualsOnly: Boolean = false,
    supportedTypes: Set<String>? = null,
    interval: Duration = 5.seconds,
    freshConnections: Boolean = false,
    beforeRequest: suspend () -> Unit = {},
    onReload: suspend (restored: Boolean) -> Unit = {},
    onFailure: suspend (Exception) -> Unit = { logger.error { "Codeforces reload failed: ${reloadFailureDescription(it)}" } },
    onRequest: (method: String, outcome: String, durationSeconds: Double, waitSeconds: Double) -> Unit = { _, _, _, _ -> },
    onHistory: (missing: Int) -> Unit = {},
): ContestDataSource {
    require(!asManager || (apiKey.value.isNotBlank() && apiSecret.value.isNotBlank())) {
        "Manager mode requires Codeforces credentials"
    }
    return object : ContestDataSource {
    private val format = Json { ignoreUnknownKeys = true }
    private val parser = CFContestInfo(includeUnofficial, individualsOnly)
    private fun url(method: String): String {
        val params = sortedMapOf("contestId" to contestId.toString())
        if (asManager) {
            params["asManager"] = "true"
            params["apiKey"] = apiKey.value
            params["time"] = Clock.System.now().epochSeconds.toString()
            val prefix = Random.nextInt(100000, 1000000).toString()
            val query = params.entries.joinToString("&") { "${it.key}=${it.value}" }
            val digest = MessageDigest.getInstance("SHA-512").digest("$prefix/$method?$query#${apiSecret.value}".toByteArray())
            params["apiSig"] = prefix + digest.joinToString("") { "%02x".format(it) }
        }
        return baseUrl.trimEnd('/') + "/" + method + "?" + params.entries.joinToString("&") {
            "${URLEncoder.encode(it.key, Charsets.UTF_8)}=${URLEncoder.encode(it.value, Charsets.UTF_8)}"
        }
    }
    private suspend fun fetch(method: String, shared: io.ktor.client.HttpClient): JsonElement {
        val queued = System.nanoTime()
        beforeRequest()
        val started = System.nanoTime()
        var outcome = "transport_error"
        val client = if (freshConnections) network.createHttpClient() else shared
        try {
            val response = client.get(url(method))
            outcome = "http_error"
            check(response.status.value == 200) { "Codeforces $method returned HTTP ${response.status.value}" }
            outcome = "decode_error"
            val envelope = format.decodeFromString<CFStatusWrapper<JsonElement>>(response.bodyAsText())
            outcome = "api_error"
            val result = envelope.unwrap()
            outcome = "decode_error"
            when (method) {
                "contest.standings" -> check(format.decodeFromJsonElement<CFStandings>(result).contest.id == contestId)
                "contest.status" -> format.decodeFromJsonElement<List<CFSubmission>>(result).forEach { require(it.id > 0) }
                "contest.hacks" -> format.decodeFromJsonElement<List<CFHack>>(result).forEach { require(it.id > 0) }
            }
            outcome = "success"
            return result
        } catch (e: CancellationException) {
            outcome = "canceled"
            throw e
        } catch (e: Exception) {
            if (e.javaClass.simpleName.contains("Timeout")) outcome = "timeout"
            throw e
        } finally {
            onRequest(method, outcome, (System.nanoTime() - started) / 1e9, (started - queued) / 1e9)
            if (freshConnections) client.close()
        }
    }

    private suspend fun parse(): ContestParseResult? {
        val raw = history.read("standings") ?: return null
        val standings = format.decodeFromJsonElement<CFStandings>(raw)
        require(supportedTypes == null || standings.contest.type.name in supportedTypes) { "Unsupported contest type: ${standings.contest.type}" }
        parser.updateStandings(standings)
        val submissions = format.decodeFromJsonElement<List<CFSubmission>>(history.read("submissions") ?: JsonArray(emptyList()))
        val hacks = format.decodeFromJsonElement<List<CFHack>>(history.read("hacks") ?: JsonArray(emptyList()))
        val runs = parser.parseSubmissions(submissions) + parser.parseHacks(hacks)
        return ContestParseResult(parser.toApi().copy(languagesList = runs.languages()), runs, emptyList())
    }
    private suspend fun FlowCollector<ContestUpdate>.send(result: ContestParseResult, restored: Boolean) {
        emit(InfoUpdate(result.contestInfo))
        result.runs.sortedWith(compareBy({ it.time }, { it.id.value })).forEach { emit(RunUpdate(it)) }
        onReload(restored)
    }
    override fun getFlow(): Flow<ContestUpdate> = flow {
        val client = network.createHttpClient()
        try {
            try {
                parse()?.let { send(it, true) }
            } catch (e: CancellationException) { throw e }
              catch (e: Exception) { onFailure(e) }
            while (currentCoroutineContext().isActive) {
                try {
                    val raw = fetch("contest.standings", client)
                    val standings = format.decodeFromJsonElement<CFStandings>(raw)
                    require(supportedTypes == null || standings.contest.type.name in supportedTypes) { "Unsupported contest type: ${standings.contest.type}" }
                    check(history.merge("standings", raw) is JsonObject)
                    if (standings.contest.phase != CFContestPhase.BEFORE) {
                        val status = fetch("contest.status", client)
                        val known = (history.read("submissions") as? JsonArray).orEmpty().map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
                        val returned = status.jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
                        onHistory((known - returned).size)
                        check(history.merge("submissions", status) is JsonArray)
                        if (standings.contest.type == CFContestType.CF) {
                            val hacks = fetch("contest.hacks", client)
                            check(history.merge("hacks", hacks) is JsonArray)
                        }
                    }
                    send(checkNotNull(parse()), false)
                } catch (e: CancellationException) { throw e }
                  catch (e: Exception) { onFailure(e) }
                delay(interval)
            }
        } finally { client.close() }
    }
}
}
