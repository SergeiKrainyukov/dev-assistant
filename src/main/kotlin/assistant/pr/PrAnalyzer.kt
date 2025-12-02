package assistant.pr

import assistant.Config
import assistant.rag.DocumentStore
import kotlinx.serialization.Serializable
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Анализатор Pull Request с использованием RAG и MCP.
 *
 * Выполняет:
 * - Получение diff из PR
 * - Анализ изменений с учетом контекста документации
 * - Поиск потенциальных проблем и багов
 * - Генерация рекомендаций
 */
class PrAnalyzer(private val config: Config) {

    private val documentStore = DocumentStore(config)

    /**
     * Инициализирует анализатор, загружая индекс документации.
     */
    suspend fun initialize() {
        documentStore.load()
        println("📚 Загружено ${documentStore.size()} документов для контекста")
    }

    /**
     * Анализирует Pull Request.
     *
     * @param prNumber номер PR (опционально, для локального анализа можно пропустить)
     * @param baseBranch базовая ветка (по умолчанию main)
     * @param headBranch сравниваемая ветка (по умолчанию текущая)
     * @return результат анализа
     */
    suspend fun analyzePr(
        prNumber: Int? = null,
        baseBranch: String = "main",
        headBranch: String? = null
    ): PrAnalysisResult {
        println("🔍 Начинаем анализ PR...")

        // Получаем diff
        val diff = if (prNumber != null) {
            getPrDiff(prNumber)
        } else {
            val head = headBranch ?: getCurrentBranch()
            getLocalDiff(baseBranch, head)
        }

        if (diff.isEmpty()) {
            return PrAnalysisResult(
                summary = "Нет изменений для анализа",
                issues = emptyList(),
                suggestions = emptyList(),
                filesChanged = emptyList()
            )
        }

        // Парсим diff
        val changedFiles = parseDiff(diff)
        println("📝 Найдено ${changedFiles.size} измененных файлов")

        // Анализируем каждый файл
        val allIssues = mutableListOf<Issue>()
        val allSuggestions = mutableListOf<Suggestion>()

        for (file in changedFiles) {
            println("  → Анализируем ${file.path}")

            // Получаем контекст из документации
            val context = getRelevantContext(file)

            // Анализируем изменения
            val analysis = analyzeFileChanges(file, context)
            allIssues.addAll(analysis.issues)
            allSuggestions.addAll(analysis.suggestions)
        }

        // Генерируем общий summary
        val summary = generateSummary(changedFiles, allIssues, allSuggestions)

        return PrAnalysisResult(
            summary = summary,
            issues = allIssues,
            suggestions = allSuggestions,
            filesChanged = changedFiles.map { it.path }
        )
    }

    /**
     * Получает diff для PR через GitHub API или gh CLI.
     */
    private fun getPrDiff(prNumber: Int): String {
        return try {
            // Используем gh CLI для получения diff
            val process = ProcessBuilder("gh", "pr", "diff", prNumber.toString())
                .redirectErrorStream(true)
                .start()

            val diff = process.inputStream.bufferedReader().readText()
            process.waitFor()
            diff
        } catch (e: Exception) {
            println("⚠️ Не удалось получить diff через gh CLI: ${e.message}")
            ""
        }
    }

    /**
     * Получает diff между двумя ветками локально.
     */
    private fun getLocalDiff(baseBranch: String, headBranch: String): String {
        return try {
            val process = ProcessBuilder("git", "diff", "$baseBranch...$headBranch")
                .redirectErrorStream(true)
                .start()

            val diff = process.inputStream.bufferedReader().readText()
            process.waitFor()
            diff
        } catch (e: Exception) {
            println("⚠️ Ошибка получения diff: ${e.message}")
            ""
        }
    }

    /**
     * Получает текущую ветку.
     */
    private fun getCurrentBranch(): String {
        return try {
            val process = ProcessBuilder("git", "branch", "--show-current")
                .redirectErrorStream(true)
                .start()

            val branch = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            branch
        } catch (e: Exception) {
            "HEAD"
        }
    }

    /**
     * Парсит diff и возвращает список измененных файлов.
     */
    private fun parseDiff(diff: String): List<ChangedFile> {
        val files = mutableListOf<ChangedFile>()
        val lines = diff.lines()

        var currentFile: String? = null
        val currentChanges = mutableListOf<String>()

        for (line in lines) {
            when {
                line.startsWith("diff --git") -> {
                    // Сохраняем предыдущий файл
                    if (currentFile != null && currentChanges.isNotEmpty()) {
                        files.add(ChangedFile(currentFile, currentChanges.toList()))
                    }
                    // Начинаем новый файл
                    currentFile = line.substringAfter("b/").trim()
                    currentChanges.clear()
                }
                line.startsWith("+") && !line.startsWith("+++") -> {
                    currentChanges.add(line)
                }
                line.startsWith("-") && !line.startsWith("---") -> {
                    currentChanges.add(line)
                }
            }
        }

        // Добавляем последний файл
        if (currentFile != null && currentChanges.isNotEmpty()) {
            files.add(ChangedFile(currentFile, currentChanges.toList()))
        }

        return files
    }

