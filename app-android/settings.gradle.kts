import java.io.File
import java.util.Properties

// Credentials for the tribalfs GitHub Packages repositories (oneui-design,
// sesl-androidx, sesl-material-components-android). Checks local config files
// (~/.config/twidget/github.properties, app-android/local.properties), Gradle
// properties (ghPackagesUser/ghPackagesToken, gpr.user/gpr.key), and CI
// environment variables (GH_PACKAGES_*, GPR_*, GITHUB_*).
fun firstNonBlank(vararg values: String?): String =
    values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()

fun loadPropertiesIfExists(file: File): Properties = Properties().apply {
    file.takeIf { it.isFile }?.inputStream()?.use { load(it) }
}

val githubProperties = loadPropertiesIfExists(
    File(System.getProperty("user.home"), ".config/twidget/github.properties"),
)
val localProperties = loadPropertiesIfExists(File(rootDir, "local.properties"))

val ghPackagesUser: String = firstNonBlank(
    githubProperties.getProperty("ghUsername"),
    localProperties.getProperty("gpr.user"),
    providers.gradleProperty("ghPackagesUser").orNull,
    providers.gradleProperty("gpr.user").orNull,
    System.getenv("GH_PACKAGES_USER"),
    System.getenv("GPR_USER"),
    System.getenv("GITHUB_ACTOR"),
)
val ghPackagesToken: String = firstNonBlank(
    githubProperties.getProperty("ghAccessToken"),
    localProperties.getProperty("gpr.key"),
    providers.gradleProperty("ghPackagesToken").orNull,
    providers.gradleProperty("gpr.key").orNull,
    System.getenv("GH_PACKAGES_TOKEN"),
    System.getenv("GPR_TOKEN"),
    System.getenv("GITHUB_TOKEN"),
)

if (ghPackagesToken.isEmpty() && !gradle.startParameter.isOffline) {
    logger.warn(
        "GitHub Packages credentials are not configured (set GH_PACKAGES_TOKEN / GPR_TOKEN " +
            "or ~/.config/twidget/github.properties). Resolution of tribalfs SESL9 artifacts " +
            "from maven.pkg.github.com requires a token with read:packages scope when not cached.",
    )
}

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// io.github.oneuiproject:icons and standard dependencies live on Maven Central /
// Google Maven; io.github.tribalfs:oneui-design and sesl.* (SESL9) are hosted
// on GitHub Packages under tribalfs and require authentication.
val tribalfsPackagesRepos = listOf(
    "https://maven.pkg.github.com/tribalfs/oneui-design",
    "https://maven.pkg.github.com/tribalfs/sesl-androidx",
    "https://maven.pkg.github.com/tribalfs/sesl-material-components-android",
)

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        tribalfsPackagesRepos.forEach { repoUrl ->
            maven(repoUrl) {
                content {
                    includeGroupByRegex("^(io\\.github\\.tribalfs|sesl\\..*)$")
                }
                if (ghPackagesUser.isNotEmpty() && ghPackagesToken.isNotEmpty()) {
                    credentials {
                        username = ghPackagesUser
                        password = ghPackagesToken
                    }
                }
            }
        }
    }
}

rootProject.name = "OneOS"

include(":app")
// OneOS inside the guest: "Back to One UI" (guest/build.gradle.kts).
include(":guest")
include(":core:model")
include(":core:device")
include(":core:root")
include(":core:safety")
