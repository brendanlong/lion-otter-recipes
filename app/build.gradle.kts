import java.time.LocalDate

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.detekt)
}

val gitVersionCode: Int by lazy {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
    }.standardOutput.asText.get().trim().toIntOrNull() ?: 1
}

val gitVersionName: String by lazy {
    val date = LocalDate.now()
    val shortSha = providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
    }.standardOutput.asText.get().trim()
    "%d.%02d.%02d-%s".format(date.year, date.monthValue, date.dayOfMonth, shortSha)
}

android {
    namespace = "com.lionotter.recipes"
    compileSdk = 37

    signingConfigs {
        getByName("debug") {
            storeFile = file(System.getenv("DEBUG_KEYSTORE_PATH") ?: "${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            // Upload key for Google Play App Signing. Google re-signs with their own key
            // before distribution, so this just needs to be consistent.
            storeFile = file(System.getenv("RELEASE_KEYSTORE_PATH") ?: System.getenv("DEBUG_KEYSTORE_PATH") ?: "${System.getProperty("user.home")}/.android/debug.keystore")
            storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("RELEASE_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("RELEASE_KEY_PASSWORD") ?: System.getenv("RELEASE_KEYSTORE_PASSWORD") ?: "android"
        }
    }

    defaultConfig {
        applicationId = "com.lionotter.recipes"
        minSdk = 26
        targetSdk = 37
        versionCode = gitVersionCode
        versionName = gitVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Overridden to true for the release variant in androidComponents below
        manifestPlaceholders["crashlyticsEnabled"] = false
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        create("benchmark") {
            initWith(buildTypes.getByName("release"))
            signingConfig = signingConfigs.getByName("release")
            matchingFallbacks += listOf("release")
            isDebuggable = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeCompiler {
        stabilityConfigurationFiles.add(project.layout.projectDirectory.file("compose-stability.conf"))
        reportsDestination = layout.buildDirectory.dir("compose_compiler")
        metricsDestination = layout.buildDirectory.dir("compose_compiler")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        checkReleaseBuilds = false
        warningsAsErrors = true
        // Version-freshness checks start failing the build whenever something new is
        // released; dependency updates are done deliberately instead.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }

    androidResources {
        // The app is English-only; drop translations bundled by libraries
        localeFilters += "en"
    }

    packaging {
        resources {
            // Desktop/JVM-only files from transitive dependencies
            excludes += "win32-x86/**"
            excludes += "win32-x86-64/**"
            excludes += "google/**/*.proto"
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
            // Duplicate from httpclient5 (Anthropic SDK)
            pickFirsts += "mozilla/public-suffix-list.txt"
        }
    }
}

// Crash reporting is only for the shipped release variant; benchmark and baseline-profile
// variants derived from release must not report to the production project.
androidComponents {
    onVariants { variant ->
        variant.manifestPlaceholders.put("crashlyticsEnabled", (variant.name == "release").toString())
    }
}

// Mapping files are uploaded only by CI release builds of main (CRASHLYTICS_UPLOAD=true).
tasks.matching { it.name.startsWith("uploadCrashlyticsMappingFile") }.configureEach {
    enabled = name == "uploadCrashlyticsMappingFileRelease" && System.getenv("CRASHLYTICS_UPLOAD") == "true"
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Run with ./gradlew detektDebug (type resolution). The variant task reads baseline-debug.xml.
detekt {
    buildUponDefaultConfig = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
    parallel = true
}

// detekt skips generated sources, so give type resolution the compiled BuildConfig as well.
// Setting the classpath replaces the plugin's convention, so the compile classpath is re-added.
val detektBuildConfigClasses = tasks.register<Sync>("detektBuildConfigClasses") {
    from(tasks.named<JavaCompile>("compileDebugJavaWithJavac").flatMap { it.destinationDirectory }) {
        include("**/BuildConfig.class")
    }
    into(layout.buildDirectory.dir("detekt/buildConfigClasses"))
}
fun detektDebugClasspath() = files(
    tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileDebugKotlin").map { it.libraries },
    detektBuildConfigClasses,
)
tasks.withType<dev.detekt.gradle.Detekt>().matching { it.name == "detektDebug" }.configureEach {
    classpath.from(detektDebugClasspath())
}
tasks.withType<dev.detekt.gradle.DetektCreateBaselineTask>().matching { it.name == "detektBaselineDebug" }.configureEach {
    classpath.from(detektDebugClasspath())
}

dependencies {
    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.ui.tooling)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // WorkManager
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Ktor (used for WebScraperService HTTP requests)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    // Anthropic SDK (for Claude API calls)
    implementation(libs.anthropic.sdk)

    // Kotlinx
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)

    // DataStore
    implementation(libs.datastore.preferences)

    // Encryption
    implementation(libs.tink.android)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.crashlytics)

    // Credentials (Google Sign-In)
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.googleid)

    // Coil
    implementation(libs.coil.compose)
    implementation(libs.exifinterface)

    // Readability (content extraction)
    implementation(libs.readability4j)

    // HTML parsing
    implementation(libs.jsoup)

    // Optimization (ILP tag selection)
    implementation(libs.ojalgo)

    // Baseline Profile
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":baselineprofile"))

    lintChecks(libs.compose.lint.checks)

    // Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.navigation.testing)

    // Robolectric + Compose UI Testing
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core.ktx)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Hilt Testing
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.compiler)

    // WorkManager Testing
    testImplementation(libs.work.testing)

    // Room migration testing
    testImplementation(libs.room.testing)

    // Ktor Mock Engine (for Hilt integration tests)
    testImplementation(libs.ktor.client.mock)
}
