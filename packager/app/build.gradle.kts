import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
}

compose.desktop {
    application {
        mainClass = "com.wangxiuwen.coursebox.packager.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            // jpackage on Windows chokes on spaces inside the package name
            // and unicode in the vendor / copyright fields, so keep those
            // ASCII-only and human strings in the title shown by the UI.
            packageName = "CourseboxPackager"
            packageVersion = "1.0.0"
            description = "Coursebox packager - build .coursebox.zip from local audio + JSON."
            copyright = "Copyright 2026 Coursebox contributors. Apache-2.0."
            vendor = "Coursebox"

            macOS {
                bundleID = "com.wangxiuwen.coursebox.packager"
                packageName = "CourseboxPackager"
            }
            windows {
                shortcut = true
                menu = true
                upgradeUuid = "5cd0c842-7c12-4e8c-9ea3-7e0c6b3ab9a1"
            }
            linux {
                packageName = "coursebox-packager"
            }
        }
    }
}
