plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.ck.lx04voice"
    compileSdk = 27

    defaultConfig {
        applicationId = "io.ck.lx04voice"
        minSdk = 27
        targetSdk = 27
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    lint {
        abortOnError = false
    }
}

// 零依赖：只用 Android 框架 API，保证在 Android 8.1 上编译通过
dependencies {
}
