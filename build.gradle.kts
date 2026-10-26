import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    kotlin("jvm") version "2.3.20"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "com.romaevt"
version = "1.0.0"

val counterProfileRuntime by configurations.creating

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    // The standalone diagnostic has no IDE to supply Kotlin; this is not packaged.
    counterProfileRuntime(kotlin("stdlib"))
    implementation("org.commonmark:commonmark:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.30.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.30.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.30.0")

    intellijPlatform {
        val idePath = providers.gradleProperty("idePath").orNull
        if (idePath != null) {
            local(idePath)
        } else {
            // This supplies the shared platform SDK, not a product restriction.
            intellijIdea("2026.1")
        }
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.opentest4j:opentest4j:1.3.0")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.withType<KotlinJvmCompile>().configureEach {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks.jar {
    from("LICENSE") { into("META-INF") }
}

intellijPlatform {
    // Preferences will live in the widget popup, not an IDE settings page.
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "261"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdea, "2026.1")
            create(IntelliJPlatformType.WebStorm, "2026.1.4")
            create(IntelliJPlatformType.GoLand, "2026.1.4")
        }
    }
}

tasks.register<JavaExec>("profileCounter") {
    group = "verification"
    description = "Profile the counting engine without starting an IDE."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath + counterProfileRuntime
    mainClass = "com.romaevt.wordcounter.CounterProfile"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(25) }
    val reportDirectory = layout.buildDirectory.dir("reports/counter-profile")
    args(reportDirectory.get().asFile.absolutePath)
    providers.gradleProperty("profileFile").orNull?.let { args(file(it).absolutePath) }
    jvmArgs("-Xms256m", "-Xmx512m",
        "-XX:StartFlightRecording=filename=${reportDirectory.get().asFile}/counter.jfr,settings=profile,dumponexit=true")
    doFirst { reportDirectory.get().asFile.mkdirs() }
}
