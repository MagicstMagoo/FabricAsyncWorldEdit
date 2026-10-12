plugins {
    id("net.fabricmc.fabric-loom")
    `java-library`
    id("buildlogic.core-and-platform")
}

description = "The Minecraft-specific implementation of WorldEdit's core module." +
        " This code is shared by all platforms that rely on access without an API."

repositories {
    verifyEngineHubRepositories()
}

dependencies {
    api(project(":worldedit-core"))
    api(project(":worldedit-libs:core-mc"))

    "minecraft"(libs.fabric.minecraft)

    // Provided by the platforms (Fabric / NeoForge both ship Mixin).
    "compileOnly"(libs.fabric.mixin)

    "implementation"(libs.cuiProtocol.common)

    // Silence some warnings, since apparently this isn't on the compile classpath like it should be.
    "compileOnly"(libs.errorprone.annotations)
}

base {
    archivesName.set("${project.name}-mc${libs.fabric.minecraft.get().version}")
}

plugins.withId("maven-publish") {
    configure<PublishingExtension> {
        publications.named<MavenPublication>("maven") {
            artifactId = the<BasePluginExtension>().archivesName.get()
            from(components["java"])
        }
    }
}
