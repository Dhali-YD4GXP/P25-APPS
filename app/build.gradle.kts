plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.p25.apx1000"
    compileSdk = 34
    ndkVersion = "26.1.10909125"

    defaultConfig {
        applicationId = "com.p25.apx1000"
        minSdk = 21
        targetSdk = 34
        versionCode = 16
        versionName = "2.4"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86_64")
        }

        buildConfigField("String", "P25_WS_URL", "\"wss://p25.dhali.my.id/ws\"")
        buildConfigField("String", "P25_API_URL", "\"https://p25.dhali.my.id\"")
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")
    implementation("androidx.media:media:1.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}

// Write an OTA version manifest next to the APK so the backend can serve it
// at /api/version (the build dir is mounted into the backend container).
tasks.register("writeVersionJson") {
    doLast {
        val ext = project.extensions.getByName("android") as com.android.build.gradle.BaseExtension
        val code = ext.defaultConfig.versionCode
        val name = ext.defaultConfig.versionName
        val dir = file("build/outputs/apk/debug")
        dir.mkdirs()
        dir.resolve("version.json").writeText(
            "{\"versionCode\":$code,\"versionName\":\"$name\",\"apkUrl\":\"/p25.apk\"}"
        )
    }
}
tasks.whenTaskAdded {
    if (name == "assembleDebug") finalizedBy("writeVersionJson")
}
