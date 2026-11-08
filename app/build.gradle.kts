import java.util.Properties
import java.io.File
plugins {
	alias(libs.plugins.android.application)
	alias(libs.plugins.compose.compiler)
}
android {
	namespace = "com.linuxrm.IME"
	compileSdk = 37
	defaultConfig {
		applicationId = "com.linuxrm.IME"
		minSdk = 24
		targetSdk = 37
		versionCode = 1
		versionName = "1.0"
		ndk {
			abiFilters.addAll(listOf("arm64-v8a", "x86_64"))
		}
	}
	signingConfigs {
		create("release") {
			enableV1Signing = true
			enableV2Signing = true
			enableV3Signing = true
			val localProperties = Properties()
			val localFile = rootProject.file("local.properties")
			if (localFile.exists()) {
				localProperties.load(localFile.inputStream())
			}
			val customKeystore = File(System.getProperty("user.home"), "my-release-key.jks")
			val hasCustom = customKeystore.exists() && localProperties.containsKey("KEYSTORE_PASS")
			storeFile = if (hasCustom) {
				customKeystore
			} else {
				File(System.getProperty("user.home"), ".android/debug.keystore")
			}
			storePassword = if (hasCustom) localProperties.getProperty("KEYSTORE_PASS") else "android"
			keyPassword = if (hasCustom) localProperties.getProperty("KEY_PASS") else "android"
			keyAlias = if (hasCustom) localProperties.getProperty("KEY_ALIAS") else "androiddebugkey"
		}
	}
	buildFeatures {
		compose = true
		viewBinding = true
		buildConfig = true
	}
	buildTypes {
		release {
			isMinifyEnabled = true
			isShrinkResources = true
			signingConfig = signingConfigs.getByName("release")
			proguardFiles(
				getDefaultProguardFile("proguard-android-optimize.txt"),
			)
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_21
		targetCompatibility = JavaVersion.VERSION_21
	}
	externalNativeBuild {
		cmake {
			path = file("src/main/jni/CMakeLists.txt")
		}
	}
	packaging {
		resources {
			excludes += setOf(
				"/META-INF/*.version",
				"/META-INF/*.kotlin_module",
				"/META-INF/androidx/**",
				"/DebugProbesKt.bin",
				"/kotlin-tooling-metadata.json"
			)
		}
	}
}
dependencies {
	implementation(platform(libs.androidx.compose.bom))
	implementation(libs.androidx.compose.ui)
	implementation(libs.androidx.compose.ui.graphics)
	implementation(libs.androidx.compose.ui.tooling.preview)
	implementation(libs.androidx.compose.material3)
	implementation(libs.androidx.compose.material.icons.extended)
	implementation(libs.androidx.activity.compose)
	implementation(libs.androidx.core.ktx)
	implementation(libs.material)
	implementation(libs.androidx.recyclerview)
	implementation(libs.splitties.bitflags)
	implementation(libs.kotlinx.coroutines.android)
	implementation(libs.timber)
}
