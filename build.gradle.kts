plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    application
}

group = "attractor"

fun gitVersion(): String {
    fun cmd(vararg args: String): Pair<Int, String> = try {
        val proc = ProcessBuilder(*args)
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val out = proc.inputStream.bufferedReader().readText().trim()
        proc.waitFor() to out
    } catch (_: Exception) { -1 to "" }

    fun isDirty(): Boolean = cmd("git", "status", "--porcelain").second.isNotEmpty()

    val (tagExit, tagOut) = cmd("git", "tag", "--points-at", "HEAD", "--sort=-version:refname")
    if (tagExit == 0 && tagOut.isNotEmpty()) {
        val tag = tagOut.lines().first()
        return if (isDirty()) "$tag-dirty" else tag
    }
    val sha = cmd("git", "rev-parse", "--short", "HEAD").second.ifEmpty { "unknown" }
    return if (isDirty()) "$sha-dirty" else sha
}

version = gitVersion()

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    runtimeOnly("com.mysql:mysql-connector-j:8.3.0")
    runtimeOnly("org.postgresql:postgresql:42.7.2")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testImplementation("com.h2database:h2:2.2.224")
    testImplementation("io.kotest:kotest-runner-junit5:5.9.1")
    testImplementation("io.kotest:kotest-assertions-core:5.9.1")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("attractor.MainKt")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "25"
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "25"
    targetCompatibility = "25"
}

tasks.test {
    useJUnitPlatform()
}

distributions {
    main {
        contents {
            from("README.md")
            from("LICENSE")
            from("docs/api") { into("docs/api") }
            from("examples")  { into("examples") }
        }
    }
}

tasks.named("assemble") {
    dependsOn("cliJar")
}

tasks.jar {
    archiveBaseName.set("attractor-server-devel")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "attractor.MainKt"
        attributes["Implementation-Version"] = version
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<Jar>("cliJar") {
    archiveBaseName.set("attractor-cli-devel")
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "attractor.cli.CliMainKt"
        attributes["Implementation-Version"] = version
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    from(sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<Jar>("releaseJar") {
    archiveBaseName.set("attractor-server")
    archiveVersion.set(version.toString())
    manifest {
        attributes["Main-Class"] = "attractor.MainKt"
        attributes["Implementation-Version"] = version
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    from(sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.register<Jar>("releaseCliJar") {
    archiveBaseName.set("attractor-cli")
    archiveVersion.set(version.toString())
    manifest {
        attributes["Main-Class"] = "attractor.cli.CliMainKt"
        attributes["Implementation-Version"] = version
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    from(sourceSets.main.get().output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
