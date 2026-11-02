@file:OptIn(org.icpclive.cds.api.InefficientContestInfoApi::class)
package org.icpclive.cds.plugins.codeforces

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.icpclive.cds.*
import org.icpclive.cds.api.*
import org.icpclive.cds.adapters.addComputedData
import org.icpclive.cds.scoreboard.*
import org.icpclive.cds.plugins.codeforces.api.data.*
import kotlin.test.*
import kotlin.time.Duration.Companion.milliseconds
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

class CFIntegrationTest {
    private fun calculate(parser: CFContestInfo, submissions: List<CFSubmission>): ContestStateWithScoreboard = runBlocking {
        val runs=parser.parseSubmissions(submissions)
        flow<ContestUpdate> {emit(InfoUpdate(parser.toApi()));runs.forEach {emit(RunUpdate(it))}}
            .addComputedData().calculateScoreboard(OptimismLevel.NORMAL).last()
    }
    private fun parser(type: String="CF", duration: Int=7200): CFContestInfo = CFContestInfo(true).also {it.updateStandings(standingsFixture(type,duration=duration))}
    @Test fun unofficialMissingFromStandingsUsesEngineAndPreservesMetadata() {
        val state=calculate(parser(),listOf(submissionFixture(1,"WRONG_ANSWER",60,type="OUT_OF_COMPETITION"),submissionFixture(2,"OK",300,type="OUT_OF_COMPETITION")))
        val id="alice".toTeamId()
        assertEquals(440.0,state.scoreboardRowAfter(id).totalScore)
        assertTrue(state.state.infoAfterEvent!!.teams.getValue(id).isOutOfContest)
        val raw=Json.parseToJsonElement(state.state.runsAfterEvent.getValue(2.toRunId()).customFields.getValue("codeforces.submission")).jsonObject
        assertEquals(12,raw.getValue("timeConsumedMillis").jsonPrimitive.int)
        assertEquals(1,raw.getValue("passedTestCount").jsonPrimitive.int)
    }
    @Test fun knownMateoCaseFor135MinuteContest() {
        val duration = 8100
        val parser=parser(duration=duration)
        val points=listOf(500.0,750.0,1250.0,1750.0,2250.0)
        val standings=standingsFixture(duration=duration)
        val problems=points.mapIndexed {i,p -> standings.problems.first().copy(index="ABCDE"[i].toString(),points=p)}
        parser.updateStandings(standings.copy(problems=problems))
        val submissions=listOf(144,1029,1940,2697,6707).mapIndexed {i,time -> submissionFixture(i+1,"OK",time,"MateoCV","OUT_OF_COMPETITION").copy(problem=problems[i])}
        assertEquals(4978.0,calculate(parser,submissions).scoreboardRowAfter("MateoCV".toTeamId()).totalScore)
    }
    @Test fun jiangly1797SixSolves() {
        val parser=parser()
        val standings=standingsFixture()
        val problems=listOf(500.0,1000.0,1500.0,1750.0,2250.0,3000.0).mapIndexed {i,p->standings.problems.first().copy(index="ABCDEF"[i].toString(),points=p)}
        parser.updateStandings(standings.copy(problems=problems))
        val events=listOf(Triple(0,"OK",133),Triple(1,"OK",248),Triple(2,"WRONG_ANSWER",452),Triple(2,"WRONG_ANSWER",531),Triple(2,"OK",597),Triple(3,"OK",988),Triple(4,"TIME_LIMIT_EXCEEDED",1550),Triple(4,"WRONG_ANSWER",1999),Triple(4,"OK",2256),Triple(5,"OK",2953))
        val submissions=events.mapIndexed {i,(problem,verdict,time)->submissionFixture(i+1,verdict,time,"jiangly","OUT_OF_COMPETITION").copy(problem=problems[problem])}
        val row=calculate(parser,submissions).scoreboardRowAfter("jiangly".toTeamId())
        assertEquals(8693.0,row.totalScore)
        assertEquals(6,row.problemResults.filterIsInstance<IOIProblemResult>().count {(it.score ?: 0.0)>0})
    }
    @Test fun icpcPenaltyAndRejudging() {
        val parser=parser("ICPC")
        val wrong=submissionFixture(1,"WRONG_ANSWER",60)
        val accepted=submissionFixture(2,"OK",180)
        val row=calculate(parser,listOf(wrong,accepted,submissionFixture(3,"WRONG_ANSWER",200))).scoreboardRowAfter("alice".toTeamId())
        assertEquals(1.0,row.totalScore);assertEquals(13,row.penalty.inWholeMinutes)
        val changed=calculate(parser,listOf(accepted.copy(verdict=CFSubmissionVerdict.WRONG_ANSWER))).scoreboardRowAfter("alice".toTeamId())
        assertEquals(0.0,changed.totalScore)
    }
    @Test fun educationalAndGymPenaltiesUseContestRules() {
        val wrong=submissionFixture(1,"WRONG_ANSWER",60)
        val accepted=submissionFixture(2,"OK",180)
        val parser=parser("ICPC")
        assertEquals(13,calculate(parser,listOf(wrong,accepted)).scoreboardRowAfter("alice".toTeamId()).penalty.inWholeMinutes)
        val gym=standingsFixture("ICPC").let {it.copy(contest=it.contest.copy(id=100001))}
        parser.updateStandings(gym)
        assertEquals(23,calculate(parser,listOf(wrong,accepted)).scoreboardRowAfter("alice".toTeamId()).penalty.inWholeMinutes)
        val official=CFProblemResult(1.0,10,1,CFProblemResultType.FINAL,180)
        parser.updateStandings(gym.copy(rows=listOf(CFRankListRow(accepted.author,1,1.0,10,0,0,listOf(official)))))
        assertEquals(10,calculate(parser,listOf(wrong,accepted)).scoreboardRowAfter("alice".toTeamId()).penalty.inWholeMinutes)
    }
    @Test fun submittedVerdictRemainsPendingUntilJudged() {
        val parser=parser()
        assertIs<RunResult.InProgress>(parser.parseSubmissions(listOf(submissionFixture(1,"SUBMITTED",100))).single().result)
        val judged=parser.parseSubmissions(listOf(submissionFixture(1,"OK",100))).single()
        assertEquals(498.0,(judged.result as RunResult.IOI).score.single())
    }
    @Test fun systemTestingIsNotOverAndOutOfWindowSubmissionsAreRetained() {
        val parser=parser()
        for(phase in listOf("PENDING_SYSTEM_TEST","SYSTEM_TEST")) {
            parser.updateStandings(standingsFixture(phase=phase))
            assertIs<ContestStatus.RUNNING>(parser.status)
        }
        val runs=parser.parseSubmissions(listOf(submissionFixture(1,"OK",-1),submissionFixture(2,"OK",7201),submissionFixture(3,"OK",100)))
        assertEquals(listOf("1", "2", "3"),runs.map {it.id.value}.sorted())
    }
    @Test fun caseVariantsRetainEngineSpelling() {
        val parser=parser()
        parser.parseSubmissions(listOf(submissionFixture(1,"OK",100,"Alice"))).also {assertEquals(1,it.size)}
        parser.parseSubmissions(listOf(submissionFixture(2,"OK",200,"ALICE"))).also {assertEquals(2,it.size)}
        assertEquals(setOf("Alice", "ALICE"),parser.toApi().teamList.map {it.id.value}.toSet())
        assertEquals(setOf("Alice", "ALICE"),parser.toApi().teamList.map {it.fullName}.toSet())
    }
    @Test fun scoreFloorPretestEligibilityAndChallengedUpdates() {
        val parser=parser()
        val pretest=submissionFixture(1,"WRONG_ANSWER",20).copy(passedTestCount=0)
        val compilation=submissionFixture(2,"COMPILATION_ERROR",30)
        val accepted=submissionFixture(3,"OK",600)
        assertEquals(480.0,calculate(parser,listOf(pretest,compilation,accepted)).scoreboardRowAfter("alice".toTeamId()).totalScore)
        assertEquals(0.0,calculate(parser,listOf(accepted.copy(verdict=CFSubmissionVerdict.CHALLENGED))).scoreboardRowAfter("alice".toTeamId()).totalScore)
        val floor=parser()
        val wrongs=(1..20).map {submissionFixture(it,"WRONG_ANSWER",it*10)}
        assertEquals(150.0,calculate(floor,wrongs+submissionFixture(21,"OK",7100)).scoreboardRowAfter("alice".toTeamId()).totalScore)
    }
    @Test fun supersededAcceptedRunCannotScoreOrAddAnotherWrongAttemptPenalty() {
        val parser = parser()
        val wrong = submissionFixture(1, "WRONG_ANSWER", 300)
        val firstAccepted = submissionFixture(2, "OK", 600)
        assertEquals(430.0, calculate(parser, listOf(wrong, firstAccepted))
            .scoreboardRowAfter("alice".toTeamId()).totalScore)

        val skipped = firstAccepted.copy(verdict = CFSubmissionVerdict.SKIPPED)
        val replacement = submissionFixture(3, "OK", 1200)
        val runs = parser.parseSubmissions(listOf(skipped, replacement)).associateBy { it.id.value }
        assertEquals(0.0, (runs.getValue("2").result as RunResult.IOI).score.single())
        // One real wrong attempt costs 50; the skipped run adds no deduction.
        assertEquals(410.0, (runs.getValue("3").result as RunResult.IOI).score.single())
        assertEquals(410.0, calculate(parser, listOf(skipped, replacement))
            .scoreboardRowAfter("alice".toTeamId()).totalScore)
        // A partial reload must keep the superseded run invalidated.
        assertEquals(410.0, calculate(parser, listOf(replacement))
            .scoreboardRowAfter("alice".toTeamId()).totalScore)
    }

