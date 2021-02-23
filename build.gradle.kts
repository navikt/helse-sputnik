import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val junitJupiterVersion = "5.6.2"
val ktorVersion = "1.5.0"
val wireMockVersion = "2.27.2"

plugins {
    kotlin("jvm") version "1.4.30"
}

group = "no.nav.helse"

dependencies {
    implementation("com.github.navikt:rapids-and-rivers:1.5e3ca6a")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-jackson:$ktorVersion")

    testImplementation("org.junit.jupiter:junit-jupiter-api:$junitJupiterVersion")
    testImplementation("org.junit.jupiter:junit-jupiter-params:$junitJupiterVersion")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:$junitJupiterVersion")

    testImplementation("no.nav:kafka-embedded-env:2.4.0")
    testImplementation("org.awaitility:awaitility:4.0.3")
    testImplementation("com.github.tomakehurst:wiremock:$wireMockVersion") {
        exclude(group = "junit")
    }

    testImplementation("io.ktor:ktor-client-mock-jvm:$ktorVersion")
    testImplementation("io.mockk:mockk:1.10.0")
}

repositories {
    jcenter()
    maven("https://jitpack.io")
    maven("http://packages.confluent.io/maven/")
}

tasks {
    named<Jar>("jar") {
        archiveBaseName.set("app")

        manifest {
            attributes["Main-Class"] = "no.nav.helse.sputnik.AppKt"
            attributes["Class-Path"] = configurations.runtimeClasspath.get().joinToString(separator = " ") {
                it.name
            }
        }

        doLast {
            configurations.runtimeClasspath.get().forEach {
                val file = File("$buildDir/libs/${it.name}")
                if (!file.exists())
                    it.copyTo(file)
            }
        }
    }

    named<KotlinCompile>("compileKotlin") {
        kotlinOptions.jvmTarget = "15"
    }

    named<KotlinCompile>("compileTestKotlin") {
        kotlinOptions.jvmTarget = "15"
    }

    withType<Test> {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
    }
}