    /**
     * Получает релевантный контекст из документации для файла.
     */
    private suspend fun getRelevantContext(file: ChangedFile): String {
        // Создаем запрос на основе имени файла и изменений
        val query = buildString {
            append("Файл: ${file.path}\n")
            append("Изменения:\n")
            append(file.changes.take(10).joinToString("\n"))
        }

        val results = documentStore.search(query, topK = 3)

        return if (results.isNotEmpty()) {
            results.joinToString("\n\n") {
                "Источник: ${it.document.source}\n${it.document.content}"
            }
        } else {
            "Контекст не найден"
        }
    }

    /**
     * Анализирует изменения в файле с учетом контекста.
     */
    private fun analyzeFileChanges(file: ChangedFile, context: String): FileAnalysis {
        val issues = mutableListOf<Issue>()
        val suggestions = mutableListOf<Suggestion>()

        // Проверка 1: Потенциальные уязвимости безопасности
        val securityPatterns = mapOf(
            "eval(" to "Использование eval() может привести к выполнению произвольного кода",
            "exec(" to "Использование exec() может быть небезопасным",
            "Runtime.getRuntime()" to "Прямое использование Runtime может быть опасным",
            ".innerHTML" to "Использование innerHTML может привести к XSS",
            "dangerouslySetInnerHTML" to "Потенциальная XSS уязвимость",
            "SQL.*WHERE.*\\+" to "Возможная SQL injection уязвимость",
            "password.*=.*\"" to "Пароль в открытом виде в коде",
            "api_key.*=.*\"" to "API ключ в открытом виде в коде"
        )

        for ((pattern, message) in securityPatterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            for ((index, change) in file.changes.withIndex()) {
                if (change.startsWith("+") && regex.containsMatchIn(change)) {
                    issues.add(Issue(
                        severity = IssueSeverity.HIGH,
                        category = IssueCategory.SECURITY,
                        file = file.path,
                        line = index + 1,
                        message = message,
                        code = change.trim()
                    ))
                }
            }
        }

        // Проверка 2: Потенциальные баги
        val bugPatterns = mapOf(
            "if.*=.*" to "Возможно, вы имели в виду == вместо =",
            "for.*in.*range\\(0,\\s*len" to "Можно упростить используя enumerate()",
            "\\.equals\\(null\\)" to "Сравнение с null через equals может вызвать NPE",
            "catch.*\\{\\s*\\}" to "Пустой catch блок скрывает ошибки",
            "Thread\\.sleep" to "Использование Thread.sleep может указывать на проблемы с синхронизацией"
        )

        for ((pattern, message) in bugPatterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            for ((index, change) in file.changes.withIndex()) {
                if (change.startsWith("+") && regex.containsMatchIn(change)) {
                    issues.add(Issue(
                        severity = IssueSeverity.MEDIUM,
                        category = IssueCategory.BUG,
                        file = file.path,
                        line = index + 1,
                        message = message,
                        code = change.trim()
                    ))
                }
            }
        }

        // Проверка 3: Стиль кода и лучшие практики
        val stylePatterns = listOf(
            "TODO" to "Есть незавершенные TODO комментарии",
            "FIXME" to "Есть незавершенные FIXME комментарии",
            "console\\.log" to "Остались отладочные console.log",
            "println" to "Остались отладочные println",
            "debugger" to "Остался debugger statement"
        )

        for ((pattern, message) in stylePatterns) {
            val regex = Regex(pattern, RegexOption.IGNORE_CASE)
            for ((index, change) in file.changes.withIndex()) {
                if (change.startsWith("+") && regex.containsMatchIn(change)) {
                    suggestions.add(Suggestion(
                        category = SuggestionCategory.STYLE,
                        file = file.path,
                        line = index + 1,
                        message = message,
                        code = change.trim()
                    ))
                }
            }
        }

        // Проверка 4: Отсутствие документации
        if (file.path.endsWith(".kt") || file.path.endsWith(".java")) {
            var hasPublicFunction = false
            var hasDocumentation = false

            for (change in file.changes) {
                if (change.contains(Regex("(public|internal)\\s+(suspend\\s+)?fun"))) {
                    hasPublicFunction = true
                }
                if (change.contains("/**")) {
                    hasDocumentation = true
                }
            }

            if (hasPublicFunction && !hasDocumentation) {
                suggestions.add(Suggestion(
                    category = SuggestionCategory.DOCUMENTATION,
                    file = file.path,
                    line = null,
                    message = "Публичные функции должны иметь KDoc документацию",
                    code = null
                ))
            }
        }

        // Проверка 5: Большие изменения
        if (file.changes.size > 50) {
            suggestions.add(Suggestion(
                category = SuggestionCategory.REFACTORING,
                file = file.path,
                line = null,
                message = "Файл содержит много изменений (${file.changes.size} строк). Рассмотрите возможность разбить изменения на несколько PR.",
                code = null
            ))
        }

        return FileAnalysis(issues, suggestions)
    }

