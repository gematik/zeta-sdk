plugins {
    id("java")
    application
}

group = "de.gematik.zeta"
version = providers.environmentVariable("RELEASE_VERSION").orElse("latest").get()

repositories {
    mavenLocal()
    mavenCentral()
}

// IMPORTANT: This is duplicated in multiple files. When changing this code, update all files.
val isRunningOnCi: Boolean by lazy {
    System.getenv("CI") == "true"
}

application {
    mainClass.set("de.gematik.zeta.Main")
}

dependencies {
    implementation(project(":zeta-sdk"))
}
