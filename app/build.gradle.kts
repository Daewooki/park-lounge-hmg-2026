plugins { id("com.android.application") }
android {
    namespace = "ai.nc.varcospaces"
    compileSdk = 34
    useLibrary("android.car")
    defaultConfig {
        applicationId = "ai.nc.varcospaces"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "0.3.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        debug { isDebuggable = true }
        release { isDebuggable = false; isMinifyEnabled = false }
    }
    androidResources { noCompress += listOf("wav", "ogg", "mp3", "glb") }
}
dependencies { implementation(files("libs/zxing-core-3.5.3.jar")) }

val verifyReleaseReadiness by tasks.registering(Exec::class) {
    group = "verification"
    description = "Validate release assets, rights, and submission evidence."
    workingDir(rootProject.projectDir)
    commandLine("python3", rootProject.file("scripts/preflight.py").absolutePath, "--release")
}

// Both APK and bundle release builds pass through this Android lifecycle task.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyReleaseReadiness)
}