    @Test fun hacksUseSyntheticRunsAndEngineTotals() = runBlocking {
        val parser=parser()
        val runs=parser.parseSubmissions(listOf(submissionFixture(1,"OK",100),submissionFixture(2,"OK",200,"bob")))
        fun hack(id: Int, verdict: String): CFHack = fixtureJson.decodeFromString(
            """{"id":$id,"creationTimeSeconds":1700000600,"hacker":{"members":[{"handle":"alice"}],"participantType":"CONTESTANT","ghost":false},"defender":{"members":[{"handle":"bob"}],"participantType":"CONTESTANT","ghost":false},"verdict":"$verdict","problem":{"contestId":1,"index":"A","name":"A","type":"PROGRAMMING","points":500}}"""
        )
        val hacks=parser.parseHacks(listOf(hack(1,"HACK_SUCCESSFUL"),hack(2,"HACK_UNSUCCESSFUL")))
        val result=flow<ContestUpdate> {emit(InfoUpdate(parser.toApi()));(runs+hacks).sortedBy {it.time}.forEach {emit(RunUpdate(it))}}
            .addComputedData().calculateScoreboard(OptimismLevel.NORMAL).last()
        assertEquals(548.0,result.scoreboardRowAfter("alice".toTeamId()).totalScore)
        assertEquals(0.0,result.scoreboardRowAfter("bob".toTeamId()).totalScore)
        assertTrue(hacks.all {it.id.value.startsWith("hack-") && "codeforces.hack" in it.customFields && "codeforces.submission" !in it.customFields})
    }
    @Test fun standingsMetadataExcludesRowsAndIsCached() {
        val parser=parser()
        val standings=standingsFixture().copy(rows=(1..500).map {
            CFRankListRow(submissionFixture(it,"OK",100,"row$it").author,0,0.0,0,0,0,emptyList())
        })
        parser.updateStandings(standings)
        val metadata=parser.toApi().customFields.getValue("codeforces.standings")
        assertEquals(setOf("contest","problems"),Json.parseToJsonElement(metadata).jsonObject.keys)
        assertTrue(metadata.length<1000)
        assertSame(metadata,parser.toApi().customFields.getValue("codeforces.standings"))
        assertEquals(500,parser.toApi().teams.size)
    }
    @Test fun officialParticipantNamesAndMetadataCanRefresh() {
        val parser=parser()
        val original=submissionFixture(1,"OK",100).author
        fun standings(party: CFParty)=standingsFixture().copy(rows=listOf(CFRankListRow(party,1,0.0,0,0,0,emptyList())))
        val named=original.copy(members=listOf(original.members.single().copy(name="Alice Original")))
        parser.updateStandings(standings(named))
        assertEquals("Alice Original",parser.toApi().teamList.single().displayName)
        val updated=named.copy(members=listOf(named.members.single().copy(name="Alice Updated")))
        parser.updateStandings(standings(updated))
        assertEquals(2,parser.parseSubmissions(listOf(submissionFixture(1,"OK",100),submissionFixture(2,"OK",200))).size)
        val team=parser.toApi().teamList.single()
        assertEquals("Alice Updated",team.fullName)
        assertEquals(updated,fixtureJson.decodeFromString<CFParty>(team.customFields.getValue("codeforces.party")))
    }
}
