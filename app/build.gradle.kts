import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.text.SimpleDateFormat

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.google.gms.google.services)
    alias(libs.plugins.google.firebase.crashlytics)
}

// val hintRewardedAdUnitId = providers.gradleProperty("HINT_REWARDED_AD_UNIT_ID").orNull.orEmpty()

android {
    namespace = "com.pixlory.color.by.number"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pixlory.color.by.number"
        minSdk = 29
        targetSdk = 36
//        versionCode = 100
//        versionName = "1.0.0"

        versionCode = 1
        versionName = "test"

        val dateTime = SimpleDateFormat("dd-MM-yyyy").format(System.currentTimeMillis())
        setProperty("archivesBaseName", "Pixlory_($versionCode)_$dateTime")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "PIXCOLOR_BASE_URL", "\"https://pixlory.dktechgroup.com/\"")
        buildConfigField("Boolean", "USE_REMOTE_CONTENT", "true")
        buildConfigField("Boolean", "USE_WARM_PAPER_CANVAS", "false")
        buildConfigField("Boolean", "USE_EDGE_UNDERPAINT", "true")
        // Re-enable this when the rewarded hint flow is enabled again.
        // buildConfigField("String", "HINT_REWARDED_AD_UNIT_ID", "\"$hintRewardedAdUnitId\"")
    }

    signingConfigs {
        create("release") {

            // Tự động tìm file có đuôi .jks hoặc .keystore đầu tiên trong thư mục app
            val keystoreFile = project.file(".")
                .listFiles()
                ?.firstOrNull { it.name.endsWith(".jks") || it.name.endsWith(".keystore") }
                ?: file("release.jks") // File dự phòng nếu không tìm thấy

            // Tự động nhận diện file jks nằm cùng thư mục
            storeFile = keystoreFile

            // Gọi mật khẩu an toàn từ biến Group của GitLab
            storePassword = System.getenv("COMPANY_KEY_PASSWORD") ?: ""
            keyPassword = System.getenv("COMPANY_KEY_PASSWORD") ?: ""

            keyAlias = "key0"
        }
    }

    buildTypes {
        debug {
            buildConfigField("Boolean", "USE_REMOTE_CONTENT", "false")
            buildConfigField("Boolean", "USE_WARM_PAPER_CANVAS", "false")
            buildConfigField("Boolean", "USE_EDGE_UNDERPAINT", "true")
        }

        release {
            isMinifyEnabled = false
            buildConfigField("Boolean", "USE_REMOTE_CONTENT", "true")
            buildConfigField("Boolean", "USE_WARM_PAPER_CANVAS", "false")
            buildConfigField("Boolean", "USE_EDGE_UNDERPAINT", "true")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Áp dụng cấu hình release vừa thiết lập
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin.compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

dependencies {

    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.config)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.messaging)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    //ads library
    implementation(libs.ssquadadslibrary)

    //other library
    implementation(libs.lottie)
    implementation(libs.glide)
    implementation(libs.user.messaging.platform)

    implementation(libs.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.shimmer)
    implementation(libs.androidsvg)

    implementation(libs.play.services.ads)

    // Coroutines for background tasks
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
