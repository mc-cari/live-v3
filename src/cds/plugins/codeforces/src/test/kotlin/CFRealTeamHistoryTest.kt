@file:OptIn(org.icpclive.cds.api.InefficientContestInfoApi::class)
package org.icpclive.cds.plugins.codeforces

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.icpclive.cds.*
import org.icpclive.cds.api.*
import org.icpclive.cds.adapters.addComputedData
import org.icpclive.cds.scoreboard.*
import org.icpclive.cds.plugins.codeforces.api.data.*
import org.icpclive.cds.plugins.codeforces.api.results.*
import kotlin.test.*

class CFRealTeamHistoryTest {
    private fun replay(contestId: Int, teamId: Int): Pair<ScoreboardRow, List<ProblemResult>> = runBlocking {
        val root = fixture(contestId)
        val selected = root.getValue("selectedTeams").jsonObject.getValue(teamId.toString()).jsonObject
        val teamSubmissions = selected.getValue("submissions").jsonArray
        val team = teamSubmissions.first().jsonObject.getValue("author").jsonObject.getValue("teamId").jsonPrimitive.int
        assertEquals(teamId, team)
        val rows = root.getValue("selectedOfficialRows").jsonArray.map { fixtureJson.decodeFromJsonElement<CFRankListRow>(it) }
        val standings = fixtureJson.decodeFromJsonElement<CFStandings>(buildJsonObject {
            put("contest", root.getValue("contest"))
            put("problems", root.getValue("problems"))
            put("rows", JsonArray(rows.map { fixtureJson.encodeToJsonElement(it) }))
        })
        val submissions = fixtureJson.decodeFromJsonElement<List<CFSubmission>>(teamSubmissions)
        val parser = CFContestInfo(includeUnofficial = true)
        parser.updateStandings(standings)
        val runs = parser.parseSubmissions(submissions)
        assertEquals(submissions.map { it.id }.toSet().size, runs.size, "one engine run per distinct source submission")
        val state = flow<ContestUpdate> {
            emit(InfoUpdate(parser.toApi()))
            runs.forEach { emit(RunUpdate(it)) }
        }.addComputedData().calculateScoreboard(OptimismLevel.NORMAL).last()
        val apiRow = rows.single { it.party.teamId == teamId }
        val apiProblems = apiRow.problemResults
        val target = state.scoreboardRowAfter("team:$teamId".toTeamId())
        when (contestId) {
            639 -> assertEquals(apiRow.points, target.totalScore, 0.01)
            640 -> assertEquals(apiRow.points, target.totalScore, 0.01)
            else -> error("unvalidated contest")
        }
        assertEquals(apiRow.penalty, target.penalty.inWholeMinutes.toInt())
        when (root.getValue("contest").jsonObject.getValue("type").jsonPrimitive.content) {
            "CF" -> {
                val results = target.problemResults.takeLast(apiProblems.size).map { it as IOIProblemResult }
                assertEquals(apiProblems.map { it.points }, results.map { it.score ?: 0.0 })
                assertEquals(apiProblems.map { it.rejectedAttemptCount }, results.map { it.totalAttempts - if ((it.score ?: 0.0) > 0) 1 else 0 })
            }
            "ICPC" -> {
                val results = target.problemResults.takeLast(apiProblems.size).map { it as ICPCProblemResult }
                assertEquals(apiProblems.map { it.points > 0 }, results.map { it.isSolved })
                assertEquals(apiProblems.map { it.rejectedAttemptCount }, results.map { it.wrongAttempts })
            }
        }
        target to target.problemResults
    }

    @Test fun vkCup2016Round1CfScoringAndSelectedOrdering() {
        val dandelion = replay(639, 17181).first
        val zk = replay(639, 17449).first
        assertEquals(4314.0, dandelion.totalScore)
        assertEquals(4219.0, zk.totalScore)
        assertTrue(dandelion.totalScore > zk.totalScore)
    }

    @Test fun vkCup2016WildCardIcpcScoringAndSelectedOrdering() {
        val winner = replay(640, 19873).first
        val second = replay(640, 18158).first
        assertEquals(7.0, winner.totalScore)
        assertEquals(634, winner.penalty.inWholeMinutes)
        assertTrue(winner.penalty < second.penalty)
    }

    @Test fun unofficialTleTeamHasOnlySkippedSubmissionsAndNoScore() = runBlocking {
        val root = fixture(639)
        val items = root.getValue("selectedTeams").jsonObject.getValue("18211").jsonObject.getValue("submissions").jsonArray
        assertEquals(3, items.size)
        assertTrue(items.all { it.jsonObject.getValue("verdict").jsonPrimitive.content == "SKIPPED" })
        val party = fixtureJson.decodeFromJsonElement<CFParty>(items.first().jsonObject.getValue("author"))
        assertEquals(CFPartyParticipantType.OUT_OF_COMPETITION, party.participantType)
        assertTrue(party.teamName == "TLE #98")
        val standingsRows = root.getValue("selectedOfficialRows").jsonArray.map { fixtureJson.decodeFromJsonElement<CFRankListRow>(it) }
        val standings = fixtureJson.decodeFromJsonElement<CFStandings>(buildJsonObject {
            put("contest", root.getValue("contest")); put("problems", root.getValue("problems"))
            put("rows", JsonArray(standingsRows.map { fixtureJson.encodeToJsonElement(it) }))
        })
        val parser = CFContestInfo(includeUnofficial = true)
        parser.updateStandings(standings)
        val runs = parser.parseSubmissions(fixtureJson.decodeFromJsonElement<List<CFSubmission>>(items))
        val state = flow<ContestUpdate> {
            emit(InfoUpdate(parser.toApi())); runs.forEach { emit(RunUpdate(it)) }
        }.addComputedData().calculateScoreboard(OptimismLevel.NORMAL).last()
        val info = state.state.infoAfterEvent!!
        assertTrue(info.teams.getValue("team:18211".toTeamId()).isOutOfContest)
        assertEquals(0.0, state.scoreboardRowAfter("team:18211".toTeamId()).totalScore)
    }

    private fun fixture(contestId: Int): JsonObject =
        Json.parseToJsonElement(checkNotNull(javaClass.getResourceAsStream("/team-replay/contest-$contestId.json")) {
            "missing captured team replay for contest $contestId"
        }.bufferedReader().use { it.readText() }).jsonObject
}
