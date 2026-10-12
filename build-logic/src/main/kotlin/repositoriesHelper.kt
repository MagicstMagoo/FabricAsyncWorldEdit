import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.logging.Logging
import java.net.URI

// The primary point of this is repository up-time. We replace most other repositories with EngineHub's repository.
// This is because we have stronger up-time guarantees for our repository. However, Maven Central and Sonatype are
// clearly even better, so we allow those as well. We also allow Gradle's plugin repository.
private val ALLOWED_PREFIXES = listOf(
    "https://repo.intellectualsites.dev",
    "https://maven.enginehub.org",
    "https://repo.enginehub.org",
    "https://repo.maven.apache.org/maven2/",
    "https://s01.oss.sonatype.org/content/repositories/snapshots/",
    "https://plugins.gradle.org",
    // Fabric Loom (non-remapping, Minecraft 26.1+) adds these by default, and the
    // Fabric/PaperMC artifacts needed for Minecraft 26.3 are not mirrored on EngineHub.
    "https://maven.fabricmc.net/",
    "https://libraries.minecraft.net/",
    "https://repo.papermc.io/",
    "file:"
)
private val LOGGER = Logging.getLogger("repositoriesHelper")

fun RepositoryHandler.killNonEngineHubRepositories() {
    val toRemove = mutableListOf<MavenArtifactRepository>()
    for (repo in this) {
        if (repo is MavenArtifactRepository && !ALLOWED_PREFIXES.any { repo.url.toString().startsWith(it) }) {
            LOGGER.info("Removing non-EngineHub repository: {}", repo.url)
            toRemove.add(repo)
        }
    }
    toRemove.forEach { remove(it) }
}

fun RepositoryHandler.verifyEngineHubRepositories() {
    for (repo in this) {
        if (repo is MavenArtifactRepository) {
            val urlString = repo.url.toString()
            check(ALLOWED_PREFIXES.any { urlString.startsWith(it) }) {
                "Only EngineHub/Central repositories are allowed: ${repo.url} found"
            }
        }
    }
}

/**
 * The EngineHub release repository, which hosts WorldEdit/FAWE artifacts (Piston, LinBus,
 * the CUI protocol, ...). Declaring repositories on a project makes Gradle ignore the
 * repositories configured in settings.gradle.kts (PREFER_PROJECT), so every module that
 * resolves those artifacts needs this explicitly.
 */
fun RepositoryHandler.addEngineHubRepository() {
    // EngineHub publishes (stripped) copies of some third-party libraries, so restrict these
    // repositories to EngineHub's own groups; everything else must come from Central.
    maven {
        name = "EngineHub"
        url = URI.create("https://repo.enginehub.org/libs-release/")
        content {
            includeGroupAndSubgroups("com.sk89q")
            includeGroupAndSubgroups("org.enginehub")
        }
    }
    // Older WorldEdit/FAWE artifacts (jlibnoise, jchronic, piston, ...) only live here.
    maven {
        name = "EngineHub Legacy"
        url = URI.create("https://maven.enginehub.org/repo/")
        content {
            includeGroupAndSubgroups("com.sk89q")
            includeGroupAndSubgroups("org.enginehub")
        }
    }
    // EngineHub's Maven Central proxy: same content, but a different TLS endpoint, which
    // avoids the intermittent handshake failures seen against repo.maven.apache.org here.
    maven {
        name = "EngineHub Maven Central Mirror"
        url = URI.create("https://repo.enginehub.org/internal/maven-central-proxy/")
    }
}
