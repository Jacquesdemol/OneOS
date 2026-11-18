plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(libs.versions.jvmToolchain.get().toInt())
}

dependencies {
    implementation(libs.smali.dexlib2)
    implementation(libs.smali.baksmali)
    implementation(libs.smali.assembler) {
        // smali's POM pulls org.antlr:antlr (compiler-compiler + antlr 2.7.7 +
        // stringtemplate 3.2.1), which reference javax.swing / java.awt and trip
        // Android Lint InvalidPackage checks in :app; runtime uses antlr-runtime only.
        exclude(group = "org.antlr", module = "antlr")
    }
    implementation(libs.smali.util)
    implementation(libs.commons.compress)

    testImplementation(libs.junit)
}
