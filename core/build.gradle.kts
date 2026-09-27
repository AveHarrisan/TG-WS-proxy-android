plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test"))
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
