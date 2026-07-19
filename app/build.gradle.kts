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
	buildTypes {
		release {
			isMinifyEnabled = false
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
