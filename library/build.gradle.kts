plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

val libraryVersion = "0.1.0"

android {
    namespace = "org.commons.ml"
    compileSdk = 36
    defaultConfig {
        minSdk = 21
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions.jvmTarget = "17"
}

dependencies {
    implementation(files("libs/onnxruntime-android-1.22.0-reduced.jar"))
    testImplementation(kotlin("test"))
}

fun MavenPom.configureCommonsMlMetadata() {
    name.set("Commons ML")
    description.set("Android library for on-device face and license-plate detection.")
    url.set("https://github.com/commons-app/commons-ml")
    licenses {
        license {
            name.set("MIT License")
            url.set("https://github.com/commons-app/commons-ml/blob/main/LICENSE")
            distribution.set("repo")
        }
    }
    developers {
        developer {
            id.set("RitikaPahwa4444")
            name.set("Ritika Pahwa")
            url.set("https://github.com/RitikaPahwa4444")
        }
        developer {
            id.set("rovertrack")
            name.set("Rishan")
            url.set("https://github.com/rovertrack")
        }
    }
    scm {
        url.set("https://github.com/commons-app/commons-ml")
        connection.set("scm:git:github.com/commons-app/commons-ml.git")
        developerConnection.set("scm:git:ssh://github.com/commons-app/commons-ml.git")
    }
}

mavenPublishing {
    coordinates("io.github.commons-app", "commons-ml", libraryVersion)
    pom {
        configureCommonsMlMetadata()
    }
}
