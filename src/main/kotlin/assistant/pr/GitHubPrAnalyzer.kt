package assistant.pr

import assistant.Config
import assistant.OllamaStreamResponse
import assistant.github.*
import assistant.rag.DocumentStore
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Анализатор GitHub Pull Request с использованием RAG и Ollama.
 *
 * Выполняет:
 * - Получение информации о PR через GitHub API
 * - Индексация измененных файлов в RAG
 * - Анализ изменений с помощью Ollama LLM
 * - Генерация рекомендаций
 */
class GitHubPrAnalyzer(private val config: Config) {

    private val githubClient = GitHubClient(System.getenv("GITHUB_TOKEN"))
    private val documentStore = DocumentStore(config)

    private val httpClient = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 180_000  // 3 минуты для LLM
            connectTimeoutMillis = 10_000
        }
    }

    /**
     * Анализирует PR по URL.
     */
    suspend fun analyzePrByUrl(prUrl: String): PrAnalysisReport {
        val ref = githubClient.parsePrUrl(prUrl)
            ?: throw IllegalArgumentException("Invalid PR URL: $prUrl")

        return analyzePr(ref)
    }

    /**
     * Анализирует Pull Request.
     */
    suspend fun analyzePr(ref: PrReference): PrAnalysisReport {
        println("🔍 Анализируем PR: $ref")

        // 1. Получаем информацию о PR
        val prInfo = githubClient.getPullRequest(ref)
        println("📋 PR: ${prInfo.title}")
        println("   Автор: ${prInfo.user.login}")
        println("   Файлов изменено: ${prInfo.changed_files}")

        // 2. Получаем список файлов
        val files = githubClient.getPullRequestFiles(ref)
        println("📁 Получено ${files.size} файлов для анализа")

        // 3. Индексируем изменения в RAG для контекста
        println("📚 Индексация изменений в RAG...")
        indexPrChanges(files, ref)

        // 4. Анализируем с помощью LLM
        println("🤖 Анализ с помощью Ollama...")
        val llmAnalysis = analyzePrWithLlm(prInfo, files)

        val report = PrAnalysisReport(
            prNumber = prInfo.number,
            prTitle = prInfo.title,
            prUrl = prInfo.html_url,
            author = prInfo.user.login,
            baseBranch = prInfo.base.ref,
            headBranch = prInfo.head.ref,
            filesChanged = files.map { it.filename },
            totalAdditions = prInfo.additions,
            totalDeletions = prInfo.deletions,
            issues = llmAnalysis.issues,
            suggestions = llmAnalysis.suggestions,
            llmSummary = llmAnalysis.summary
        )

        println("✅ Анализ завершен: найдено ${llmAnalysis.issues.size} проблем, ${llmAnalysis.suggestions.size} рекомендаций")

        return report
    }

    /**
     * Индексирует изменения PR в RAG store для контекстного поиска.
     */
    private suspend fun indexPrChanges(files: List<PrFile>, ref: PrReference) {
        documentStore.clear()

        for (file in files) {
            // Индексируем patch (diff) файла
            file.patch?.let { patch ->
                documentStore.add(
                    content = "File: ${file.filename}\nStatus: ${file.status}\nChanges:\n$patch",
                    source = "pr:${ref}:${file.filename}",
                    metadata = mapOf(
                        "type" to "pr_diff",
                        "file" to file.filename,
                        "status" to file.status
                    )
                )
            }
        }

        // Загружаем также существующий индекс документации если есть
        val indexFile = File(config.indexPath)
        if (indexFile.exists()) {
            try {
                documentStore.load()
            } catch (e: Exception) {
                println("⚠️ Не удалось загрузить индекс документации: ${e.message}")
            }
        }
    }

    /**
     * Анализирует PR с помощью Ollama LLM.
     */
    private suspend fun analyzePrWithLlm(prInfo: PullRequestInfo, files: List<PrFile>): LlmAnalysisResult {
        // Готовим контекст для LLM
        val filesContext = files.take(15).joinToString("\n\n") { file ->
            val patch = file.patch?.take(3000) ?: "No patch available"
            """
            === ${file.filename} ===
            Status: ${file.status}
            Changes: +${file.additions}/-${file.deletions}

            $patch
            """.trimIndent()
        }

        val prompt = buildString {
            appendLine("Ты - опытный code reviewer. Проанализируй следующий Pull Request и дай детальные рекомендации.")
            appendLine()
            appendLine("=== PR INFO ===")
            appendLine("Title: ${prInfo.title}")
            appendLine("Description: ${prInfo.body ?: "No description"}")
            appendLine("Author: ${prInfo.user.login}")
            appendLine("Files changed: ${prInfo.changed_files}")
            appendLine("Additions: ${prInfo.additions}, Deletions: ${prInfo.deletions}")
            appendLine()
            appendLine("=== CHANGED FILES ===")
            appendLine(filesContext)
            appendLine()
            appendLine("=== ЗАДАНИЕ ===")
            appendLine("Проанализируй изменения и предоставь структурированный ответ:")
            appendLine()
            appendLine("## SUMMARY")
            appendLine("Краткое описание что делает этот PR (2-3 предложения)")
            appendLine()
            appendLine("## ISSUES")
            appendLine("Список найденных проблем. Для каждой проблемы укажи:")
            appendLine("- Серьезность: CRITICAL / HIGH / MEDIUM / LOW")
            appendLine("- Файл и строку (если применимо)")
            appendLine("- Описание проблемы")
            appendLine("- Как исправить")
            appendLine()
            appendLine("Ищи: баги, уязвимости безопасности (SQL injection, XSS, secrets в коде), утечки памяти, race conditions, неправильная обработка ошибок")
            appendLine()
            appendLine("## SUGGESTIONS")
            appendLine("Рекомендации по улучшению кода:")
            appendLine("- Улучшения архитектуры")
            appendLine("- Оптимизации производительности")
            appendLine("- Улучшения читаемости")
            appendLine("- Отсутствующие тесты")
            appendLine("- Улучшения документации")
            appendLine()
            appendLine("Ответь на русском языке. Будь конкретен, указывай файлы и строки.")
        }

        return try {
            // Используем JSON body с options для отключения thinking mode
            val jsonEncoder = Json { encodeDefaults = true }
            val escapedPrompt = jsonEncoder.encodeToString(kotlinx.serialization.serializer<String>(), prompt)
            val requestBody = """{"model":"${config.llmModel}","prompt":$escapedPrompt,"stream":false,"options":{"num_predict":2048}}"""

            val response = httpClient.post("${config.llmApiUrl}/api/generate") {
                contentType(ContentType.Application.Json)
                setBody(requestBody)
            }

            // Читаем ответ как строку
            val responseText = response.body<String>()
            println("📝 Ollama response length: ${responseText.length}")

            // Парсим JSON - берем последний объект с done:true
            val json = Json { ignoreUnknownKeys = true }
            val lines = responseText.trim().split("\n").filter { it.startsWith("{") }

            val fullResponse = StringBuilder()
            for (line in lines) {
                try {
                    val obj = json.decodeFromString<OllamaStreamResponse>(line)
                    if (!obj.response.isNullOrEmpty()) {
                        fullResponse.append(obj.response)
                    }
                } catch (e: Exception) {
                    // Игнорируем невалидные строки
                }
            }

            val resultText = fullResponse.toString()
            println("📝 Parsed response length: ${resultText.length}")

            if (resultText.isBlank()) {
                throw Exception("Empty response from Ollama")
            }

            parseLlmResponse(resultText)
        } catch (e: Exception) {
            println("⚠️ Ollama недоступен: ${e.message}")
            e.printStackTrace()
            LlmAnalysisResult(
                summary = "⚠️ Автоматический анализ с помощью LLM недоступен.\n\nУбедитесь что:\n1. Ollama запущен: ollama serve\n2. Модель загружена: ollama pull ${config.llmModel}\n\nОшибка: ${e.message}",
                issues = emptyList(),
                suggestions = emptyList()
            )
        }
    }

    /**
     * Парсит ответ LLM и извлекает структурированные данные.
     */
    private fun parseLlmResponse(response: String): LlmAnalysisResult {
        val issues = mutableListOf<PrIssue>()
        val suggestions = mutableListOf<PrSuggestion>()
        val summaryBuilder = StringBuilder()

        val lines = response.lines()
        var currentSection = ""
        var currentIssueText = StringBuilder()
        var currentSuggestionText = StringBuilder()

        for (line in lines) {
            val trimmedLine = line.trim()

            // Определяем секцию
            when {
                trimmedLine.contains("## SUMMARY", ignoreCase = true) ||
                trimmedLine.contains("## Summary", ignoreCase = true) ||
                trimmedLine.contains("# SUMMARY", ignoreCase = true) -> {
                    currentSection = "summary"
                    continue
                }
                trimmedLine.contains("## ISSUES", ignoreCase = true) ||
                trimmedLine.contains("## Issues", ignoreCase = true) ||
                trimmedLine.contains("# ISSUES", ignoreCase = true) ||
                trimmedLine.contains("## Проблемы", ignoreCase = true) ||
                trimmedLine.contains("## ПРОБЛЕМЫ", ignoreCase = true) -> {
                    currentSection = "issues"
                    continue
                }
                trimmedLine.contains("## SUGGESTIONS", ignoreCase = true) ||
                trimmedLine.contains("## Suggestions", ignoreCase = true) ||
                trimmedLine.contains("# SUGGESTIONS", ignoreCase = true) ||
                trimmedLine.contains("## Рекомендации", ignoreCase = true) ||
                trimmedLine.contains("## РЕКОМЕНДАЦИИ", ignoreCase = true) -> {
                    // Сохраняем последний issue если есть
                    if (currentIssueText.isNotBlank()) {
                        parseIssueText(currentIssueText.toString())?.let { issues.add(it) }
                        currentIssueText = StringBuilder()
                    }
                    currentSection = "suggestions"
                    continue
                }
            }

            // Обрабатываем содержимое секции
            when (currentSection) {
                "summary" -> {
                    if (trimmedLine.isNotBlank() && !trimmedLine.startsWith("#")) {
                        summaryBuilder.appendLine(trimmedLine)
                    }
                }
                "issues" -> {
                    if (trimmedLine.startsWith("-") || trimmedLine.startsWith("*") || trimmedLine.matches(Regex("^\\d+\\..*"))) {
                        // Новый issue - сохраняем предыдущий
                        if (currentIssueText.isNotBlank()) {
                            parseIssueText(currentIssueText.toString())?.let { issues.add(it) }
                        }
                        currentIssueText = StringBuilder(trimmedLine.removePrefix("-").removePrefix("*").replace(Regex("^\\d+\\.\\s*"), "").trim())
                    } else if (trimmedLine.isNotBlank() && currentIssueText.isNotBlank()) {
                        currentIssueText.append(" ").append(trimmedLine)
                    }
                }
                "suggestions" -> {
                    if (trimmedLine.startsWith("-") || trimmedLine.startsWith("*") || trimmedLine.matches(Regex("^\\d+\\..*"))) {
                        // Новая рекомендация - сохраняем предыдущую
                        if (currentSuggestionText.isNotBlank()) {
                            parseSuggestionText(currentSuggestionText.toString())?.let { suggestions.add(it) }
                        }
                        currentSuggestionText = StringBuilder(trimmedLine.removePrefix("-").removePrefix("*").replace(Regex("^\\d+\\.\\s*"), "").trim())
                    } else if (trimmedLine.isNotBlank() && currentSuggestionText.isNotBlank()) {
                        currentSuggestionText.append(" ").append(trimmedLine)
                    }
                }
            }
        }

        // Сохраняем последние элементы
        if (currentIssueText.isNotBlank()) {
            parseIssueText(currentIssueText.toString())?.let { issues.add(it) }
        }
        if (currentSuggestionText.isNotBlank()) {
            parseSuggestionText(currentSuggestionText.toString())?.let { suggestions.add(it) }
        }

        // Если не удалось распарсить структурированно, используем весь ответ
        val summary = if (summaryBuilder.isNotBlank()) {
            summaryBuilder.toString().trim()
        } else {
            response.take(1000)
        }

        return LlmAnalysisResult(
            summary = summary,
            issues = issues,
            suggestions = suggestions
        )
    }

    /**
     * Парсит текст проблемы и определяет серьезность.
     */
    private fun parseIssueText(text: String): PrIssue? {
        if (text.isBlank()) return null

        // Определяем серьезность по ключевым словам
        val severity = when {
            text.contains("CRITICAL", ignoreCase = true) ||
            text.contains("критич", ignoreCase = true) ||
            text.contains("security", ignoreCase = true) ||
            text.contains("безопасност", ignoreCase = true) ||
            text.contains("injection", ignoreCase = true) ||
            text.contains("XSS", ignoreCase = true) ||
            text.contains("password", ignoreCase = true) ||
            text.contains("пароль", ignoreCase = true) ||
            text.contains("secret", ignoreCase = true) -> PrIssueSeverity.CRITICAL

            text.contains("HIGH", ignoreCase = true) ||
            text.contains("высок", ignoreCase = true) ||
            text.contains("важн", ignoreCase = true) ||
            text.contains("ошибк", ignoreCase = true) ||
            text.contains("bug", ignoreCase = true) ||
            text.contains("баг", ignoreCase = true) -> PrIssueSeverity.HIGH

            text.contains("MEDIUM", ignoreCase = true) ||
            text.contains("средн", ignoreCase = true) -> PrIssueSeverity.MEDIUM

            else -> PrIssueSeverity.LOW
        }

        // Пытаемся извлечь файл
        val fileMatch = Regex("""[\w/.-]+\.(kt|java|js|ts|py|go|rs|cpp|c|h|swift|rb|php|scala|clj)""").find(text)
        val file = fileMatch?.value ?: "general"

        // Пытаемся извлечь номер строки
        val lineMatch = Regex(""":(\d+)""").find(text)
        val line = lineMatch?.groupValues?.get(1)?.toIntOrNull()

        return PrIssue(
            severity = severity,
            category = PrIssueCategory.LLM_DETECTED,
            file = file,
            line = line,
            message = text.replace(Regex("(CRITICAL|HIGH|MEDIUM|LOW)\\s*:?", RegexOption.IGNORE_CASE), "").trim(),
            code = null
        )
    }

    /**
     * Парсит текст рекомендации.
     */
    private fun parseSuggestionText(text: String): PrSuggestion? {
        if (text.isBlank()) return null

        // Пытаемся извлечь файл
        val fileMatch = Regex("""[\w/.-]+\.(kt|java|js|ts|py|go|rs|cpp|c|h|swift|rb|php|scala|clj)""").find(text)
        val file = fileMatch?.value ?: "general"

        return PrSuggestion(
            category = PrSuggestionCategory.LLM_SUGGESTION,
            file = file,
            line = null,
            message = text,
            code = null
        )
    }

    /**
     * Получает релевантный контекст из RAG для запроса.
     */
    suspend fun getRelevantContext(query: String, topK: Int = 3): String {
        val results = documentStore.search(query, topK)
        return if (results.isNotEmpty()) {
            results.joinToString("\n\n---\n\n") { result ->
                "Source: ${result.document.source}\n${result.document.content}"
            }
        } else {
            ""
        }
    }

    fun close() {
        githubClient.close()
        documentStore.close()
        httpClient.close()
    }
}

