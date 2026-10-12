plugins {
    id("java")
    id("buildlogic.common-java")
    id("signing")
}

// Project-level repositories make Gradle ignore the settings-level ones, so the repositories
// every platform/core module needs have to be added here.
repositories {
    addEngineHubRepository()
    mavenCentral()
}

ext["internalVersion"] = "$version+${rootProject.ext["gitCommitHash"]}"

val enablePublishing = gradle.startParameter.taskNames.any { taskName ->
    taskName.contains("publish", ignoreCase = true)
            || taskName.contains("maven", ignoreCase = true)
            || taskName.contains("nmcp", ignoreCase = true)
            || taskName.contains("sign", ignoreCase = true)
}
if (enablePublishing) {
    pluginManager.apply("maven-publish")
}

plugins.withId("maven-publish") {
    val publishingExtension = the<PublishingExtension>()

    configure<SigningExtension> {
        if (!version.toString().endsWith("-SNAPSHOT")) {
            val signingKey: String? by project
            val signingPassword: String? by project
            useInMemoryPgpKeys(signingKey, signingPassword)
            isRequired
            sign(publishingExtension.publications)
        }
    }

    configure<PublishingExtension> {
        publications {
            register<MavenPublication>("maven") {
                versionMapping {
                    usage("java-api") {
                        fromResolutionOf("runtimeClasspath")
                    }
                    usage("java-runtime") {
                        fromResolutionResult()
                    }
                }
                groupId = "com.fastasyncworldedit"
                artifactId = "${rootProject.name}-${project.name}"
                version = "$version"
                pom {
                    name.set("${rootProject.name}-${project.name}" + " " + project.version)
                    description.set("Blazingly fast Minecraft world manipulation for artists, builders and everyone else.")
                    url.set("https://github.com/IntellectualSites/FastAsyncWorldEdit")

                    licenses {
                        license {
                            name.set("GNU General Public License, Version 3.0")
                            url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                            distribution.set("repo")
                        }
                    }

                    developers {
                        developer {
                            id.set("NotMyFault")
                            name.set("Alexander Brandes")
                            email.set("contact(at)notmyfault.dev")
                            organization.set("IntellectualSites")
                            organizationUrl.set("https://github.com/IntellectualSites")
                        }
                        developer {
                            id.set("SirYwell")
                            name.set("Hannes Greule")
                            organization.set("IntellectualSites")
                            organizationUrl.set("https://github.com/IntellectualSites")
                        }
                        developer {
                            id.set("dordsor21")
                            name.set("dordsor21")
                            organization.set("IntellectualSites")
                            organizationUrl.set("https://github.com/IntellectualSites")
                        }
                    }

                    scm {
                        url.set("https://github.com/IntellectualSites/FastAsyncWorldEdit")
                        connection.set("scm:git:https://github.com/IntellectualSites/FastAsyncWorldEdit.git")
                        developerConnection.set("scm:git:git@github.com:IntellectualSites/FastAsyncWorldEdit.git")
                        tag.set("${project.version}")
                    }

                    issueManagement {
                        system.set("GitHub")
                        url.set("https://github.com/IntellectualSites/FastAsyncWorldEdit/issues")
                    }
                }
            }
        }
    }
}
