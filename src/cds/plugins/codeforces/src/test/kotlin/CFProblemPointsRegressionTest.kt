package org.icpclive.cds.plugins.codeforces

import kotlin.test.*
import org.icpclive.cds.api.*

class CFProblemPointsRegressionTest {
    @Test fun missingSubmissionProblemPointsUseStandings() {
        val parser=CFContestInfo()
        parser.updateStandings(standingsFixture())
        val accepted=submissionFixture(1,"OK",600)
        val result=parser.parseSubmissions(listOf(accepted.copy(problem=accepted.problem.copy(points=null)))).single().result as RunResult.IOI
        assertEquals(480.0,result.score.single())
    }
}
