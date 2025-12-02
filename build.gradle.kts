plugins {
    kotlin("jvm") version "1.9.21"
    kotlin("plugin.serialization") version "1.9.21"
    application
}

group = "dev.assistant"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    
    // HTTP клиент для LLM API
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")

    // Web Server
    implementation("io.ktor:ktor-server-core:2.3.7")
    implementation("io.ktor:ktor-server-netty:2.3.7")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-server-cors:2.3.7")
    implementation("io.ktor:ktor-server-html-builder:2.3.7")
    implementation("ch.qos.logback:logback-classic:1.4.14")
    
    // Тестирование
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("assistant.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(17)
}

// Задача для запуска MCP сервера
tasks.register<JavaExec>("runMcp") {
    group = "application"
    mainClass.set("assistant.mcp.GitMcpServerKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}

// Задача для индексации документов
tasks.register<JavaExec>("indexDocs") {
    group = "application"
    mainClass.set("assistant.rag.IndexerKt")
    classpath = sourceSets["main"].runtimeClasspath
    args = listOf("project")
}

// Задача для запуска веб-сервера
tasks.register<JavaExec>("runWeb") {
    group = "application"
    mainClass.set("assistant.web.WebServerKt")
    classpath = sourceSets["main"].runtimeClasspath
}

// Задача для анализа PR (локального)
tasks.register<JavaExec>("analyzePr") {
    group = "application"
    mainClass.set("assistant.pr.PrAnalyzerCliKt")
    classpath = sourceSets["main"].runtimeClasspath

    // Передаем системные свойства как аргументы
    if (project.hasProperty("pr")) {
        args = listOf("-Ppr=${project.property("pr")}")
    }
    if (project.hasProperty("base")) {
        args = (args ?: emptyList()) + "-Pbase=${project.property("base")}"
    }
    if (project.hasProperty("head")) {
        args = (args ?: emptyList()) + "-Phead=${project.property("head")}"
    }
    if (project.hasProperty("format")) {
        args = (args ?: emptyList()) + "-Pformat=${project.property("format")}"
    }
    if (project.hasProperty("output")) {
        args = (args ?: emptyList()) + "-Poutput=${project.property("output")}"
    }
}

// Задача для запуска GitHub MCP сервера
tasks.register<JavaExec>("runGitHubMcp") {
    group = "application"
    mainClass.set("assistant.mcp.GitHubMcpServerKt")
    classpath = sourceSets["main"].runtimeClasspath
    standardInput = System.`in`
}
