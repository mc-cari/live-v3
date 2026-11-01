plugins {
    id("live.cds-plugin-conventions")
}

dependencies {
    api(projects.cds.ktor)
    implementation(projects.cds.utils)
}