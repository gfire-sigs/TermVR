plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val configuredAbis: List<String> = providers.gradleProperty("termvrAbis")
    .orElse("arm64-v8a,x86_64")
    .get()
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }

android {
    namespace = "dev.gfire.termvr"
    compileSdk = 36
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        applicationId = "dev.gfire.termvr"
        minSdk = 31
        targetSdk = 36
        versionCode = libs.versions.termvrVersionCode.get().toInt()
        versionName = libs.versions.termvrVersionName.get()

        buildConfigField("String", "GVX_VERSION", "\"${libs.versions.gvx.get()}\"")
        buildConfigField("String", "GVX_COMMIT", "\"${libs.versions.gvxCommit.get()}\"")

        ndk {
            abiFilters += configuredAbis
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    dependenciesInfo {
        // Omit the encrypted AGP dependency block required to pass F-Droid scanner checks.
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        jniLibs {
            // Compress .so entries in APK and extract librungv.so to nativeLibraryDir at install time for ProcessBuilder execution under Android W^X SELinux policy.
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.all {
            it.useJUnitPlatform()
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:data"))
    implementation(project(":core:terminal-vt"))
    implementation(project(":core:terminal-ui"))
    implementation(project(":core:runtime-gvx"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.window)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.kotlinx.coroutines.test)
}
