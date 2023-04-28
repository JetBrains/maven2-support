plugins {
    java
    `java-library`
    id("org.jetbrains.intellij") version "1.13.3"
}

val ideaVersion = "${rootProject.extra.get("ideaVersion")}"
val ideaType = "${rootProject.extra.get("ideaType")}"

dependencies {
    "implementation"("com.jetbrains.intellij.platform:util:${ideaVersion}")
    "implementation"("commons-logging:commons-logging:1.2")
    "implementation"("org.apache.lucene:lucene-core:2.4.1")
    "implementation"(fileTree(baseDir = "lib/maven2/lib") { include("*.jar") })
    "implementation"(fileTree(baseDir = "lib/maven2/boot") { include("*.jar") })
    "implementation"(fileTree(baseDir = "lib/") { include("*.jar") })
}


intellij {
    version.set(ideaVersion)
    type.set(ideaType) // Target IDE Platform
    instrumentCode.set(false)

    plugins.set(
        listOf(
            "org.jetbrains.idea.maven.model",
            "org.jetbrains.idea.maven.server.api"
        )
    )
}


sourceSets {
    getByName("main").java.srcDirs("src")
}