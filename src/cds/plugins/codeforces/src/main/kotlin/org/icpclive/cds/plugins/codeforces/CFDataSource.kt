package org.icpclive.cds.plugins.codeforces

import org.icpclive.cds.ContestDataSource
import org.icpclive.cds.ktor.KtorNetworkSettingsProvider
import org.icpclive.cds.settings.CDSSettings
import org.icpclive.cds.settings.Credential
import org.icpclive.ksp.cds.Builder

@Builder("cf")
public sealed interface CFSettings : CDSSettings, KtorNetworkSettingsProvider {
    public val contestId: Int
    public val apiKey: Credential get() = Credential("", "")
    public val apiSecret: Credential get() = Credential("", "")
    public val asManager: Boolean get() = true
    override fun toDataSource(): ContestDataSource = createSource()
}
