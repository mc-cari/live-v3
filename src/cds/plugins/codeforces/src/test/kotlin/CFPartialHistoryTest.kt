package org.icpclive.cds.plugins.codeforces

import kotlinx.serialization.json.Json
import org.icpclive.cds.api.RunResult
import org.icpclive.cds.plugins.codeforces.api.data.CFSubmission
import org.icpclive.cds.plugins.codeforces.api.results.CFStandings
import kotlin.test.Test
import kotlin.test.assertEquals

internal val fixtureJson = Json { ignoreUnknownKeys = true }
internal fun standingsFixture(type: String = "CF", phase: String = "CODING", duration: Int = 7200): CFStandings = fixtureJson.decodeFromString(
    """{"contest":{"id":1,"name":"Fixture","type":"$type","phase":"$phase","frozen":false,"durationSeconds":$duration,"startTimeSeconds":1700000000},"problems":[{"contestId":1,"index":"A","name":"A","type":"PROGRAMMING","points":500}],"rows":[]}"""
)
internal fun submissionFixture(id: Int, verdict: String, time: Int, handle: String = "alice", type: String = "CONTESTANT"): CFSubmission = fixtureJson.decodeFromString(
    """{"id":$id,"contestId":1,"creationTimeSeconds":${1700000000L + time},"relativeTimeSeconds":$time,"problem":{"contestId":1,"index":"A","name":"A","type":"PROGRAMMING","points":500},"author":{"members":[{"handle":"$handle"}],"participantType":"$type","ghost":false},"programmingLanguage":"GNU C++17","verdict":"$verdict","testset":"TESTS","passedTestCount":1,"timeConsumedMillis":12,"memoryConsumedBytes":1024}"""
)
class CFPartialHistoryTest {
    @Test fun omittedWrongAttemptMustNotInflateAcceptedScore() {
        val parser = CFContestInfo()
        parser.updateStandings(standingsFixture())
        val wrong = submissionFixture(101, "WRONG_ANSWER", 300)
        val accepted = submissionFixture(102, "OK", 600)
        val first = parser.parseSubmissions(listOf(accepted, wrong)).single { it.id.value == "102" }
        val second = parser.parseSubmissions(listOf(accepted)).single { it.id.value == "102" }
        assertEquals(430.0, (first.result as RunResult.IOI).score.single())
        assertEquals(first.result, second.result)
    }
}
