plugins {
    kotlin("jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api("org.jsoup:jsoup:1.21.2")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    // Fourni par Android; exclu côté app.
    implementation("org.json:json:20250517")
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