    /**
     * Генерирует общий summary анализа.
     */
    private fun generateSummary(
        files: List<ChangedFile>,
        issues: List<Issue>,
        suggestions: List<Suggestion>
    ): String {
        val totalChanges = files.sumOf { it.changes.size }
        val highIssues = issues.count { it.severity == IssueSeverity.HIGH }
        val mediumIssues = issues.count { it.severity == IssueSeverity.MEDIUM }
        val lowIssues = issues.count { it.severity == IssueSeverity.LOW }

        return buildString {
            appendLine("## Анализ Pull Request")
            appendLine()
            appendLine("### Общая статистика")
            appendLine("- Измененных файлов: ${files.size}")
            appendLine("- Всего изменений: $totalChanges строк")
            appendLine()
            appendLine("### Найденные проблемы")
            if (issues.isEmpty()) {
                appendLine("✅ Критических проблем не обнаружено")
            } else {
                if (highIssues > 0) appendLine("- 🔴 Высокий приоритет: $highIssues")
                if (mediumIssues > 0) appendLine("- 🟡 Средний приоритет: $mediumIssues")
                if (lowIssues > 0) appendLine("- 🟢 Низкий приоритет: $lowIssues")
            }
            appendLine()
            appendLine("### Рекомендации")
            appendLine("- Всего рекомендаций: ${suggestions.size}")

            if (highIssues > 0) {
                appendLine()
                appendLine("⚠️ Обнаружены проблемы высокого приоритета. Рекомендуется исправить перед мержем.")
            }
        }
    }

    fun close() {
        documentStore.close()
    }
}

/**
 * Измененный файл с diff.
 */
data class ChangedFile(
    val path: String,
    val changes: List<String>
)

/**
 * Результат анализа файла.
 */
data class FileAnalysis(
    val issues: List<Issue>,
    val suggestions: List<Suggestion>
)

/**
 * Результат анализа PR.
 */
@Serializable
data class PrAnalysisResult(
    val summary: String,
    val issues: List<Issue>,
    val suggestions: List<Suggestion>,
    val filesChanged: List<String>
) {
    /**
     * Форматирует результат в текст для комментария к PR.
     */
    fun formatAsComment(): String {
        return buildString {
            appendLine(summary)
            appendLine()

            if (issues.isNotEmpty()) {
                appendLine("## 🔍 Найденные проблемы")
                appendLine()

                for (issue in issues.sortedByDescending { it.severity }) {
                    val icon = when (issue.severity) {
                        IssueSeverity.HIGH -> "🔴"
                        IssueSeverity.MEDIUM -> "🟡"
                        IssueSeverity.LOW -> "🟢"
                    }
                    val category = when (issue.category) {
                        IssueCategory.SECURITY -> "Безопасность"
                        IssueCategory.BUG -> "Потенциальный баг"
                        IssueCategory.PERFORMANCE -> "Производительность"
                        IssueCategory.OTHER -> "Другое"
                    }

                    appendLine("### $icon $category - ${issue.file}:${issue.line}")
                    appendLine()
                    appendLine("**Проблема:** ${issue.message}")
                    if (issue.code != null) {
                        appendLine()
                        appendLine("```")
                        appendLine(issue.code)
                        appendLine("```")
                    }
                    appendLine()
                }
            }

            if (suggestions.isNotEmpty()) {
                appendLine("## 💡 Рекомендации")
                appendLine()

                for (suggestion in suggestions) {
                    val category = when (suggestion.category) {
                        SuggestionCategory.STYLE -> "Стиль кода"
                        SuggestionCategory.DOCUMENTATION -> "Документация"
                        SuggestionCategory.REFACTORING -> "Рефакторинг"
                        SuggestionCategory.TESTING -> "Тестирование"
                        SuggestionCategory.OTHER -> "Другое"
                    }

                    val location = if (suggestion.line != null) {
                        "${suggestion.file}:${suggestion.line}"
                    } else {
                        suggestion.file
                    }

                    appendLine("### 📝 $category - $location")
                    appendLine()
                    appendLine(suggestion.message)
                    if (suggestion.code != null) {
                        appendLine()
                        appendLine("```")
                        appendLine(suggestion.code)
                        appendLine("```")
                    }
                    appendLine()
                }
            }

            appendLine()
            appendLine("---")
            appendLine("🤖 Автоматический анализ с помощью DevAssistant")
        }
    }
}

/**
 * Проблема в коде.
 */
@Serializable
data class Issue(
    val severity: IssueSeverity,
    val category: IssueCategory,
    val file: String,
    val line: Int,
    val message: String,
    val code: String?
)

/**
 * Уровень серьезности проблемы.
 */
@Serializable
enum class IssueSeverity {
    HIGH, MEDIUM, LOW
}

/**
 * Категория проблемы.
 */
@Serializable
enum class IssueCategory {
    SECURITY, BUG, PERFORMANCE, OTHER
}

/**
 * Рекомендация по улучшению.
 */
@Serializable
data class Suggestion(
    val category: SuggestionCategory,
    val file: String,
    val line: Int?,
    val message: String,
    val code: String?
)

/**
 * Категория рекомендации.
 */
@Serializable
enum class SuggestionCategory {
    STYLE, DOCUMENTATION, REFACTORING, TESTING, OTHER
}
