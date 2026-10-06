// Used by scripts/licenses/generate.py: prints every artifact of :app's
// releaseRuntimeClasspath — what ships in the APK — with its POM and file.
allprojects {
    if (path == ":app") {
        tasks.register("printShippedArtifacts") {
            notCompatibleWithConfigurationCache("reads a configuration at execution time")
            doLast {
                val view = configurations.getByName("releaseRuntimeClasspath").incoming
                    .artifactView { lenient(false) }.artifacts.artifacts
                val files = view.associate { it.id.componentIdentifier.displayName to it.file }
                val ids = view.mapNotNull { it.id.componentIdentifier as? org.gradle.api.artifacts.component.ModuleComponentIdentifier }.distinct()
                val poms = dependencies.createArtifactResolutionQuery()
                    .forComponents(ids)
                    .withArtifacts(org.gradle.maven.MavenModule::class.java, org.gradle.maven.MavenPomArtifact::class.java)
                    .execute()
                for (c in poms.resolvedComponents) {
                    val pom = c.getArtifacts(org.gradle.maven.MavenPomArtifact::class.java)
                        .filterIsInstance<org.gradle.api.artifacts.result.ResolvedArtifactResult>().firstOrNull()?.file
                    println("ART\t${c.id.displayName}\t${pom ?: ""}\t${files[c.id.displayName] ?: ""}")
                }
            }
        }
    }
}
