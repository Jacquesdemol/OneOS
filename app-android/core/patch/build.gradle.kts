plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

dependencies {
    api(project(":core:model"))
    api(project(":core:dex"))
    api(project(":core:image"))
    api(project(":core:safety"))
    implementation(libs.smali.dexlib2)
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
}
