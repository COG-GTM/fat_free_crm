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
    compileOnly("com.github.spotbugs:spotbugs-annotations:4.9.3")
    testCompileOnly("com.github.spotbugs:spotbugs-annotations:4.9.3")

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.yaml:snakeyaml")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
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

tasks.named<Test>("test") {
    systemProperty("spring.profiles.active", "test")
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
val contractTest by sourceSets.creating {
    compileClasspath = configurations.getByName("contractTestCompileClasspath")
    runtimeClasspath = output + compileClasspath + configurations.getByName("contractTestRuntimeClasspath")
}

configurations.named("testImplementation") {
    extendsFrom(configurations.getByName("contractTestImplementation"))
}

sourceSets.named("test") {
    compileClasspath += contractTest.output
    runtimeClasspath += contractTest.output
}

dependencies {
    add("contractTestImplementation", "com.fasterxml.jackson.core:jackson-databind")
    add("contractTestImplementation", "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")
    add("contractTestImplementation", "org.junit.jupiter:junit-jupiter")
    add("contractTestCompileOnly", "com.github.spotbugs:spotbugs-annotations:4.9.3")
    add("contractTestRuntimeOnly", "org.junit.platform:junit-platform-launcher")
    add("contractTestRuntimeOnly", "org.postgresql:postgresql")
}

fun contractSetting(environmentName: String, propertyName: String, projectName: String, defaultValue: String): Provider<String> =
    providers.environmentVariable(environmentName)
        .orElse(providers.systemProperty(propertyName))
        .orElse(providers.gradleProperty(projectName))
        .orElse(defaultValue)

tasks.register<Test>("contractTest") {
    group = "verification"
    description = "Runs the Rails/Spring API contract-diff cases."
    testClassesDirs = contractTest.output.classesDirs
    classpath = contractTest.runtimeClasspath
    systemProperty("contract.railsUrl", contractSetting("CONTRACT_RAILS_URL", "contract.railsUrl", "contractRailsUrl", "http://localhost:3000").get())
    systemProperty("contract.springUrl", contractSetting("CONTRACT_SPRING_URL", "contract.springUrl", "contractSpringUrl", "http://localhost:8080").get())
    systemProperty("contract.caseFilter", contractSetting("CONTRACT_CASE_FILTER", "contract.caseFilter", "contractCaseFilter", "").get())
    systemProperty(
        "contract.reportDir",
        contractSetting(
            "CONTRACT_REPORT_DIR",
            "contract.reportDir",
            "contractReportDir",
            layout.buildDirectory.dir("reports/contract-diff").get().asFile.absolutePath
        ).get()
    )
    outputs.upToDateWhen { false }
}

// AB-272: contract DB coordinates for reset:true / dbAssert cases in the contractTest suite.
// The 5+ minute dual-write soak is excluded here; it runs via ./gradlew dualWriteSoak.
tasks.named<Test>("contractTest") {
    filter { excludeTestsMatching("com.fatfreecrm.contract.DualWriteSoakTest") }
    systemProperty("contract.dbUrl", contractSetting("CONTRACT_DB_URL", "contract.dbUrl", "contractDbUrl", "jdbc:postgresql://127.0.0.1:5433/ffcrm_contract").get())
    systemProperty("contract.dbUser", contractSetting("CONTRACT_DB_USER", "contract.dbUser", "contractDbUser", "postgres").get())
    systemProperty("contract.dbPassword", contractSetting("CONTRACT_DB_PASSWORD", "contract.dbPassword", "contractDbPassword", "postgres").get())
    systemProperty("contract.fixturesSql", contractSetting("CONTRACT_FIXTURES_SQL", "contract.fixturesSql", "contractFixturesSql", layout.buildDirectory.file("contract-db/fixtures.sql").get().asFile.absolutePath).get())
}

// AB-272 Phase A: dual-write soak — concurrent Rails+Spring writes at the same rows.
tasks.register<Test>("dualWriteSoak") {
    group = "verification"
    description = "Drives concurrent Rails and Spring writes at the same rows and checks for 5xx, constraint violations, lost updates and version counts."
    testClassesDirs = contractTest.output.classesDirs
    classpath = contractTest.runtimeClasspath
    filter { includeTestsMatching("com.fatfreecrm.contract.DualWriteSoakTest") }
    systemProperty("contract.railsUrl", contractSetting("CONTRACT_RAILS_URL", "contract.railsUrl", "contractRailsUrl", "http://localhost:3000").get())
    systemProperty("contract.springUrl", contractSetting("CONTRACT_SPRING_URL", "contract.springUrl", "contractSpringUrl", "http://localhost:8080").get())
    systemProperty("contract.dbUrl", contractSetting("CONTRACT_DB_URL", "contract.dbUrl", "contractDbUrl", "jdbc:postgresql://127.0.0.1:5433/ffcrm_contract").get())
    systemProperty("contract.dbUser", contractSetting("CONTRACT_DB_USER", "contract.dbUser", "contractDbUser", "postgres").get())
    systemProperty("contract.dbPassword", contractSetting("CONTRACT_DB_PASSWORD", "contract.dbPassword", "contractDbPassword", "postgres").get())
    systemProperty("soak.minutes", contractSetting("SOAK_MINUTES", "soak.minutes", "soakMinutes", "5").get())
    systemProperty("soak.threads", contractSetting("SOAK_THREADS", "soak.threads", "soakThreads", "4").get())
    systemProperty("soak.commentSides", contractSetting("SOAK_COMMENT_SIDES", "soak.commentSides", "soakCommentSides", "both").get())
    outputs.upToDateWhen { false }
}
