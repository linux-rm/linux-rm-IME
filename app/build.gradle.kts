import java.util.Properties
import java.io.File
plugins {
	alias(libs.plugins.android.application)
}
android {
	namespace = "com.linuxrm.ime"
	compileSdk = 37

	defaultConfig {
		applicationId = "com.linuxrm.ime"
		minSdk = 26
		targetSdk = 37
		versionCode = 1
		versionName = "1.0"
	}
	signingConfigs {
		create("release") {
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
	buildTypes {
		release {
			isMinifyEnabled = false
			signingConfig = signingConfigs.getByName("release")
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_21
		targetCompatibility = JavaVersion.VERSION_21
	}
}
dependencies {
	implementation(libs.androidx.core.ktx)
	implementation(libs.androidx.activity)
	implementation("androidx.recyclerview:recyclerview:1.4.0")
	implementation("com.google.android.material:material:1.14.0")
}