plugins {
    id("java")
    id("org.jetbrains.intellij") version "1.8.0"
}

group = "org.jetbrains.idea.maven"
version = "1.0-SNAPSHOT"

val ideaVersion by rootProject.extra { "223-SNAPSHOT" }
val ideaType by rootProject.extra { "IC" }

repositories {
    mavenCentral()
    maven(url = "https://plugins.jetbrains.com/maven")
    maven(url = "https://www.jetbrains.com/intellij-repository/nightly")
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-gradle-intellij-plugin.html
intellij {
    version.set(ideaVersion)
    type.set(ideaType) // Target IDE Platform

    plugins.set(
        listOf(
            "org.jetbrains.idea.maven"
        )
    )
}

subprojects {
    repositories {
        mavenCentral()
        maven(url = "https://plugins.jetbrains.com/maven")
        maven(url = "https://cache-redirector.jetbrains.com/intellij-dependencies")
        maven(url = "https://www.jetbrains.com/intellij-repository/nightly")
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "1.8"
        targetCompatibility = "1.8"
    }

    buildPlugin {
        //dependsOn(project(":maven2-server-impl").tasks["jar"])
        //dependsOn(project(":artifact-resolver-m2").tasks["jar"])
        dependsOn(":maven2-server-impl:jar", ":artifact-resolver-m2:jar")
        from(File(project(":maven2-server-impl").buildDir, "libs")) {
            include("*.jar")
            into("server")
        }

        from(File(project(":maven2-server-impl").projectDir, "lib")) {
            include("*.jar")
            into("server")
        }

        from(File(project(":artifact-resolver-m2").buildDir, "libs")) {
            include("*.jar")
            into("server")
        }

        from(File(project(":maven2-server-impl").projectDir, "lib/maven2")) {
            into("bundled-maven-2")
        }


    }

    patchPluginXml {
        sinceBuild.set("221")
        untilBuild.set("223.*")
    }

    signPlugin {
        certificateChain.set(System.getenv("CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("PRIVATE_KEY"))
        password.set(System.getenv("PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("PUBLISH_TOKEN"))
    }
}
