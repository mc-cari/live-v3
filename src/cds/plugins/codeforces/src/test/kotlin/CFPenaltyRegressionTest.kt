package org.icpclive.cds.plugins.codeforces

import kotlin.test.*
import org.icpclive.cds.api.*
import kotlin.time.Duration.Companion.minutes
import org.icpclive.cds.plugins.codeforces.api.data.*

class CFPenaltyRegressionTest {
    @Test fun educationalPenaltyAndGymOverridesMatchStandings() {
        val parser=CFContestInfo()
        val base=standingsFixture("ICPC")
        parser.updateStandings(base)
        assertEquals(10.minutes,parser.toApi().penaltyPerWrongAttempt)
        val gym=base.copy(contest=base.contest.copy(id=100001))
        parser.updateStandings(gym)
        assertEquals(20.minutes,parser.toApi().penaltyPerWrongAttempt)
        val result=CFProblemResult(1.0,10,1,CFProblemResultType.FINAL,180)
        val row=CFRankListRow(submissionFixture(1,"OK",180).author,1,1.0,10,0,0,listOf(result))
        parser.updateStandings(gym.copy(rows=listOf(row)))
        assertEquals(7.minutes,parser.toApi().penaltyPerWrongAttempt)
    }
    @Test fun learnedPenaltySurvivesMissingAndConflictingEvidence() {
        val gym=standingsFixture("ICPC").let {it.copy(contest=it.contest.copy(id=100001))}
        val accepted=submissionFixture(2,"OK",180)
        fun row(minutes: Int)=CFRankListRow(accepted.author,1,1.0,3+minutes,0,0,
            listOf(CFProblemResult(1.0,null,1,CFProblemResultType.FINAL,180)))
        val parser=CFContestInfo()
        parser.updateStandings(gym.copy(rows=listOf(row(7))))
        assertEquals(7.minutes,parser.toApi().penaltyPerWrongAttempt)
        parser.updateStandings(gym)
        assertEquals(7.minutes,parser.toApi().penaltyPerWrongAttempt)
        parser.updateStandings(gym.copy(rows=listOf(row(9))))
        assertEquals(7.minutes,parser.toApi().penaltyPerWrongAttempt)
        val conflict=CFContestInfo()
        conflict.updateStandings(gym.copy(rows=listOf(row(7),row(9))))
        assertEquals(20.minutes,conflict.toApi().penaltyPerWrongAttempt)
        conflict.updateStandings(gym.copy(rows=listOf(row(7))))
        assertEquals(7.minutes,conflict.toApi().penaltyPerWrongAttempt)
    }
}
