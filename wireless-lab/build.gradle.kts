plugins {
    alias(libs.plugins.android.application)
}

/** Only public license texts are packaged: the lab has no projection or authentication assets. */
abstract class WirelessLabLicenseAssets : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val agplText: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val gplText: RegularFileProperty
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val componentNotices: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val dir = outputDir.get().asFile.resolve("carlink-licenses")
        dir.deleteRecursively()
        check(dir.mkdirs()) { "Cannot create generated license directory: $dir" }
        agplText.get().asFile.copyTo(dir.resolve("AGPL-3.0.txt"))
        gplText.get().asFile.copyTo(dir.resolve("GPL-3.0.txt"))
        componentNotices.get().asFile.copyTo(dir.resolve("NOTICE.txt"))
    }
}

androidComponents {
    onVariants { variant ->
        val licenses = tasks.register<WirelessLabLicenseAssets>("${variant.name}WirelessLabLicenseAssets") {
            agplText.set(rootProject.file("LICENSE"))
            gplText.set(rootProject.file("vendor/diplay/LICENSE"))
            componentNotices.set(rootProject.file("vendor/diplay/docs/THIRD_PARTY_NOTICES.md"))
            outputDir.set(layout.buildDirectory.dir("generated/wirelessLabLicenses/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(licenses, WirelessLabLicenseAssets::outputDir)
    }
}

android {
    namespace = "com.carlink.wirelesslab"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        applicationId = "com.buckydajunior.carlink.f150.wirelesslab"
        minSdk = 25
        targetSdk = 37
        versionCode = 102
        versionName = "0.1.2"
    }
    buildTypes {
        release {
            optimization { enable = true }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
    androidResources {
        localeFilters += "en"
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

val recordingLabScreenshots = providers.gradleProperty("carlink.lab.screenshots").orNull == "true"
tasks.withType<Test>().configureEach {
    systemProperty("carlink.repoRoot", rootDir.path)
    systemProperty("carlink.lab.screenshots", recordingLabScreenshots.toString())
    if (!recordingLabScreenshots) {
        filter.excludeTestsMatching("com.carlink.wirelesslab.gallery.*")
    } else if (name == "testDebugUnitTest") {
        outputs.upToDateWhen { false }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx) {
        // Core -> lifecycle-runtime adds this optional baseline-profile installer. The passive
        // native lab needs no automatic profile installation, so omit its initializer,
        // exported receiver and transitive AndroidX Startup dependency altogether.
        exclude(group = "androidx.profileinstaller", module = "profileinstaller")
    }
    testImplementation(libs.junit)
    testImplementation("org.robolectric:robolectric:4.17")
}
