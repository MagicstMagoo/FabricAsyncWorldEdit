plugins {
    `kotlin-dsl`
}

repositories {
    // PaperMC is the origin of paperweight-userdev; the EngineHub mirror of that SNAPSHOT
    // repository frequently stalls on this artifact, so prefer the source of truth.
    maven {
        name = "PaperMC"
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }
    gradlePluginPortal()
    maven {
        name = "EngineHub Repository"
        url = uri("https://maven.enginehub.org/repo/")
    }
}

dependencies {
    implementation(gradleApi())
    implementation(libs.grgit)
    implementation(libs.shadow)
    implementation(libs.paperweight)

    constraints {
        val asmVersion = "[${libs.versions.minimumAsm.get()},)"
        implementation("org.ow2.asm:asm:$asmVersion") {
            because("Need Java 21 support in shadow")
        }
        implementation("org.ow2.asm:asm-commons:$asmVersion") {
            because("Need Java 21 support in shadow")
        }
        implementation("org.vafer:jdependency:[${libs.versions.minimumJdependency.get()},)") {
            because("Need Java 21 support in shadow")
        }
    }
}
