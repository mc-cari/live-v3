package org.icpclive.cds.plugins.codeforces

import kotlin.test.Test
import kotlin.test.assertEquals
import org.icpclive.cds.api.RunResult

class CFDecayRegressionTest {
    @Test fun matchesPublishedCodeforcesScoresAcrossDurations() {
        // Expected values, solve times and rejected counts come from contest.standings.
        data class Case(val name: String, val duration: Int, val seconds: Int, val maximum: Double, val wrong: Int, val expected: Double)
        val cases = listOf(
            Case("1137/sunset/A", 9000, 470, 500.0, 0, 489.0),
            Case("1137/sunset/E", 9000, 4072, 2500.0, 1, 1914.0),
            Case("2262/Kevin114514/C", 10800, 2479, 1750.0, 1, 1509.0),
            Case("1735/9u46/B", 8100, 763, 750.0, 1, 664.0),
            Case("1916/Benq/F", 7200, 5500, 2750.0, 3, 1599.0),
        )
        for (case in cases) {
            val standings = standingsFixture(duration = case.duration)
            val problem = standings.problems.single().copy(points = case.maximum)
            val parser = CFContestInfo()
            parser.updateStandings(standings.copy(problems = listOf(problem)))
            val wrong = (1..case.wrong).map {
                submissionFixture(it, "WRONG_ANSWER", it).copy(problem = problem)
            }
            val accepted = submissionFixture(case.wrong + 1, "OK", case.seconds).copy(problem = problem)
            val run = parser.parseSubmissions(wrong + accepted).single { it.id.value == accepted.id.toString() }
            assertEquals(case.expected, (run.result as RunResult.IOI).score.single(), case.name)
        }
    }
}
