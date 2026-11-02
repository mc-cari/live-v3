package org.icpclive.cds.plugins.codeforces

import kotlin.test.*
import org.icpclive.cds.api.*

class CFVerdictRegressionTest {
    @Test fun skippedAndChallengedRunsCannotScore() {
        for (verdict in listOf("SKIPPED","CHALLENGED")) {
            val parser=CFContestInfo()
            parser.updateStandings(standingsFixture())
            val result=parser.parseSubmissions(listOf(submissionFixture(1,verdict,600))).single().result as RunResult.IOI
            assertEquals(0.0,result.score.single())
        }
    }
}
