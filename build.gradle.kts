plugins {
    alias(libs.plugins.android.application) apply false
}

tasks.register<Delete>("clean") {
    description = "clean build directory"
    delete(rootProject.layout.buildDirectory)
}
