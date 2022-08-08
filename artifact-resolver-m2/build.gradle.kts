plugins {
    java
    `java-library`
}


dependencies {
    "implementation"("org.apache.maven:maven-core:2.2.1")
}

sourceSets {
    getByName("main").java.srcDirs("src")
}