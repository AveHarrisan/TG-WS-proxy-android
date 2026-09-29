import java.util.Properties

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    id("io.github.takahirom.roborazzi")
}

val signing = Properties().apply {
    val f = rootProject.file("keystore/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.aveharrisan.tgwsproxy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aveharrisan.tgwsproxy"
        minSdk = 24
        targetSdk = 35
        versionCode = 11
        versionName = "1.1.9"
    }

    signingConfigs {
        if (signing.isNotEmpty()) create("release") {
            storeFile = rootProject.file("keystore/" + signing.getProperty("storeFile"))
            storePassword = signing.getProperty("storePassword")
            keyAlias = signing.getProperty("keyAlias")
            keyPassword = signing.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signing.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    // Тесты приложения на компьютере: Robolectric вместо телефона, Roborazzi снимает экраны.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Проверка подписи APK внутри Robolectric читает файл через DirectByteBuffer.
            all { it.jvmArgs("--add-opens=java.base/java.nio=ALL-UNNAMED") }
        }
    }
}

// LICENSE целиком (с разделом о коде Flowseal под MIT) едет внутри APK: MIT требует
// сохранять уведомление во всех копиях программы. На экране его не видно.
val licenseAssets = layout.buildDirectory.dir("generated/licenseAssets")
val copyLicense = tasks.register<Copy>("copyLicense") {
    from(rootProject.file("LICENSE")) { rename { "LICENSE.txt" } }
    into(licenseAssets)
}
android.sourceSets["main"].assets.srcDir(licenseAssets)
tasks.named("preBuild") { dependsOn(copyLicense) }
// UpdaterTest качает настоящий подписанный APK — собираем его до тестов, в том числе после clean.
tasks.withType<Test>().configureEach { dependsOn("assembleRelease") }

base.archivesName.set("TG-WS-Proxy-${android.defaultConfig.versionName}")

dependencies {
    implementation(project(":core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("io.github.takahirom.roborazzi:roborazzi:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-compose:1.32.2")
    testImplementation("io.github.takahirom.roborazzi:roborazzi-junit-rule:1.32.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
