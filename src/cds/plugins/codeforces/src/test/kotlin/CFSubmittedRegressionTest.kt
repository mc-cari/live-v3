package org.icpclive.cds.plugins.codeforces

import kotlin.test.*
import org.icpclive.cds.api.*

class CFSubmittedRegressionTest {
    @Test fun queuedSubmissionsAreInProgress() {
        val parser=CFContestInfo()
        parser.updateStandings(standingsFixture())
        assertIs<RunResult.InProgress>(parser.parseSubmissions(listOf(submissionFixture(1,"SUBMITTED",100))).single().result)
    }
}
