import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Release signing: keystore/release.properties (never in the source archive, never committed).
val releaseProps = Properties().apply {
    val f = rootProject.file("keystore/release.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.chudmusic"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.chudmusic"
        minSdk = 26
        targetSdk = 36
        // -PchudVersionName / -PchudUpdateApi only serve to test the updater against a local server.
        versionName = (project.findProperty("chudVersionName") as String?) ?: "0.4.2"
        // Derived from the name so it always grows: 0.1.0 → 100, 1.2.3 → 10203 (Android refuses a lower one).
        versionCode = versionName!!.split('.', '-').take(3).map { it.toIntOrNull() ?: 0 }
            .let { v -> v.getOrElse(0) { 0 } * 10000 + v.getOrElse(1) { 0 } * 100 + v.getOrElse(2) { 0 } }.coerceAtLeast(1)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Where the in-app updater looks for new versions.
        buildConfigField("String", "RELEASES_REPO", "\"BigFatDeuce/Chud-Music-More\"")
        val updateApi = (project.findProperty("chudUpdateApi") as String?) ?: "https://api.github.com/repos/BigFatDeuce/Chud-Music-More/releases/latest"
        buildConfigField("String", "UPDATE_API", "\"$updateApi\"")
    }

    signingConfigs {
        if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Only the languages we actually ship (keeps library translations out of the APK).
        localeFilters += listOf("fr", "en")
        generateLocaleConfig = true
    }

    sourceSets {
        // The licence texts (root licenses/ folder) ship in the APK and are shown in Settings > Licences.
        getByName("main") { assets.srcDir(rootProject.file("licenses")) }
    }

    lint {
        // Media3's "unstable" APIs (custom DataSource, AudioProcessor, renderers) are used on purpose
        // and pinned to one Media3 version; Kotlin already enforces the other opt-ins.
        disable += "UnsafeOptInUsageError"
    }

    packaging {
        resources {
            excludes += listOf(
                "/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/INDEX.LIST", "META-INF/*.kotlin_module",
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.desugar)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.work)
    implementation(libs.androidx.palette)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.session)
    implementation(libs.media3.okhttp)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.coroutines.android)
    implementation(libs.coroutines.guava)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.okhttp)
    implementation(libs.smbj)
    // Lists the shares of an SMB server (srvsvc over the IPC$ pipe), for the network search.
    implementation(libs.smbj.rpc)
    constraints {
        // smbj-rpc asks for versions from 2015-2020: take current ones.
        implementation("commons-io:commons-io:2.19.0")
        implementation("org.apache.commons:commons-lang3:3.18.0")
        // GHSA-xxph-c9ww-hj94 (Java deserialization, which the app never uses): the fixed version.
        implementation("com.google.guava:guava:33.7.2-jre")
    }
    implementation(libs.newpipe.extractor)
    implementation(libs.reorderable)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    // The app's SQL on a real SQLite, without a phone. Never in the app.
    testImplementation("org.xerial:sqlite-jdbc:3.50.3.0")
    // smbj logs through SLF4J: shown in the test output.
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.17")
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.uiautomator)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}

// Online tests (YouTube Music, LRCLIB, Radio Browser): ./gradlew testDebugUnitTest -Pchud.online=true
tasks.withType<Test>().configureEach {
    systemProperty("chud.online", (project.findProperty("chud.online") ?: "false").toString())
    // SMB tests against a real server: -Pchud.smb=host:port/share -Pchud.smbUser=… -Pchud.smbPassword=…
    for (name in listOf("chud.smb", "chud.smbUser", "chud.smbPassword", "chud.smbIdleMs")) systemProperty(name, (project.findProperty(name) ?: "").toString())
    testLogging { showStandardStreams = true; events("passed", "failed", "skipped"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
