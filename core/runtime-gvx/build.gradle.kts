plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

val configuredAbis: List<String> = providers.gradleProperty("termvrAbis")
    .orElse("arm64-v8a,x86_64")
    .get()
    .split(",")
    .map { it.trim() }
    .filter { it.isNotEmpty() }

android {
    namespace = "dev.gfire.termvr.core.runtime"
    compileSdk = 36
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = 31

        ndk {
            abiFilters += configuredAbis
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = libs.versions.cmake.get()
        }
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
    api(project(":core:model"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.kotlinx.coroutines.test)
}