private data class LlmAnalysisResult(
    val summary: String,
    val issues: List<PrIssue>,
    val suggestions: List<PrSuggestion>
)

/**
 * Полный отчет анализа PR.
 */
@Serializable
data class PrAnalysisReport(
    val prNumber: Int,
    val prTitle: String,
    val prUrl: String,
    val author: String,
    val baseBranch: String,
    val headBranch: String,
    val filesChanged: List<String>,
    val totalAdditions: Int,
    val totalDeletions: Int,
    val issues: List<PrIssue>,
    val suggestions: List<PrSuggestion>,
    val llmSummary: String
) {
    /**
     * Форматирует отчет в Markdown.
     */
    fun toMarkdown(): String = buildString {
        appendLine("# 🔍 Анализ Pull Request #$prNumber")
        appendLine()
        appendLine("**$prTitle**")
        appendLine()
        appendLine("| Параметр | Значение |")
        appendLine("|----------|----------|")
        appendLine("| Автор | $author |")
        appendLine("| Ветки | `$headBranch` → `$baseBranch` |")
        appendLine("| Файлов изменено | ${filesChanged.size} |")
        appendLine("| Строк добавлено | +$totalAdditions |")
        appendLine("| Строк удалено | -$totalDeletions |")
        appendLine()

        appendLine("## 📝 Summary")
        appendLine()
        appendLine(llmSummary)
        appendLine()

        if (issues.isNotEmpty()) {
            appendLine("## ⚠️ Найденные проблемы (${issues.size})")
            appendLine()

            val critical = issues.filter { it.severity == PrIssueSeverity.CRITICAL }
            val high = issues.filter { it.severity == PrIssueSeverity.HIGH }
            val medium = issues.filter { it.severity == PrIssueSeverity.MEDIUM }
            val low = issues.filter { it.severity == PrIssueSeverity.LOW }

            if (critical.isNotEmpty()) {
                appendLine("### 🔴 Критические (${critical.size})")
                for (issue in critical) {
                    appendLine()
                    appendLine("**${issue.file}${issue.line?.let { ":$it" } ?: ""}**")
                    appendLine("> ${issue.message}")
                }
            }

            if (high.isNotEmpty()) {
                appendLine("### 🟠 Высокий приоритет (${high.size})")
                for (issue in high) {
                    appendLine()
                    appendLine("**${issue.file}${issue.line?.let { ":$it" } ?: ""}**")
                    appendLine("> ${issue.message}")
                }
            }

            if (medium.isNotEmpty()) {
                appendLine("### 🟡 Средний приоритет (${medium.size})")
                for (issue in medium) {
                    appendLine()
                    appendLine("**${issue.file}${issue.line?.let { ":$it" } ?: ""}**")
                    appendLine("> ${issue.message}")
                }
            }

            if (low.isNotEmpty()) {
                appendLine("### 🟢 Низкий приоритет (${low.size})")
                for (issue in low) {
                    appendLine()
                    appendLine("**${issue.file}${issue.line?.let { ":$it" } ?: ""}**")
                    appendLine("> ${issue.message}")
                }
            }
        } else {
            appendLine("## ✅ Проблемы не найдены")
            appendLine()
            appendLine("LLM анализ не выявил критических проблем.")
        }

        appendLine()

        if (suggestions.isNotEmpty()) {
            appendLine("## 💡 Рекомендации (${suggestions.size})")
            appendLine()

            for (suggestion in suggestions) {
                appendLine("- **${suggestion.file}**: ${suggestion.message}")
            }
        }

        appendLine()
        appendLine("---")
        appendLine("🤖 *Анализ выполнен DevAssistant с использованием RAG и Ollama*")
    }

    /**
     * Форматирует краткий отчет для UI.
     */
    fun toShortSummary(): String = buildString {
        appendLine("PR #$prNumber: $prTitle")
        appendLine("Автор: $author | Файлов: ${filesChanged.size} | +$totalAdditions/-$totalDeletions")
        appendLine()

        val critical = issues.count { it.severity == PrIssueSeverity.CRITICAL }
        val high = issues.count { it.severity == PrIssueSeverity.HIGH }
        val medium = issues.count { it.severity == PrIssueSeverity.MEDIUM }

        if (critical > 0 || high > 0) {
            appendLine("⚠️ Найдены проблемы:")
            if (critical > 0) appendLine("  🔴 Критических: $critical")
            if (high > 0) appendLine("  🟠 Высоких: $high")
            if (medium > 0) appendLine("  🟡 Средних: $medium")
        } else if (issues.isEmpty()) {
            appendLine("✅ Проблем не найдено")
        }

        if (suggestions.isNotEmpty()) {
            appendLine("💡 Рекомендаций: ${suggestions.size}")
        }
    }
}

/**
 * Проблема в PR.
 */
@Serializable
data class PrIssue(
    val severity: PrIssueSeverity,
    val category: PrIssueCategory,
    val file: String,
    val line: Int?,
    val message: String,
    val code: String?
)

@Serializable
enum class PrIssueSeverity {
    CRITICAL, HIGH, MEDIUM, LOW
}

@Serializable
enum class PrIssueCategory(val displayName: String) {
    LLM_DETECTED("AI анализ"),
    OTHER("Другое")
}

/**
 * Рекомендация по PR.
 */
@Serializable
data class PrSuggestion(
    val category: PrSuggestionCategory,
    val file: String,
    val line: Int?,
    val message: String,
    val code: String?
)

@Serializable
enum class PrSuggestionCategory {
    LLM_SUGGESTION, OTHER
}
