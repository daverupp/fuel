dependencies {
    implementation ("io.ktor:ktor-client-core-jvm:1.3.1")
    implementation (project(":fuel-singleton"))

    testImplementation (Library.JUNIT)
}