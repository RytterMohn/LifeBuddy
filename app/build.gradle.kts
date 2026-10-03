plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "dev.ondevice.gemma.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.ondevice.gemma.app"
        minSdk = 26
        targetSdk = 34
        testInstrumentationRunner = providers.gradleProperty("testRunner").getOrElse("dev.ondevice.gemma.app.HistorySmokeInstrumentation")
        versionCode = 19
        versionName = "0.2.0-alpha18"

        // llama.cpp 目前主要支持 arm64；x86_64 留作模拟器调试
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
}
// Explicit dependency: the app stores typed task records and speaks the planner protocol.
dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    testImplementation(kotlin("test"))
}

// Real provider calls are opt-in and never run as part of the regular offline suite.
tasks.withType<Test>().configureEach {
    if (name !in setOf("learningApiTest", "latencyApiTest", "appSkillsApiTest", "autoSkillsApiTest", "systemToolsApiTest", "extensionsApiTest")) filter.excludeTestsMatching("dev.ondevice.gemma.app.learning.*IntegrationTest")
}
tasks.register<Test>("autoSkillsApiTest") {
    group = "verification"
    description = "Learn a skill from an unfamiliar App, reload it and reuse it with a new parameter through the real planner."
    val unitTests = tasks.named<Test>("testDebugUnitTest").get()
    dependsOn(unitTests.taskDependencies.getDependencies(unitTests))
    testClassesDirs = unitTests.testClassesDirs
    classpath = unitTests.classpath
    filter.includeTestsMatching("dev.ondevice.gemma.app.learning.AutoSkillsIntegrationTest")
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}
tasks.register<Test>("appSkillsApiTest") {
    group = "verification"
    description = "Exercise application skill retrieval and multi-App tasks against the configured provider using synthetic screens."
    val unitTests = tasks.named<Test>("testDebugUnitTest").get()
    dependsOn(unitTests.taskDependencies.getDependencies(unitTests))
    testClassesDirs = unitTests.testClassesDirs
    classpath = unitTests.classpath
    filter.includeTestsMatching("dev.ondevice.gemma.app.learning.AppSkillsIntegrationTest")
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}
tasks.register<Test>("latencyApiTest") {
    group = "verification"
    description = "Compare full and compact phone planning requests using alternating real-provider synthetic tasks."
    val unitTests = tasks.named<Test>("testDebugUnitTest").get()
    dependsOn(unitTests.taskDependencies.getDependencies(unitTests))
    testClassesDirs = unitTests.testClassesDirs
    classpath = unitTests.classpath
    filter.includeTestsMatching("dev.ondevice.gemma.app.learning.PhoneLatencyIntegrationTest")
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}
tasks.register<Test>("learningApiTest") {
    group = "verification"
    description = "Exercise the production planner and learning loop against an explicitly configured provider, with synthetic screens."
    val unitTests = tasks.named<Test>("testDebugUnitTest").get()
    dependsOn(unitTests.taskDependencies.getDependencies(unitTests))
    testClassesDirs = unitTests.testClassesDirs
    classpath = unitTests.classpath
    filter.includeTestsMatching("dev.ondevice.gemma.app.learning.LaowangLearningIntegrationTest")
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}

tasks.register<Test>("systemToolsApiTest") {
    group = "verification"
    description = "Verify alarm, timer, switches and sliders with real model calls against synthetic OS pages."
    val unitTests = tasks.named<Test>("testDebugUnitTest").get()
    dependsOn(unitTests.taskDependencies.getDependencies(unitTests))
    testClassesDirs = unitTests.testClassesDirs
    classpath = unitTests.classpath
    filter.includeTestsMatching("dev.ondevice.gemma.app.learning.SystemToolsIntegrationTest")
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}


tasks.register<Test>("extensionsApiTest") {
    group = "verification"
    description = "Verify bilingual imported skills and lazy external tools against the configured model, with synthetic extension results."
    val unitTests = tasks.named<Test>("testDebugUnitTest").get()
    dependsOn(unitTests.taskDependencies.getDependencies(unitTests))
    testClassesDirs = unitTests.testClassesDirs
    classpath = unitTests.classpath
    filter.includeTestsMatching("dev.ondevice.gemma.app.learning.ExtensionsIntegrationTest")
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}
