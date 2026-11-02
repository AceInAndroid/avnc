import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Properties

plugins {
    id("com.android.application") version "9.2.1"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.20"
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.2.20"
    id("com.android.legacy-kapt") version "9.2.1"
}

// Keep signing credentials local; an alternate path supports CI secret-file injection.
val releaseSigningFile = providers.environmentVariable("FASTVNC_SIGNING_PROPERTIES")
        .map { rootProject.file(it) }
        .getOrElse(rootProject.file("private/signing/release.properties"))
require(!providers.environmentVariable("FASTVNC_SIGNING_PROPERTIES").isPresent || releaseSigningFile.isFile) {
    "FASTVNC_SIGNING_PROPERTIES must point to an existing signing properties file"
}
val releaseSigning = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use { load(it) }
}

android {
    namespace = "com.gaurav.avnc"

    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "tech.zhangbing.fvnc"
        minSdk = 21
        targetSdk = 36
        versionCode = 53
        versionName = "3.3.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments += "room.schemaLocation" to "$projectDir/roomSchema/"
            }
        }

        externalNativeBuild {
            cmake {
                arguments(
                        "-DANDROID_STL=none", // We are not using STL
                        "-DCMAKE_TOOLCHAIN_FILE=${layout.settingsDirectory}/extern/vcpkg/scripts/buildsystems/vcpkg.cmake",
                        "-DVCPKG_CHAINLOAD_TOOLCHAIN_FILE=\${ndk.moduleNdkDir}/build/cmake/android.toolchain.cmake",
                        "-DVCPKG_MANIFEST_MODE=ON"
                )

                targets("native-vnc", "vncclient")
            }
        }
    }

    sourceSets {
        getByName("androidTest").assets.directories += "$projectDir/roomSchema"
    }

    signingConfigs {
        if (releaseSigningFile.isFile) {
            create("fastVncRelease") {
                fun required(key: String) = requireNotNull(releaseSigning.getProperty(key)?.takeIf { it.isNotBlank() }) {
                    "Missing release signing property: $key"
                }
                val configuredStore = File(required("storeFile"))
                storeFile = if (configuredStore.isAbsolute) configuredStore
                else releaseSigningFile.parentFile.resolve(configuredStore.path)
                storePassword = required("storePassword")
                keyAlias = required("keyAlias")
                keyPassword = required("keyPassword")
                storeType = "JKS"
            }
        }
    }

    buildTypes {

        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = " (debug)"
        }

        release {
            signingConfig = signingConfigs.findByName("fastVncRelease")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }

        create("ci") {
            initWith(getByName("release"))
            signingConfig = null
            applicationIdSuffix = ".ci"
            versionNameSuffix = " (CI)"
        }
    }

    buildFeatures {
        dataBinding = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            version = "3.22.1"
            path("CMakeLists.txt")
        }
    }

    bundle {
        density {
            enableSplit = false
        }
        language {
            enableSplit = false
        }
    }

    packaging {
        resources.excludes += "META-INF/LICENSE.md"
        resources.excludes += "META-INF/LICENSE-notice.md"
        resources.excludes += "META-INF/DEPENDENCIES"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_1_8
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.fragment:fragment-ktx:1.8.9")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.dynamicanimation:dynamicanimation-ktx:1.1.0")
    implementation("androidx.biometric:biometric-ktx:1.2.0-alpha05")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.9.3")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")

    val roomVersion = "2.7.2"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    implementation("com.google.android.material:material:1.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.connectbot:sshlib:2.2.36")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-contrib:3.7.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.ext:junit-ktx:1.3.0")
    androidTestImplementation("androidx.room:room-testing:$roomVersion")
    androidTestImplementation("io.mockk:mockk-android:1.14.5")
    androidTestImplementation("org.apache.sshd:sshd-core:2.16.0")
}
