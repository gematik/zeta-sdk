// Routes Maven Central through the Azure Artifacts mirror when AZDO_MAVEN_MIRROR_URL is set (CI only).
val mirrorUrl = System.getenv("AZDO_MAVEN_MIRROR_URL")?.takeIf { it.isNotBlank() }

if (mirrorUrl != null) {
    val centralHosts = setOf("repo.maven.apache.org", "repo1.maven.org")
    val user = System.getenv("AZDO_MAVEN_USERNAME")
    val token = System.getenv("AZDO_MAVEN_PAT")

    fun RepositoryHandler.useMirror() {
        all {
            if (this is MavenArtifactRepository && url.host in centralHosts) {
                setUrl(mirrorUrl)
                if (!token.isNullOrBlank()) {
                    credentials {
                        username = user ?: "azdo"
                        password = token
                    }
                }
            }
        }
    }

    beforeSettings {
        pluginManagement.repositories.useMirror()
        dependencyResolutionManagement.repositories.useMirror()
    }
    allprojects {
        buildscript.repositories.useMirror()
        repositories.useMirror()
    }
}
