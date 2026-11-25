plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

base {
    archivesName.set("Novel Tracker")
}

android {
    namespace = "com.example.noveltracker"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.noveltracker"
        minSdk = 37
        targetSdk = 37
        versionCode = 17
        versionName = "17.0"
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation("org.jsoup:jsoup:1.18.3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation("androidx.compose.material:material-icons-extended")
    implementation("io.coil-kt.coil3:coil-compose:3.3.0")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.3.0")
}