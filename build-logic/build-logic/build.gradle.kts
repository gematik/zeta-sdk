plugins {
    `kotlin-dsl`
    id("java-gradle-plugin")
    id("org.jetbrains.kotlinx.kover") version "0.9.2"
}

dependencies {
    api(project(":build-logic-base"))
    api(rootLibs.gradle.serialization)
}

val autoDetectPluginRegex = Regex("""^(?:public\s+)?class\s+(\w+)BuildLogicPlugin\s*:.*$""", RegexOption.MULTILINE)
val autoDetectedPlugins = file("src").walkBottomUp().filter { it.extension == "kt" }.flatMap { file ->
    autoDetectPluginRegex.findAll(file.readText()).map { it.groupValues[1] }
}.toList()

gradlePlugin {
    plugins {
        autoDetectedPlugins.forEach {  variant ->
            create("de.gematik.zeta.sdk.build-logic.${variant.lowercase()}") {
                id = name
                implementationClass = "de.gematik.zeta.sdk.buildlogic.${variant}BuildLogicPlugin"
            }
        }
    }
}
