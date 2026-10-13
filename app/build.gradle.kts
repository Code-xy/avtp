plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ── 签名解析优先级 ────────────────────────────────────────────────
//  1) CI secrets（ANDROID_KEYSTORE_PATH 环境变量）
//  2) 仓库内置 signing/dev-release.jks —— 固定签名，保证可覆盖升级
//  3) 都没有 → debug 签名
val DEV_STORE_PASS = "avtp-dev-2026"
val DEV_ALIAS = "avtp"

val envKsPath: String = System.getenv("ANDROID_KEYSTORE_PATH").orEmpty()
val repoKs = rootProject.file("signing/dev-release.jks")
val useEnvKs: Boolean = envKsPath.isNotEmpty() && file(envKsPath).exists()
val ksFile: java.io.File? = if (useEnvKs) file(envKsPath) else repoKs.takeIf { it.exists() }
val ksStorePass: String = if (useEnvKs) System.getenv("ANDROID_KEYSTORE_PASSWORD").orEmpty() else DEV_STORE_PASS
val ksAlias: String = if (useEnvKs) System.getenv("ANDROID_KEY_ALIAS").orEmpty().ifEmpty { DEV_ALIAS } else DEV_ALIAS
val ksKeyPass: String = if (useEnvKs) System.getenv("ANDROID_KEY_PASSWORD").orEmpty().ifEmpty { ksStorePass } else DEV_STORE_PASS
val hasKs: Boolean = ksFile != null && ksStorePass.isNotEmpty()

// ── CI 可用 -P 参数覆盖 ──
val pVName: String? = (project.findProperty("vName") as String?)?.takeIf { it.isNotBlank() }
val pVCode: String? = (project.findProperty("vCode") as String?)?.takeIf { it.isNotBlank() }
val pAbi: String = (project.findProperty("abiFilters") as String?)?.trim().orEmpty()
val pSplitAbi: Boolean = (project.findProperty("splitAbi") as String?)?.lowercase() == "true"

android {
    namespace = "com.seagull.avtp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.seagull.avtp"
        minSdk = 23
        targetSdk = 35
        versionCode = pVCode?.toIntOrNull() ?: 1
        versionName = pVName ?: "1.0.0"
        vectorDrawables.useSupportLibrary = true

        if (pAbi.isNotEmpty()) {
            ndk {
                abiFilters.clear()
                abiFilters.addAll(pAbi.split(",").map { it.trim() }.filter { it.isNotEmpty() })
            }
        }
    }

    splits {
        abi {
            isEnable = pSplitAbi
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = false
        }
    }

    signingConfigs {
        if (hasKs) {
            create("rel") {
                storeFile = ksFile
                storePassword = ksStorePass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
                val ext = ksFile!!.extension.lowercase()
                if (ext == "p12" || ext == "pfx") storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasKs) signingConfigs.getByName("rel")
                            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.kotlin_module"
        )
        // GSY 的 ex_so 会塞进 IJK 播放器的 native 库（四架构约 40MB），
        // 我们用 Exo2 内核，不会加载它们。
        jniLibs.excludes += setOf(
            "**/libijkffmpeg.so",
            "**/libijkplayer.so",
            "**/libijksdl.so"
        )
    }

    lint { abortOnError = false }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil:2.7.0")

    val media3 = "1.4.1"
    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-exoplayer-hls:$media3")
    implementation("androidx.media3:media3-exoplayer-dash:$media3")
    implementation("androidx.media3:media3-ui:$media3")
    implementation("androidx.media3:media3-datasource-okhttp:$media3")

    // 播放器：GSYVideoPlayer（Exo2 内核）—— 自带手势音量/亮度/进度、倍速、横屏全屏、
    // 缓存、内核切换；比裸 PlayerView 好用得多。
    implementation("com.github.CarGuo.GSYVideoPlayer:gsyvideoplayer:14.0.0")
}
