package org.icpclive.converter.export.clics

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.icpclive.cds.*
import org.icpclive.cds.api.*
import org.icpclive.cds.scoreboard.calculateScoreboard
import org.icpclive.clics.FeedVersion
import org.icpclive.clics.clicsEventsSerializersModule
import org.icpclive.clics.events.SubmissionEvent
import kotlin.test.*
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ClicsFeedGeneratorTest {
    @OptIn(InefficientContestInfoApi::class)
    private fun info() = ContestInfo(
        name = "Generic fixture",
        status = ContestStatus.RUNNING(Instant.fromEpochSeconds(0), null),
        resultType = ContestResultType.ICPC,
        contestLength = 2.hours,
        freezeTime = null,
        problemList = listOf(ProblemInfo("A".toProblemId(), "A", "Problem A", 0)),
        teamList = listOf(false, true).mapIndexed { index, unofficial ->
            TeamInfo(index.toTeamId(), "Team $index", "Team $index", emptyList(), null,
                emptyMap(), false, unofficial, null, customFields = mapOf("native" to "value"))
        },
        groupList = emptyList(), organizationList = emptyList(), languagesList = emptyList(),
        penaltyRoundingMode = PenaltyRoundingMode.EACH_SUBMISSION_DOWN_TO_MINUTE,
        customFields = mapOf("native" to "contest")
    )

    @Test
    fun optionalUnofficialRowsKeepEngineTiesAndDefaultEligibility() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val generator = ClicsFeedGenerator(scope, flowOf(InfoUpdate(info())).calculateScoreboard(OptimismLevel.NORMAL), true)
            val default = withTimeout(2000) { generator.getScoreboard(null) }
            val extended = generator.getScoreboard(null, true)
            assertEquals(1, default.rows.size)
            assertEquals(2, extended.rows.size)
            assertEquals(listOf(1, 1), extended.rows.map { it.rank })
            val json = Json { serializersModule = clicsEventsSerializersModule(FeedVersion.DRAFT, "") }
            val value = generator.getExtendedScoreboard(null, true, json)
            assertEquals(generator.feedInstance, value["x_live"]!!.jsonObject["feed_instance"]!!.jsonPrimitive.content)
            assertTrue(value["x_live"]!!.jsonObject["watermark"]!!.jsonPrimitive.content.startsWith("live-cds-"))
            assertFalse(json.encodeToJsonElement(default).jsonObject.containsKey("x_live"))
        } finally { scope.cancel() }
    }

    @Test
    fun nativeProgressChangesEmitEvenWithIdenticalClicsSubmission() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val run = RunInfo("1".toRunId(), RunResult.InProgress(0.5), "A".toProblemId(), "0".toTeamId(), 1.seconds, null)
            val updates = flow {
                emit(InfoUpdate(info()))
                emit(RunUpdate(run.copy(customFields = mapOf("tested" to "22"))))
                emit(RunUpdate(run.copy(customFields = mapOf("tested" to "23"))))
                emit(RunUpdate(run.copy(customFields = mapOf("tested" to "23"))))
            }.calculateScoreboard(OptimismLevel.NORMAL)
            val generator = ClicsFeedGenerator(scope, updates, true)
            val events = withTimeout(2000) { generator.eventFeed.filter { it.event is SubmissionEvent }.take(2).toList() }
            assertEquals(listOf("22", "23"), events.map { it.metadata["custom_fields"]!!.jsonObject["tested"]!!.jsonPrimitive.content })
            assertEquals((events[0].event as SubmissionEvent).data, (events[1].event as SubmissionEvent).data)
        } finally { scope.cancel() }
    }
}
