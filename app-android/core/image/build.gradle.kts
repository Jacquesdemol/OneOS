plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

dependencies {
    api(project(":core:model"))
    implementation(libs.commons.compress)
    implementation(libs.tukaani.xz)
    testImplementation(libs.junit)
}
