plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

tasks.test {
    useJUnitPlatform()
}

// Консольный запуск ядра без Android — для проверки на компьютере:
// ./gradlew :core:run --args="--port 1443 --secret <32 hex>"
tasks.register<JavaExec>("run") {
    group = "application"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.aveharrisan.tgwsproxy.core.CliKt")
    standardInput = System.`in`
}
