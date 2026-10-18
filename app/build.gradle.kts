plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// GitHub Actions 빌드 번호를 버전으로 사용 → 매번 덮어쓰기 설치 가능
val buildNo = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.cury.cctv"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.cury.cctv"
        minSdk = 24
        targetSdk = 35
        versionCode = buildNo
        versionName = "1.0.$buildNo"
    }

    // 고정 서명키: 빌드마다 같은 키라서 업데이트(덮어쓰기) 설치가 됨
    signingConfigs {
        create("fixed") {
            storeFile = file("cctv.jks")
            storePassword = "cctv1234"
            keyAlias = "cctv"
            keyPassword = "cctv1234"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

dependencies {
    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}
