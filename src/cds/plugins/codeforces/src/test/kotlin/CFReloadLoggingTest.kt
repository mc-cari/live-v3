package org.icpclive.cds.plugins.codeforces

import kotlin.test.*

class CFReloadLoggingTest {
    @Test fun reloadFailureDescriptionsRetainDiagnosticsWithoutSignedQueries() {
        assertEquals("IllegalStateException: Codeforces contest.status returned HTTP 503",
            reloadFailureDescription(IllegalStateException("Codeforces contest.status returned HTTP 503")))
        val message=reloadFailureDescription(java.io.IOException("Failed https://codeforces.com/api/contest.status?apiKey=secret&apiSig=signature"))
        assertEquals("IOException: Failed https://codeforces.com/api/contest.status",message)
        assertFalse("secret" in message)
        assertFalse("signature" in message)
        assertEquals("IOException: ",reloadFailureDescription(java.io.IOException()))
    }
}
