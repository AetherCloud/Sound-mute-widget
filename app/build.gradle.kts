import java.util.Properties

plugins {
	alias(libs.plugins.android.application)
}

val keystoreProperties = Properties().apply {
	val file = rootProject.file("keystore.properties")
	if (file.exists()) {
		file.inputStream().use { load(it) }
	}
}

android {
	namespace = "dk.ftb.soundmutewidget"
	compileSdk {
		version = release(37)
	}

	defaultConfig {
		applicationId = "dk.ftb.soundmutewidget"
		minSdk = 33
		targetSdk = 37
		versionCode = 2
		versionName = "1.1"

		testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
	}

	signingConfigs {
		if (keystoreProperties.isNotEmpty()) {
			create("release") {
				storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
				storePassword = keystoreProperties.getProperty("storePassword")
				keyAlias = keystoreProperties.getProperty("keyAlias")
				keyPassword = keystoreProperties.getProperty("keyPassword")
			}
		}
	}

	buildTypes {
		release {
			optimization {
				enable = false
			}
			signingConfig = signingConfigs.findByName("release")
		}
	}
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_11
		targetCompatibility = JavaVersion.VERSION_11
	}
}

dependencies {
	implementation(libs.androidx.appcompat)
	implementation(libs.androidx.core.ktx)
	implementation(libs.material)
	testImplementation(libs.junit)
	androidTestImplementation(libs.androidx.espresso.core)
	androidTestImplementation(libs.androidx.junit)
}