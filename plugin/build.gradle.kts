plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "net.rcwalter"
version = "0.4.2"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Provided by the Paper server at runtime; not shaded into the jar.
    compileOnly("io.papermc.paper:paper-api:26.2.build.+")

    // Declared as a runtime library in plugin.yml under `libraries:` so Paper
    // downloads it at runtime. compileOnly here so it is not bundled.
    compileOnly("org.java-websocket:Java-WebSocket:1.6.0")

    // Gson is bundled with Paper and exposed transitively via paper-api.
    // If the compiler cannot resolve com.google.gson.*, uncomment the line
    // below - but nothing else should be added.
    // compileOnly("com.google.code.gson:gson:2.11.0")

    // Test-only: paper-api is compileOnly and not on the test classpath, so
    // RegionData's Gson usage needs an explicit test dependency (main gets it
    // transitively through paper-api). JUnit 5 for the RegionData RLE codec
    // unit tests (plan.md 3.1/3.3), which are pure Java with no Bukkit/paper-api
    // dependency at all.
    testImplementation("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveFileName.set("ashlar-${project.version}.jar")
}

tasks.compileJava {
    options.release.set(25)
}

tasks.test {
    useJUnitPlatform()
}

runPaper {
    // Local test server folder; kept out of git via plugin/.gitignore.
}

tasks.named<xyz.jpenilla.runpaper.task.RunServer>("runServer") {
    minecraftVersion("26.2")
}
