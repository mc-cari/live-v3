package org.icpclive.cds.plugins.codeforces

import kotlin.test.*
import org.icpclive.cds.api.*
import org.icpclive.cds.plugins.codeforces.api.data.*

class CFParticipantMetadataRegressionTest {
    @Test fun namesRefreshWithoutHistoricalPayloadsOverwritingStandings() {
        val parser=CFContestInfo(true)
        val original=submissionFixture(1,"OK",100).author
        fun standings(party: CFParty)=standingsFixture().copy(rows=listOf(CFRankListRow(party,1,0.0,0,0,0,emptyList())))
        val named=original.copy(members=listOf(original.members.single().copy(name="Alice Original")))
        parser.updateStandings(standings(named))
        assertEquals("Alice Original",parser.toApi().teams.values.single().displayName)
        val updated=named.copy(members=listOf(named.members.single().copy(name="Alice Updated")))
        parser.updateStandings(standings(updated))
        assertEquals(2,parser.parseSubmissions(listOf(submissionFixture(1,"OK",100),submissionFixture(2,"OK",200))).size)
        val team=parser.toApi().teams.values.single()
        assertEquals("Alice Updated",team.fullName)
        assertEquals(updated,fixtureJson.decodeFromString<CFParty>(team.customFields.getValue("codeforces.party")))
    }

}
