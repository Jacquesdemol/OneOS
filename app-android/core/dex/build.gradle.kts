plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

dependencies {
    implementation(libs.smali.dexlib2)
    implementation(libs.smali.baksmali)
    implementation(libs.smali.assembler)
    implementation(libs.smali.util)
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
}
