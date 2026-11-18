// Wired CarPlay backend: USB lifecycle, DiPlay adapter, display policy and connection history.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.carlink.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        minSdk = 25
        consumerProguardFiles("consumer-rules.pro")
    }
    ndkVersion = "28.2.13676358"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":carplay-common"))
    api(project(":carplay-shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
