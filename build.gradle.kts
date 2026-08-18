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
