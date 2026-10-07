import java.nio.file.Files

plugins {
    java
    checkstyle
    id("org.springframework.boot") version "3.5.16"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.github.spotbugs") version "6.5.12"
}

group = "com.fatfreecrm"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")
    runtimeOnly("org.postgresql:postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
}

springBoot {
    buildInfo()
}

checkstyle {
    toolVersion = "10.26.1"
    configFile = file("config/checkstyle/checkstyle.xml")
    isIgnoreFailures = false
    maxWarnings = 0
}

spotbugs {
    toolVersion.set("4.9.3")
    excludeFilter.set(file("config/spotbugs/exclude.xml"))
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "passed")
    }
}

tasks.named("spotbugsTest") {
    enabled = false
}

val frozenContract = layout.projectDirectory.file("../docs/migration/openapi.yaml")
val packagedContract = layout.projectDirectory.file("src/main/resources/static/openapi.yaml")

tasks.register("verifyFrozenOpenApi") {
    group = "verification"
    description = "Checks that the packaged OpenAPI contract matches the frozen migration contract."
    inputs.files(frozenContract, packagedContract)
    doLast {
        val source = frozenContract.asFile.toPath()
        val packaged = packagedContract.asFile.toPath()
        if (!Files.exists(packaged) || Files.mismatch(source, packaged) != -1L) {
            throw GradleException(
                "The packaged OpenAPI contract differs from ../docs/migration/openapi.yaml; " +
                    "copy the frozen file to src/main/resources/static/openapi.yaml."
            )
        }
    }
}

tasks.named("check") {
    dependsOn("verifyFrozenOpenApi")
}
