plugins {
    id("java")
    application
}

repositories {
    mavenCentral()
    maven("https://www.jetbrains.com/intellij-repository/releases")
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
}

dependencies {
    implementation("com.jetbrains.intellij.platform:util:262.9437.214")
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

application {
    mainClass.set("Main")
    // com.intellij.util.io.ByteBufferUtil reflectively accesses sun.nio.ch.DirectBuffer and
    // jdk.internal.ref.Cleaner to explicitly unmap direct buffers when the page cache evicts
    // pages under memory pressure -- needs both opened, unlike the real IDE which bakes these
    // into its own .vmoptions file.
    applicationDefaultJvmArgs = listOf(
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/jdk.internal.ref=ALL-UNNAMED"
    )
}

// A standalone "fat" jar (app classes + all runtime dependencies merged in, with a Main-Class
// manifest entry) for uploading as a single downloadable artifact, e.g. to a GitHub release --
// runnable with just `java -jar` and no separate classpath/dependencies to manage.
// Note: unlike `gradlew run`, this does NOT bake in the --add-opens flags above (there's no
// manifest equivalent for JVM args) -- pass them on the `java` command line, see README.
tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Builds a standalone runnable jar with all dependencies merged in."
    archiveClassifier.set("standalone")
    manifest {
        attributes["Main-Class"] = application.mainClass.get()
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    from(configurations.runtimeClasspath.map { rc -> rc.map { if (it.isDirectory) it else zipTree(it) } })
}
