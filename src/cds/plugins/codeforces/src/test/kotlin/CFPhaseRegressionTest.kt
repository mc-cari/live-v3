package org.icpclive.cds.plugins.codeforces

import kotlin.test.*
import org.icpclive.cds.api.*

class CFPhaseRegressionTest {
    @Test fun systemTestingRemainsRunning() {
        val parser=CFContestInfo()
        for (phase in listOf("PENDING_SYSTEM_TEST","SYSTEM_TEST")) {
            parser.updateStandings(standingsFixture(phase=phase))
            assertIs<ContestStatus.RUNNING>(parser.status)
        }
    }
}
