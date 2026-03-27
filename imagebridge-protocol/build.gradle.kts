plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnit()
}

ktlint {
    outputToConsole.set(true)
    ignoreFailures.set(false)
}

dependencies {
    compileOnly(libs.org.json)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.org.json)
}
