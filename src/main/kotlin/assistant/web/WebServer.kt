package assistant.web

import assistant.Config
import assistant.commands.HelpCommand
import assistant.pr.GitHubPrAnalyzer
import assistant.rag.DocumentStore
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.html.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.html.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.io.File

/**
 * Веб-сервер для взаимодействия с ассистентом через браузер.
 * Поддерживает анализ GitHub PR через RAG и Ollama.
 */
@Serializable
data class CommandRequest(
    val command: String,
    val args: Map<String, String> = emptyMap()
)

@Serializable
data class CommandResponse(
    val success: Boolean,
    val result: String? = null,
    val error: String? = null,
    val data: JsonObject? = null
)

class WebServer {

    private val config = Config.default()
    private val store = DocumentStore(config)
    private val helpCommand = HelpCommand(config, store)
    private val prAnalyzer = GitHubPrAnalyzer(config)

    init {
        // Загружаем индекс при старте
        if (File(config.indexPath).exists()) {
            store.load()
        }
    }

    /**
     * Выполняет git команду через ProcessBuilder.
     */
    private fun executeGitCommand(command: String, vararg args: String): CommandResponse {
        return try {
            val process = ProcessBuilder("git", command, *args)
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                CommandResponse(success = true, result = output.trim())
            } else {
                CommandResponse(success = false, error = output.trim())
            }
        } catch (e: Exception) {
            CommandResponse(success = false, error = "Error: ${e.message}")
        }
    }

    /**
     * Запускает веб-сервер на порту 8080.
     */
    fun start(port: Int = 8080) {
        embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    ignoreUnknownKeys = true
                })
            }

            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
            }

            routing {
                // Главная страница с интерфейсом
                get("/") {
                    call.respondHtml {
                        head {
                            title { +"DevAssistant - PR Analyzer" }
                            meta { charset = "UTF-8" }
                            style {
                                unsafe {
                                    raw(CSS_STYLES)
                                }
                            }
                        }
                        body {
                            div("container") {
                                div("header") {
                                    h1 { +"🚀 DevAssistant" }
                                    p { +"Анализатор Pull Request с RAG и AI" }
                                }
                                div("content") {
                                    // Секция анализа PR
                                    div("command-section highlight") {
                                        h2 { +"🔍 Анализ Pull Request" }
                                        p("description") {
                                            +"Вставьте ссылку на GitHub PR для анализа. Система получит diff, проиндексирует изменения и выдаст рекомендации."
                                        }
                                        div("input-group") {
                                            input {
                                                id = "prUrl"
                                                type = InputType.text
                                                placeholder = "https://github.com/owner/repo/pull/123"
                                            }
                                            button {
                                                id = "analyzePrBtn"
                                                onClick = "analyzePr()"
                                                +"Анализировать"
                                            }
                                        }
                                        div("checkbox-group") {
                                            input {
                                                id = "useOllama"
                                                type = InputType.checkBox
                                                checked = true
                                            }
                                            label {
                                                htmlFor = "useOllama"
                                                +"Использовать Ollama для AI анализа"
                                            }
                                        }
                                    }

                                    // Секция результатов
                                    div("results-section") {
                                        id = "resultsSection"
                                        style = "display: none;"

                                        div("result-header") {
                                            h3 {
                                                id = "resultTitle"
                                                +"Результат анализа"
                                            }
                                            div("result-badges") {
                                                id = "resultBadges"
                                            }
                                        }

                                        div("tabs") {
                                            button {
                                                id = "tabSummary"
                                                classes = setOf("tab-btn", "active")
                                                onClick = "showTab('summary')"
                                                +"📋 Summary"
                                            }
                                            button {
                                                id = "tabIssues"
                                                classes = setOf("tab-btn")
                                                onClick = "showTab('issues')"
                                                +"⚠️ Проблемы"
                                            }
                                            button {
                                                id = "tabSuggestions"
                                                classes = setOf("tab-btn")
                                                onClick = "showTab('suggestions')"
                                                +"💡 Рекомендации"
                                            }
                                            button {
                                                id = "tabFull"
                                                classes = setOf("tab-btn")
                                                onClick = "showTab('full')"
                                                +"📄 Полный отчет"
                                            }
                                        }

                                        div("tab-content active") {
                                            id = "contentSummary"
                                        }
                                        div("tab-content") {
                                            id = "contentIssues"
                                        }
                                        div("tab-content") {
                                            id = "contentSuggestions"
                                        }
                                        div("tab-content") {
                                            id = "contentFull"
                                        }
                                    }

                                    // Секция Git статуса
                                    div("command-section") {
                                        h2 { +"📊 Git Status" }
                                        div("button-group") {
                                            button {
                                                onClick = "executeCommand('branch')"
                                                +"Current Branch"
                                            }
                                            button {
                                                onClick = "executeCommand('status')"
                                                +"Repository Status"
                                            }
                                            button {
                                                onClick = "executeCommand('log', {count: '5'})"
                                                +"Recent Commits (5)"
                                            }
                                        }
                                    }

                                    // Секция чата с ассистентом
                                    div("command-section") {
                                        h2 { +"💬 Чат с ассистентом" }
                                        div("input-group") {
                                            input {
                                                id = "chatInput"
                                                type = InputType.text
                                                placeholder = "Задайте вопрос о проекте..."
                                            }
                                            button {
                                                onClick = "executeChat()"
                                                +"Спросить"
                                            }
                                        }
                                    }

                                    // Статус и вывод
                                    div("loading") {
                                        id = "loading"
                                        div("spinner")
                                        span {
                                            id = "loadingText"
                                            +"Загрузка..."
                                        }
                                    }

                                    div("output") {
                                        id = "output"
                                        +"Готов к работе. Вставьте ссылку на PR для анализа."
                                    }
                                }

                                div("footer") {
                                    p { +"DevAssistant • RAG + Ollama • MCP" }
                                }
                            }

                            script {
                                unsafe {
                                    raw(JS_CODE)
                                }
                            }
                        }
                    }
                }

                // API endpoint для анализа PR
                post("/api/analyze-pr") {
                    val request = call.receive<CommandRequest>()
                    val prUrl = request.args["url"] ?: ""

                    if (prUrl.isBlank()) {
                        call.respond(CommandResponse(
                            success = false,
                            error = "URL PR не указан"
                        ))
                        return@post
                    }

                    try {
                        val report = prAnalyzer.analyzePrByUrl(prUrl)

                        val dataJson = buildJsonObject {
                            put("prNumber", report.prNumber)
                            put("prTitle", report.prTitle)
                            put("prUrl", report.prUrl)
                            put("author", report.author)
                            put("baseBranch", report.baseBranch)
                            put("headBranch", report.headBranch)
                            put("filesChanged", report.filesChanged.size)
                            put("totalAdditions", report.totalAdditions)
                            put("totalDeletions", report.totalDeletions)
                            put("issuesCount", report.issues.size)
                            put("suggestionsCount", report.suggestions.size)
                            put("llmSummary", report.llmSummary)

                            put("issues", buildJsonArray {
                                for (issue in report.issues) {
                                    add(buildJsonObject {
                                        put("severity", issue.severity.name)
                                        put("category", issue.category.displayName)
                                        put("file", issue.file)
                                        issue.line?.let { put("line", it) }
                                        put("message", issue.message)
                                        issue.code?.let { put("code", it) }
                                    })
                                }
                            })

                            put("suggestions", buildJsonArray {
                                for (suggestion in report.suggestions) {
                                    add(buildJsonObject {
                                        put("category", suggestion.category.name)
                                        put("file", suggestion.file)
                                        suggestion.line?.let { put("line", it) }
                                        put("message", suggestion.message)
                                        suggestion.code?.let { put("code", it) }
                                    })
                                }
                            })

                            put("markdown", report.toMarkdown())
                        }

                        call.respond(CommandResponse(
                            success = true,
                            result = report.toShortSummary(),
                            data = dataJson
                        ))
                    } catch (e: Exception) {
                        call.respond(CommandResponse(
                            success = false,
                            error = "Ошибка анализа PR: ${e.message}\n\nУбедитесь что:\n1. URL корректный\n2. PR публичный или установлен GITHUB_TOKEN\n3. Ollama запущен (ollama serve)"
                        ))
                    }
                }

                // API endpoint для выполнения команд
                post("/api/command") {
                    val request = call.receive<CommandRequest>()

                    val response = when (request.command) {
                        "branch" -> executeGitCommand("branch", "--show-current")
                        "status" -> executeGitCommand("status", "--short")
                        "log" -> {
                            val count = request.args["count"] ?: "5"
                            executeGitCommand("log", "--oneline", "-n", count)
                        }
                        "help" -> {
                            val query = request.args["query"] ?: ""
                            try {
                                val answer = helpCommand.execute(query)
                                CommandResponse(success = true, result = answer)
                            } catch (e: Exception) {
                                CommandResponse(
                                    success = false,
                                    error = "Error: ${e.message}\n\nNote: Make sure Ollama is running (ollama serve) and index exists (./gradlew indexDocs)"
                                )
                            }
                        }
                        "custom" -> {
                            val cmd = request.args["cmd"] ?: ""
                            if (cmd.isNotEmpty()) {
                                val parts = cmd.split(" ")
                                executeGitCommand(parts[0], *parts.drop(1).toTypedArray())
                            } else {
                                CommandResponse(success = false, error = "Empty command")
                            }
                        }
                        else -> CommandResponse(success = false, error = "Unknown command: ${request.command}")
                    }

                    call.respond(response)
                }

                // Health check
                get("/health") {
                    call.respond(mapOf(
                        "status" to "OK",
                        "ollama" to checkOllamaStatus()
                    ))
                }
            }
        }.start(wait = true)
    }

    private fun checkOllamaStatus(): String {
        return try {
            val process = ProcessBuilder("curl", "-s", "${config.llmApiUrl}/api/tags")
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            if (exitCode == 0) "connected" else "unavailable"
        } catch (e: Exception) {
            "unavailable"
        }
    }

    companion object {
        private val CSS_STYLES = """
            * { margin: 0; padding: 0; box-sizing: border-box; }
            body {
                font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
                min-height: 100vh;
                padding: 20px;
                color: #e4e4e7;
            }
            .container {
                max-width: 1000px;
                margin: 0 auto;
                background: #1e1e2e;
                border-radius: 16px;
                box-shadow: 0 20px 60px rgba(0,0,0,0.5);
                overflow: hidden;
            }
            .header {
                background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
                color: white;
                padding: 30px;
                text-align: center;
            }
            .header h1 { font-size: 2em; margin-bottom: 10px; }
            .header p { opacity: 0.9; }
            .content { padding: 30px; }
            .command-section {
                margin-bottom: 25px;
                padding: 20px;
                background: #27273a;
                border-radius: 12px;
                border: 1px solid #3f3f5a;
            }
            .command-section.highlight {
                border: 2px solid #6366f1;
                background: linear-gradient(135deg, #27273a 0%, #2d2d44 100%);
            }
            .command-section h2 {
                color: #a5b4fc;
                margin-bottom: 15px;
                font-size: 1.2em;
            }
            .description {
                color: #9ca3af;
                font-size: 0.9em;
                margin-bottom: 15px;
                line-height: 1.5;
            }
            .button-group {
                display: flex;
                gap: 10px;
                flex-wrap: wrap;
            }
            button {
                padding: 12px 24px;
                border: none;
                border-radius: 8px;
                font-size: 14px;
                font-weight: 600;
                cursor: pointer;
                transition: all 0.3s;
                background: linear-gradient(135deg, #6366f1 0%, #8b5cf6 100%);
                color: white;
            }
            button:hover {
                transform: translateY(-2px);
                box-shadow: 0 4px 15px rgba(99, 102, 241, 0.4);
            }
            button:active { transform: translateY(0); }
            button:disabled {
                opacity: 0.5;
                cursor: not-allowed;
                transform: none;
            }
            .input-group {
                display: flex;
                gap: 10px;
            }
            input[type="text"] {
                flex: 1;
                padding: 12px 16px;
                border: 2px solid #3f3f5a;
                border-radius: 8px;
                font-size: 14px;
                background: #1a1a2e;
                color: #e4e4e7;
                transition: border-color 0.3s;
            }
            input[type="text"]:focus {
                outline: none;
                border-color: #6366f1;
            }
            input[type="text"]::placeholder {
                color: #6b7280;
            }
            .checkbox-group {
                margin-top: 12px;
                display: flex;
                align-items: center;
                gap: 8px;
            }
            .checkbox-group label {
                color: #9ca3af;
                font-size: 0.9em;
                cursor: pointer;
            }
            input[type="checkbox"] {
                width: 18px;
                height: 18px;
                cursor: pointer;
            }
            .output {
                margin-top: 20px;
                padding: 20px;
                background: #0f0f17;
                color: #d4d4d8;
                border-radius: 8px;
                font-family: 'Monaco', 'Courier New', monospace;
                font-size: 13px;
                line-height: 1.6;
                min-height: 100px;
                max-height: 300px;
                overflow-y: auto;
                white-space: pre-wrap;
                word-wrap: break-word;
                border: 1px solid #27273a;
            }
            .output.success { border-left: 4px solid #22c55e; }
            .output.error { border-left: 4px solid #ef4444; }
            .loading {
                display: none;
                color: #a5b4fc;
                font-weight: 600;
                margin-top: 15px;
                padding: 15px;
                background: #27273a;
                border-radius: 8px;
                align-items: center;
                gap: 12px;
            }
            .loading.active { display: flex; }
            .spinner {
                width: 20px;
                height: 20px;
                border: 3px solid #3f3f5a;
                border-top: 3px solid #6366f1;
                border-radius: 50%;
                animation: spin 1s linear infinite;
            }
            @keyframes spin {
                0% { transform: rotate(0deg); }
                100% { transform: rotate(360deg); }
            }
            .results-section {
                margin-top: 25px;
                padding: 20px;
                background: #27273a;
                border-radius: 12px;
                border: 1px solid #3f3f5a;
            }
            .result-header {
                display: flex;
                justify-content: space-between;
                align-items: center;
                margin-bottom: 20px;
                flex-wrap: wrap;
                gap: 10px;
            }
            .result-header h3 {
                color: #e4e4e7;
                font-size: 1.1em;
            }
            .result-badges {
                display: flex;
                gap: 8px;
                flex-wrap: wrap;
            }
            .badge {
                padding: 4px 10px;
                border-radius: 12px;
                font-size: 12px;
                font-weight: 600;
            }
            .badge.critical { background: #7f1d1d; color: #fca5a5; }
            .badge.high { background: #7c2d12; color: #fdba74; }
            .badge.medium { background: #713f12; color: #fcd34d; }
            .badge.success { background: #14532d; color: #86efac; }
            .tabs {
                display: flex;
                gap: 5px;
                margin-bottom: 15px;
                border-bottom: 1px solid #3f3f5a;
                padding-bottom: 10px;
            }
            .tab-btn {
                padding: 8px 16px;
                background: transparent;
                border: 1px solid #3f3f5a;
                color: #9ca3af;
                font-size: 13px;
            }
            .tab-btn.active {
                background: #6366f1;
                border-color: #6366f1;
                color: white;
            }
            .tab-btn:hover:not(.active) {
                background: #3f3f5a;
                transform: none;
                box-shadow: none;
            }
            .tab-content {
                display: none;
                background: #1a1a2e;
                padding: 15px;
                border-radius: 8px;
                max-height: 400px;
                overflow-y: auto;
            }
            .tab-content.active { display: block; }
            .issue-item, .suggestion-item {
                padding: 12px;
                margin-bottom: 10px;
                background: #27273a;
                border-radius: 8px;
                border-left: 4px solid #6366f1;
            }
            .issue-item.critical { border-left-color: #ef4444; }
            .issue-item.high { border-left-color: #f97316; }
            .issue-item.medium { border-left-color: #eab308; }
            .issue-item.low { border-left-color: #22c55e; }
            .issue-header {
                display: flex;
                justify-content: space-between;
                margin-bottom: 8px;
            }
            .issue-file {
                color: #a5b4fc;
                font-family: monospace;
                font-size: 0.9em;
            }
            .issue-category {
                color: #9ca3af;
                font-size: 0.85em;
            }
            .issue-message {
                color: #e4e4e7;
                line-height: 1.5;
            }
            .issue-code {
                margin-top: 8px;
                padding: 8px;
                background: #0f0f17;
                border-radius: 4px;
                font-family: monospace;
                font-size: 12px;
                color: #d4d4d8;
                overflow-x: auto;
            }
            .markdown-content {
                line-height: 1.7;
            }
            .markdown-content h1, .markdown-content h2, .markdown-content h3 {
                color: #e4e4e7;
                margin: 15px 0 10px;
            }
            .markdown-content code {
                background: #0f0f17;
                padding: 2px 6px;
                border-radius: 4px;
                font-family: monospace;
            }
            .markdown-content pre {
                background: #0f0f17;
                padding: 12px;
                border-radius: 8px;
                overflow-x: auto;
            }
            .markdown-content blockquote {
                border-left: 4px solid #6366f1;
                padding-left: 15px;
                color: #9ca3af;
                margin: 10px 0;
            }
            .markdown-content table {
                width: 100%;
                border-collapse: collapse;
                margin: 10px 0;
            }
            .markdown-content th, .markdown-content td {
                border: 1px solid #3f3f5a;
                padding: 8px 12px;
                text-align: left;
            }
            .markdown-content th {
                background: #27273a;
            }
            .footer {
                text-align: center;
                padding: 20px;
                color: #6b7280;
                font-size: 0.85em;
                border-top: 1px solid #27273a;
            }
        """.trimIndent()

        private val JS_CODE = """
            let analysisData = null;

            async function analyzePr() {
                const urlInput = document.getElementById('prUrl');
                const url = urlInput.value.trim();

                if (!url) {
                    showError('Введите URL Pull Request');
                    return;
                }

                showLoading('Анализируем PR... Получаем данные с GitHub...');
                hideResults();

                try {
                    const response = await fetch('/api/analyze-pr', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ command: 'analyze', args: { url: url } })
                    });

                    const data = await response.json();

                    if (data.success && data.data) {
                        analysisData = data.data;
                        showResults(data.data);
                        showOutput(data.result, true);
                    } else {
                        showError(data.error || 'Неизвестная ошибка');
                    }
                } catch (error) {
                    showError('Ошибка сети: ' + error.message);
                } finally {
                    hideLoading();
                }
            }

            function showResults(data) {
                const section = document.getElementById('resultsSection');
                section.style.display = 'block';

                // Заголовок
                document.getElementById('resultTitle').textContent =
                    'PR #' + data.prNumber + ': ' + data.prTitle;

                // Badges
                const badges = document.getElementById('resultBadges');
                badges.innerHTML = '';

                const criticalCount = data.issues.filter(i => i.severity === 'CRITICAL').length;
                const highCount = data.issues.filter(i => i.severity === 'HIGH').length;
                const mediumCount = data.issues.filter(i => i.severity === 'MEDIUM').length;

                if (criticalCount > 0) {
                    badges.innerHTML += '<span class="badge critical">🔴 ' + criticalCount + ' критических</span>';
                }
                if (highCount > 0) {
                    badges.innerHTML += '<span class="badge high">🟠 ' + highCount + ' высоких</span>';
                }
                if (mediumCount > 0) {
                    badges.innerHTML += '<span class="badge medium">🟡 ' + mediumCount + ' средних</span>';
                }
                if (data.issues.length === 0) {
                    badges.innerHTML += '<span class="badge success">✅ Проблем не найдено</span>';
                }

                // Summary tab
                document.getElementById('contentSummary').innerHTML =
                    '<div class="markdown-content">' +
                    '<p><strong>Автор:</strong> ' + data.author + '</p>' +
                    '<p><strong>Ветки:</strong> ' + data.headBranch + ' → ' + data.baseBranch + '</p>' +
                    '<p><strong>Файлов:</strong> ' + data.filesChanged + ' | <strong>Изменений:</strong> +' + data.totalAdditions + '/-' + data.totalDeletions + '</p>' +
                    '<hr style="border-color: #3f3f5a; margin: 15px 0;">' +
                    '<h4>AI Summary</h4>' +
                    '<p>' + (data.llmSummary || 'Нет данных') + '</p>' +
                    '</div>';

                // Issues tab
                const issuesHtml = data.issues.length > 0
                    ? data.issues.map(issue =>
                        '<div class="issue-item ' + issue.severity.toLowerCase() + '">' +
                        '<div class="issue-header">' +
                        '<span class="issue-file">' + issue.file + (issue.line ? ':' + issue.line : '') + '</span>' +
                        '<span class="issue-category">' + issue.category + '</span>' +
                        '</div>' +
                        '<div class="issue-message">' + issue.message + '</div>' +
                        (issue.code ? '<div class="issue-code">' + escapeHtml(issue.code) + '</div>' : '') +
                        '</div>'
                    ).join('')
                    : '<p style="color: #22c55e;">✅ Автоматический анализ не выявил проблем</p>';

                document.getElementById('contentIssues').innerHTML = issuesHtml;

                // Suggestions tab
                const suggestionsHtml = data.suggestions.length > 0
                    ? data.suggestions.map(s =>
                        '<div class="suggestion-item">' +
                        '<div class="issue-header">' +
                        '<span class="issue-file">' + s.file + (s.line ? ':' + s.line : '') + '</span>' +
                        '<span class="issue-category">' + s.category + '</span>' +
                        '</div>' +
                        '<div class="issue-message">' + s.message + '</div>' +
                        '</div>'
                    ).join('')
                    : '<p style="color: #9ca3af;">Нет дополнительных рекомендаций</p>';

                document.getElementById('contentSuggestions').innerHTML = suggestionsHtml;

                // Full report tab
                document.getElementById('contentFull').innerHTML =
                    '<div class="markdown-content" style="white-space: pre-wrap;">' +
                    escapeHtml(data.markdown) +
                    '</div>';

                showTab('summary');
            }

            function showTab(tabName) {
                // Update buttons
                document.querySelectorAll('.tab-btn').forEach(btn => btn.classList.remove('active'));
                document.getElementById('tab' + tabName.charAt(0).toUpperCase() + tabName.slice(1)).classList.add('active');

                // Update content
                document.querySelectorAll('.tab-content').forEach(content => content.classList.remove('active'));
                document.getElementById('content' + tabName.charAt(0).toUpperCase() + tabName.slice(1)).classList.add('active');
            }

            function hideResults() {
                document.getElementById('resultsSection').style.display = 'none';
            }

            async function executeCommand(cmd, args = {}) {
                showLoading('Выполняем команду...');

                try {
                    const response = await fetch('/api/command', {
                        method: 'POST',
                        headers: { 'Content-Type': 'application/json' },
                        body: JSON.stringify({ command: cmd, args: args })
                    });

                    const data = await response.json();

                    if (data.success) {
                        showOutput(data.result || 'Команда выполнена', true);
                    } else {
                        showError(data.error || 'Ошибка выполнения');
                    }
                } catch (error) {
                    showError('Ошибка сети: ' + error.message);
                } finally {
                    hideLoading();
                }
            }

            function executeChat() {
                const input = document.getElementById('chatInput');
                const query = input.value.trim();
                if (query) {
                    showLoading('Обрабатываем запрос с помощью RAG и Ollama...');
                    executeCommand('help', { query: query });
                }
            }

            function showLoading(text) {
                const loading = document.getElementById('loading');
                document.getElementById('loadingText').textContent = text;
                loading.classList.add('active');
                document.getElementById('analyzePrBtn').disabled = true;
            }

            function hideLoading() {
                document.getElementById('loading').classList.remove('active');
                document.getElementById('analyzePrBtn').disabled = false;
            }

            function showOutput(text, success) {
                const output = document.getElementById('output');
                output.textContent = text;
                output.className = 'output ' + (success ? 'success' : 'error');
            }

            function showError(text) {
                showOutput(text, false);
            }

            function escapeHtml(text) {
                const div = document.createElement('div');
                div.textContent = text;
                return div.innerHTML;
            }

            // Enter key handlers
            document.getElementById('prUrl').addEventListener('keypress', (e) => {
                if (e.key === 'Enter') analyzePr();
            });

            document.getElementById('chatInput').addEventListener('keypress', (e) => {
                if (e.key === 'Enter') executeChat();
            });
        """.trimIndent()
    }
}

fun main() {
    println("🚀 Starting DevAssistant Web Server...")
    println("📡 Server will be available at: http://localhost:8080")
    println("💡 Tip: Set GITHUB_TOKEN env var for private repos")
    println("Press Ctrl+C to stop")

    WebServer().start()
}
