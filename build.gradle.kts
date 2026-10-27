import org.gradle.api.file.DuplicatesStrategy

plugins {
    kotlin("jvm") version "2.4.21"
    id("com.gradleup.shadow") version "9.6.1"
}

group = "com.ququla89"
version = "1.0.2"

val pluginVersion = version.toString()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://jitpack.io")
}

val ktlint: NamedDomainObjectProvider<Configuration> = configurations.register("ktlint")

dependencies {
    // Paper API (compileOnly)
    compileOnly("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")

    // Vault API (compileOnly)
    compileOnly("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }

    // Kotlin & Coroutines
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    // Database: HikariCP & SQLite JDBC
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")

    // Ktlint CLI for formatting & verification
    add(ktlint.name, "com.pinterest.ktlint:ktlint-cli:1.8.0")

    // Testing
    testImplementation(kotlin("test-junit5"))
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation("com.github.MilkBowl:VaultAPI:1.7") {
        exclude(group = "org.bukkit", module = "bukkit")
    }
    testImplementation("io.papermc.paper:paper-api:1.21.4-R0.1-SNAPSHOT")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(21)
}

tasks {
    jar {
        enabled = false
    }

    shadowJar {
        archiveBaseName.set("folia-market")
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE

        // Relocate libraries to avoid classpath conflicts (except sqlite-jdbc due to JNI)
        relocate("kotlin", "com.ququla89.foliamarket.lib.kotlin")
        relocate("kotlinx", "com.ququla89.foliamarket.lib.kotlinx")
        relocate("com.zaxxer.hikari", "com.ququla89.foliamarket.lib.hikari")
    }

    build {
        dependsOn(shadowJar)
    }

    processResources {
        inputs.property("version", pluginVersion)
        filesMatching("paper-plugin.yml") {
            expand("version" to pluginVersion)
        }
    }

    test {
        useJUnitPlatform()
    }

    register<JavaExec>("ktlintCheck") {
        group = "verification"
        description = "Check Kotlin code style using ktlint."
        classpath = ktlint.get()
        mainClass.set("com.pinterest.ktlint.Main")
        args = listOf("src/**/*.kt")
    }

    register<JavaExec>("ktlintFormat") {
        group = "formatting"
        description = "Format Kotlin code using ktlint."
        classpath = ktlint.get()
        mainClass.set("com.pinterest.ktlint.Main")
        args = listOf("-F", "src/**/*.kt")
    }
}
