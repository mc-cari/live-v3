@file:OptIn(org.icpclive.cds.api.InefficientContestInfoApi::class)
package org.icpclive.cds.plugins.codeforces

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import org.icpclive.cds.*
import org.icpclive.cds.adapters.autoFinalize
import org.icpclive.cds.api.*
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes

class CFReloadFlowTest {
    @Test fun finishedPublicContestDeliversRunsBeforeFinalizing() = runBlocking {
        for (type in listOf("CF", "ICPC")) {
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            val queries = mutableListOf<String>()
            val paths = mutableListOf<String>()
            server.createContext("/api/") { exchange ->
                queries.add(exchange.requestURI.rawQuery)
                paths.add(exchange.requestURI.path.substringAfterLast('/'))
                val result = when (paths.last()) {
                    "contest.standings" -> fixtureJson.encodeToJsonElement(standingsFixture(type, phase="FINISHED"))
                    "contest.status" -> JsonArray(listOf(fixtureJson.encodeToJsonElement(submissionFixture(1,"OK",180,type="OUT_OF_COMPETITION"))))
                    else -> JsonArray(emptyList())
                }
                val bytes = """{"status":"OK","result":$result}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            try {
                val source = CFSettings(1) {
                    asManager=false
                    baseUrl="http://127.0.0.1:${server.address.port}/api/"
                    includeUnofficial=true
                }.toDataSource()
                assertIs<FullReloadContestDataSource>(source)
                val events = withTimeout(10000) { source.getFlow().autoFinalize().toList() }
                val first = (events.first() as InfoUpdate).newInfo
                assertIs<ContestStatus.RUNNING>(first.status)
                if (type == "ICPC") assertEquals(10.minutes, first.penaltyPerWrongAttempt)
                assertTrue(first.teamList.single().isOutOfContest)
                val runIndex=events.indexOfFirst { it is RunUpdate }
                val overIndex=events.indexOfFirst { it is InfoUpdate && it.newInfo.status is ContestStatus.OVER }
                assertTrue(runIndex > 0 && overIndex > runIndex)
                assertIs<ContestStatus.FINALIZED>((events.last() as InfoUpdate).newInfo.status)
                assertTrue(queries.all { it == "contestId=1" })
                assertEquals(if(type=="CF") listOf("contest.standings","contest.status","contest.hacks") else listOf("contest.standings","contest.status"),paths)
            } finally { server.stop(0) }
        }
    }
    @Test fun managerCredentialsAreRequiredOnlyInManagerMode() {
        assertFailsWith<IllegalArgumentException> { CFSettings(1).toDataSource() }
        assertIs<FullReloadContestDataSource>(CFSettings(1) { asManager=false }.toDataSource())
    }
}
