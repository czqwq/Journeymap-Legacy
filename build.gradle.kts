import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

val configFile = file("project.properties")
val srcWebDir = "src/main/resources/assets/journeymap/web/"
val implSrc = "build/resources/java/journeymap/feature/impl/"

val config = configFile.reader(Charsets.UTF_8).use {
    val prop = Properties()
    prop.load(it)
    return@use prop
}

val forge_version = config.getProperty("forge_version")!!
val minecraft_version = config.getProperty("minecraft_version")!!
val group_id = config.getProperty("group_id")!!
val mod_id = config.getProperty("mod_id")!!
val fairPlay = config.getProperty("fairPlay")!!
val unlimited = config.getProperty("unlimited")!!
val noRadar = config.getProperty("noRadar")!!
val dateFormat = config.getProperty("dateFormat")!!

val jmVersionComponents = project.version.toString().split(".")
val jm_major = jmVersionComponents.getOrNull(0) ?: config.getProperty("jm_major")!!
val jm_minor = jmVersionComponents.getOrNull(1) ?: config.getProperty("jm_minor")!!
val jm_micro = jmVersionComponents.getOrNull(2) ?: config.getProperty("jm_micro")!!
val jm_patch = jmVersionComponents.getOrNull(3) ?: config.getProperty("jm_patch")!!

minecraft {
    injectedTags.put("JM_VERSION", project.version.toString())
    injectedTags.put("MC_VERSION", minecraft_version)
    injectedTags.put("FORGE_VERSION", forge_version)
    injectedTags.put("MAJOR", jm_major)
    injectedTags.put("MINOR", jm_minor)
    injectedTags.put("MICRO", jm_micro)
    injectedTags.put("PATCH", jm_patch)
}

fun getDate(): String {
    val date = LocalDateTime.now()
    return date.format(DateTimeFormatter.ofPattern(dateFormat))
}

val processDocs by tasks.registering(Copy::class) {
    group = "JourneyMap"
    description = "Substitute templates in docs"

    from(file("doc/")) {
        include("*.html")
        expand(
            "version" to project.version.toString(),
            "mcversion" to minecraft_version,
            "forgeversion" to forge_version,
            "date" to getDate()
        )
    }
    into("build/doc")

    doFirst {
        file("build/doc").mkdirs()
    }
}

with(gtnhGradle.toolchainModule) {
    mcmodInfoProperties.put("version", project.version.toString())
    mcmodInfoProperties.put("mcversion", minecraft_version)
    mcmodInfoProperties.put("forgeversion", forge_version)
    // Keep as a template for the final jar builds
    mcmodInfoProperties.put("jmedition", "\${jm_edition}")
}

tasks.processResources {
    filesMatching("license.txt") {
        expand("version" to project.version.toString(), "mcversion" to minecraft_version, "date" to getDate())
    }
}

// -dev jar
tasks.jar {
    dependsOn(processDocs)
    archiveVersion = "${version}-${unlimited}".lowercase()
    exclude("journeymap/client/feature/impl/NoRadar.class")
    exclude("**/module-info.class")
    filesMatching("mcmod.info") {
        expand("jm_edition" to unlimited)
    }
    manifest {
         // since we enabled mixins and we want it to be a soft dep
         // we need to remove the mixins tweaker from the manifest
         // file that gets automatically added by the buildscript
         // otherwise the game will crash on startup with a
         // class not found error when mixins are not present
         attributes.remove("TweakClass")
    }
}

// Main artifact
tasks.reobfJar {
    archiveVersion = "${version}-${unlimited}".lowercase()
}

tasks.named<Jar>("shadowJar") {
    // The unlimited edition must not contain the NoRadar policy class (same as the original build script)
    exclude("journeymap/client/feature/impl/NoRadar.class")
    filesMatching("mcmod.info") {
        expand("jm_edition" to unlimited)
    }
}

tasks.sourcesJar {
    archiveVersion = "${version}-${unlimited}".lowercase()
}

// Additional fairPlay jar
val fairPlayJar by tasks.registering(Jar::class) {
    dependsOn(tasks.reobfJar)
    archiveVersion = "${version}-${fairPlay}".lowercase()
    from(tasks.reobfJar.map { zipTree(it.archiveFile.get().asFile) }) {
        exclude("META-INF/MANIFEST.MF")
        exclude("mcmod.info")
        exclude("**/module-info.class")
    }
    manifest.from(tasks.jar.get().manifest)
    // Exclude both policy classes so FeatureManager falls back to its built-in FairPlay policy
    // (all features disabled in multiplayer). Leaving NoRadar.class in would select NoRadar instead.
    exclude("journeymap/client/feature/impl/Unlimited.class")
    exclude("journeymap/client/feature/impl/NoRadar.class")
    from(tasks.processResources) {
        include("mcmod.info")
        expand("jm_edition" to fairPlay)
    }
}

tasks.assemble {
    dependsOn(fairPlayJar)
}

// Add fairPlay to publishing
publishing.publications.withType<MavenPublication> {
    this.artifact(fairPlayJar) {
        classifier = "fairPlay"
    }
}
