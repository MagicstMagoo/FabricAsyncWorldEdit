import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.task.RunGameTask

plugins {
    // Minecraft 26.1+ is unobfuscated; the non-remapping Loom plugin is required.
    id("net.fabricmc.fabric-loom")
    `java-library`
    id("buildlogic.platform")
}

platform {
    kind = buildlogic.WorldEditKind.Mod
    includeClasspath = true
}

val fabricApiConfiguration: Configuration = configurations.create("fabricApi")

tasks.withType<RunGameTask>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor(java.toolchain))
}

tasks.named<RunGameTask>("runClient") {
    args("--width", "854", "--height", "480")
}

sourceSets {
    create("testmod") {
        java.setSrcDirs(listOf("src/testmod/java"))
        resources.setSrcDirs(listOf("src/testmod/resources"))
        compileClasspath += named("main").get().output + named("main").get().compileClasspath
        runtimeClasspath += named("main").get().output + named("main").get().runtimeClasspath
    }
}

tasks.register<Jar>("testmodJar") {
    archiveClassifier.set("testmod")
    from(sourceSets["testmod"].output)
}

loom {
    mods {
        create("worldedit") {
            sourceSet(sourceSets["main"])
        }
        create("worldedit-sp-testharness") {
            sourceSet(sourceSets["testmod"])
        }
    }
    runs {
        create("testmodClient") {
            client()
            source(sourceSets["testmod"])
            programArgs("--width", "854", "--height", "480")
            if (project.hasProperty("quickPlayWorld")) {
                programArgs("--quickPlaySingleplayer", project.property("quickPlayWorld") as String)
            }
        }
        create("smokeTestServer") {
            server()
            source(sourceSets["testmod"])
            runDir("run-smoke-26.3")
            vmArg("-Dfawe.smokeTest=true")
        }
    }
}

tasks.named<RunGameTask>("runSmokeTestServer") {
    val smokeDirectory = project.file("run-smoke-26.3")
    val resultFile = smokeDirectory.resolve("server-test-results.json")
    doFirst {
        smokeDirectory.mkdirs()
        if (resultFile.exists() && !resultFile.delete()) {
            throw GradleException("Could not remove the previous smoke test result: $resultFile")
        }
        val serverProperties = smokeDirectory.resolve("server.properties")
        if (!serverProperties.exists()) {
            serverProperties.writeText(
                """
                level-name=fawe-smoke-world
                level-type=minecraft:flat
                generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}],"biome":"minecraft:plains"}
                generate-structures=false
                server-ip=127.0.0.1
                server-port=25576
                view-distance=2
                simulation-distance=2
                pause-when-empty-seconds=0
                """.trimIndent() + "\n"
            )
        }
    }
    doLast {
        if (!resultFile.isFile) {
            throw GradleException("The Fabric smoke test did not produce $resultFile")
        }
        val result = groovy.json.JsonSlurper().parse(resultFile) as Map<*, *>
        if (result["passed"] != true) {
            throw GradleException("Fabric smoke tests failed; see $resultFile")
        }
    }
}

repositories {
    verifyEngineHubRepositories()
}

dependencies {
    "api"(project(":worldedit-core"))
    "api"(project(":worldedit-core-mc"))
    "testImplementation"(libs.fabric.loader)

    "minecraft"(libs.fabric.minecraft)
    // No mappings: Minecraft 26.1+ ships unobfuscated and includes parameter names.
    "implementation"(libs.fabric.loader)
    "implementation"(libs.cuiProtocol.common)
    "implementation"(libs.cuiProtocol.fabric)
    // The protocol payload classes live in the plain "common" library, so it is bundled as a
    // normal jar-in-jar (no shadowing); the Fabric artifact registers the handler with Loader.
    "include"(libs.cuiProtocol.common)
    "include"(libs.cuiProtocol.fabric) {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling::class, Bundling.SHADOWED))
        }
    }

    // FAWE core runtime deps not provided by vanilla/Fabric (Spigot ships some of
    // these on the Bukkit side). ZSTD must not be relocated:
    // https://github.com/luben/zstd-jni/issues/189
    "include"(libs.parallelgzip)
    "implementation"(libs.parallelgzip)
    "include"(libs.sparsebitset)
    "implementation"(libs.sparsebitset)
    "include"(libs.zstd)
    "implementation"(libs.zstd)
    "include"(libs.json.simple)
    "implementation"(libs.json.simple)
    "include"(libs.jchronic)
    "implementation"(libs.jchronic)
    // Vanilla dropped snakeyaml; FAWE's settings parser (config.yml) needs it.
    "include"(libs.snakeyaml)
    "implementation"(libs.snakeyaml)
    "include"(libs.rhino)
    "implementation"(libs.rhino)

    // [1] Load the API dependencies from the fabric mod json...
    @Suppress("UNCHECKED_CAST")
    val fabricModJson = file("src/main/resources/fabric.mod.json").bufferedReader().use {
        groovy.json.JsonSlurper().parse(it) as Map<String, Map<String, *>>
    }
    val wantedDependencies = (fabricModJson["depends"] ?: error("no depends in fabric.mod.json")).keys
        .filter { it == "fabric-api-base" || it.contains(Regex("v\\d$")) }
        .toSet()
    // [2] Request the matching dependency from fabric-loom
    for (wantedDependency in wantedDependencies) {
        val dep = fabricApi.module(wantedDependency, libs.versions.fabric.api.get())
        "include"(dep)
        "implementation"(dep)
    }

    // No need for this at runtime
    "compileOnly"(libs.fabric.permissions.api)

    // Silence some warnings, since apparently this isn't on the compile classpath like it should be.
    "compileOnly"(libs.errorprone.annotations)
}

configure<BasePluginExtension> {
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

tasks.named<Copy>("processResources") {
    val internalVersion = project.ext["internalVersion"]
    // The Fabric mod must report a *WorldEdit*-compatible version: mods that depend on
    // WorldEdit declare version predicates such as "worldedit": ">=7.4.6-beta-02", and FAWE's
    // own 2.x version would fail every one of them. The FAWE build is advertised separately.
    val worldEditCompatVersion = providers.gradleProperty("worldEditCompatVersion").getOrElse("7.4.6-beta-02")
    // this will ensure that this task is redone when the versions change.
    inputs.property("version", worldEditCompatVersion)
    inputs.property("faweVersion", internalVersion)
    filesMatching("fabric.mod.json") {
        this.expand(
            mapOf(
                "version" to worldEditCompatVersion,
                "faweVersion" to internalVersion,
            )
        )
    }
}

tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("dist")
    // Use the JAR output, not classes, as we need jar-in-jar from loom to work properly.
    dependsOn(tasks.jar)
    val processedFabricModJson = layout.buildDirectory.file("resources/main/fabric.mod.json").get().asFile.absoluteFile
    eachFile {
        // Exclude the fabric.mod.json in the resources folder to allow the one from Fabric's jar task to be added
        if (path == "fabric.mod.json" && file.absoluteFile == processedFabricModJson) {
            exclude()
        }
    }
    from(zipTree(tasks.jar.flatMap { it.archiveFile }))
    relocate("org.antlr.v4", "com.sk89q.worldedit.antlr4")
    relocate("net.royawesome.jlibnoise", "com.sk89q.worldedit.jlibnoise")
    dependencies {
        include(dependency("org.antlr:antlr4-runtime"))
        include(dependency("com.sk89q.lib:jlibnoise"))
    }
}

tasks.named("assemble").configure {
    dependsOn("shadowJar")
}
