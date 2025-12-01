package assistant.web

import assistant.mcp.GitMcpServer
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
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Веб-сервер для взаимодействия с ассистентом через браузер.
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
    val error: String? = null
)

class WebServer {

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
                            title { +"DevAssistant - Web Interface" }
                            style {
                                unsafe {
                                    raw("""
                                        * { margin: 0; padding: 0; box-sizing: border-box; }
                                        body {
                                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
                                            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                                            min-height: 100vh;
                                            padding: 20px;
                                        }
                                        .container {
                                            max-width: 900px;
                                            margin: 0 auto;
                                            background: white;
                                            border-radius: 16px;
                                            box-shadow: 0 20px 60px rgba(0,0,0,0.3);
                                            overflow: hidden;
                                        }
                                        .header {
                                            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                                            color: white;
                                            padding: 30px;
                                            text-align: center;
                                        }
                                        .header h1 { font-size: 2em; margin-bottom: 10px; }
                                        .header p { opacity: 0.9; }
                                        .content { padding: 30px; }
                                        .command-section {
                                            margin-bottom: 30px;
                                            padding: 20px;
                                            background: #f8f9fa;
                                            border-radius: 8px;
                                        }
                                        .command-section h2 {
                                            color: #667eea;
                                            margin-bottom: 15px;
                                            font-size: 1.3em;
                                        }
                                        .button-group {
                                            display: flex;
                                            gap: 10px;
                                            flex-wrap: wrap;
                                        }
                                        button {
                                            padding: 12px 24px;
                                            border: none;
                                            border-radius: 6px;
                                            font-size: 14px;
                                            font-weight: 600;
                                            cursor: pointer;
                                            transition: all 0.3s;
                                            background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                                            color: white;
                                        }
                                        button:hover {
                                            transform: translateY(-2px);
                                            box-shadow: 0 4px 12px rgba(102, 126, 234, 0.4);
                                        }
                                        button:active { transform: translateY(0); }
                                        .input-group {
                                            display: flex;
                                            gap: 10px;
                                            margin-top: 10px;
                                        }
                                        input {
                                            flex: 1;
                                            padding: 12px;
                                            border: 2px solid #e0e0e0;
                                            border-radius: 6px;
                                            font-size: 14px;
                                        }
                                        input:focus {
                                            outline: none;
                                            border-color: #667eea;
                                        }
                                        .output {
                                            margin-top: 20px;
                                            padding: 20px;
                                            background: #1e1e1e;
                                            color: #d4d4d4;
                                            border-radius: 8px;
                                            font-family: 'Monaco', 'Courier New', monospace;
                                            font-size: 13px;
                                            line-height: 1.6;
                                            min-height: 200px;
                                            max-height: 400px;
                                            overflow-y: auto;
                                            white-space: pre-wrap;
                                            word-wrap: break-word;
                                        }
                                        .output.success { border-left: 4px solid #4caf50; }
                                        .output.error { border-left: 4px solid #f44336; }
                                        .loading {
                                            display: none;
                                            color: #667eea;
                                            font-weight: 600;
                                            margin-top: 10px;
                                        }
                                        .loading.active { display: block; }
                                    """)
                                }
                            }
                        }
                        body {
                            div("container") {
                                div("header") {
                                    h1 { +"🚀 DevAssistant" }
                                    p { +"Web Interface for Git Commands" }
                                }
                                div("content") {
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
                                                onClick = "executeCommand('log', {count: 5})"
                                                +"Recent Commits (5)"
                                            }
                                            button {
                                                onClick = "executeCommand('log', {count: 10})"
                                                +"Recent Commits (10)"
                                            }
                                        }
                                    }

                                    div("command-section") {
                                        h2 { +"🔍 Custom Command" }
                                        div("input-group") {
                                            input {
                                                id = "customCommand"
                                                type = InputType.text
                                                placeholder = "Enter git command (e.g., 'diff', 'remote -v')"
                                            }
                                            button {
                                                onClick = "executeCustomCommand()"
                                                +"Execute"
                                            }
                                        }
                                    }

                                    div("loading") {
                                        id = "loading"
                                        +"⏳ Executing command..."
                                    }

                                    div("output") {
                                        id = "output"
                                        +"Output will appear here..."
                                    }
                                }
                            }

                            script {
                                unsafe {
                                    raw("""
                                        async function executeCommand(cmd, args = {}) {
                                            const loading = document.getElementById('loading');
                                            const output = document.getElementById('output');

                                            loading.classList.add('active');
                                            output.textContent = '';
                                            output.className = 'output';

                                            try {
                                                const response = await fetch('/api/command', {
                                                    method: 'POST',
                                                    headers: { 'Content-Type': 'application/json' },
                                                    body: JSON.stringify({ command: cmd, args: args })
                                                });

                                                const data = await response.json();

                                                if (data.success) {
                                                    output.className = 'output success';
                                                    output.textContent = data.result || 'Command executed successfully';
                                                } else {
                                                    output.className = 'output error';
                                                    output.textContent = 'Error: ' + (data.error || 'Unknown error');
                                                }
                                            } catch (error) {
                                                output.className = 'output error';
                                                output.textContent = 'Network error: ' + error.message;
                                            } finally {
                                                loading.classList.remove('active');
                                            }
                                        }

                                        function executeCustomCommand() {
                                            const input = document.getElementById('customCommand');
                                            const cmd = input.value.trim();
                                            if (cmd) {
                                                const parts = cmd.split(' ');
                                                const command = parts[0];
                                                const args = {};
                                                if (parts.length > 1) {
                                                    args.extra = parts.slice(1).join(' ');
                                                }
                                                executeCommand('custom', { cmd: cmd });
                                            }
                                        }

                                        document.getElementById('customCommand').addEventListener('keypress', (e) => {
                                            if (e.key === 'Enter') executeCustomCommand();
                                        });

                                        // Load initial status
                                        executeCommand('branch');
                                    """)
                                }
                            }
                        }
                    }
                }

                // API endpoint для выполнения команд
                post("/api/command") {
                    val request = call.receive<CommandRequest>()

                    val response = when (request.command) {
                        "branch" -> {
                            executeGitCommand("branch", "--show-current")
                        }
                        "status" -> {
                            executeGitCommand("status", "--short")
                        }
                        "log" -> {
                            val count = request.args["count"] ?: "5"
                            executeGitCommand("log", "--oneline", "-n", count)
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
                        else -> {
                            CommandResponse(success = false, error = "Unknown command: ${request.command}")
                        }
                    }

                    call.respond(response)
                }

                // Health check
                get("/health") {
                    call.respond(mapOf("status" to "OK"))
                }
            }
        }.start(wait = true)
    }
}

fun main() {
    println("🚀 Starting DevAssistant Web Server...")
    println("📡 Server will be available at: http://localhost:8080")
    println("Press Ctrl+C to stop")

    WebServer().start()
}
