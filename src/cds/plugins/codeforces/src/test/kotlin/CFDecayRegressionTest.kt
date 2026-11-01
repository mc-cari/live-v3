package org.icpclive.cds.plugins.codeforces

import kotlin.test.*
import org.icpclive.cds.api.*

class CFDecayRegressionTest {
    @Test fun scoreDecayDoesNotDependOnContestLength() {
        for (duration in listOf(8100,9000,10800)) {
            val parser=CFContestInfo()
            parser.updateStandings(standingsFixture(duration=duration))
            val result=parser.parseSubmissions(listOf(submissionFixture(1,"OK",600))).single().result as RunResult.IOI
            assertEquals(480.0,result.score.single())
        }
    }
}
