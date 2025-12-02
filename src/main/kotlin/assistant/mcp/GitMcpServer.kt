package assistant.mcp

import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

/**
 * MCP сервер для интеграции с git репозиторием.
 * 
 * Поддерживает:
 * - git_branch - получение текущей ветки
 * - git_status - статус репозитория
 * - git_log - история коммитов
 */
class GitMcpServer {
    
    private val json = Json { 
        prettyPrint = false 
        ignoreUnknownKeys = true
    }
    
    // Версия протокола
    private val protocolVersion = "2024-11-05"
    
    /**
     * Запускает MCP сервер в режиме stdio.
     */
    fun start() {
        val reader = BufferedReader(InputStreamReader(System.`in`))
        val writer = PrintWriter(System.out, true)
        
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue

            try {
                // Проверяем что это валидный JSON перед парсингом
                if (!line.trim().startsWith("{")) {
                    System.err.println("Warning: Skipping non-JSON input: ${line.take(50)}")
                    continue
                }

                val request = json.parseToJsonElement(line).jsonObject
                val response = handleRequest(request)
                writer.println(json.encodeToString(response))
            } catch (e: SerializationException) {
                System.err.println("JSON parse error: ${e.message}")
                val errorResponse = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", JsonNull)
                    put("error", buildJsonObject {
                        put("code", -32700)
                        put("message", "Parse error: ${e.message}")
                    })
                }
                writer.println(json.encodeToString(errorResponse))
            } catch (e: Exception) {
                System.err.println("Unexpected error: ${e.message}")
                val errorResponse = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", JsonNull)
                    put("error", buildJsonObject {
                        put("code", -32603)
                        put("message", "Internal error: ${e.message}")
                    })
                }
                writer.println(json.encodeToString(errorResponse))
            }
        }
    }
    
    private fun handleRequest(request: JsonObject): JsonObject {
        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.content ?: ""
        
        return when (method) {
            "initialize" -> handleInitialize(id)
            "tools/list" -> handleListTools(id)
            "tools/call" -> handleToolCall(id, request["params"]?.jsonObject)
            else -> buildErrorResponse(id, -32601, "Method not found: $method")
        }
    }
    
    private fun handleInitialize(id: JsonElement?): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            put("result", buildJsonObject {
                put("protocolVersion", protocolVersion)
                put("capabilities", buildJsonObject {
                    put("tools", buildJsonObject {})
                })
                put("serverInfo", buildJsonObject {
                    put("name", "git-mcp-server")
                    put("version", "1.0.0")
                })
            })
        }
    }
    
    private fun handleListTools(id: JsonElement?): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            put("result", buildJsonObject {
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("name", "git_branch")
                        put("description", "Получает текущую ветку git репозитория")
                        put("inputSchema", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {})
                        })
                    })
                    add(buildJsonObject {
                        put("name", "git_status")
                        put("description", "Получает статус git репозитория")
                        put("inputSchema", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {})
                        })
                    })
                    add(buildJsonObject {
                        put("name", "git_log")
                        put("description", "Получает последние коммиты")
                        put("inputSchema", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("count", buildJsonObject {
                                    put("type", "integer")
                                    put("description", "Количество коммитов")
                                    put("default", 5)
                                })
                            })
                        })
                    })
                    add(buildJsonObject {
                        put("name", "git_diff")
                        put("description", "Получает diff между двумя ветками или коммитами")
                        put("inputSchema", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("base", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Базовая ветка или коммит")
                                    put("default", "main")
                                })
                                put("head", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Сравниваемая ветка или коммит (по умолчанию текущая)")
                                })
                            })
                        })
                    })
                    add(buildJsonObject {
                        put("name", "git_show_file")
                        put("description", "Показывает содержимое файла из репозитория")
                        put("inputSchema", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("file", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Путь к файлу")
                                })
                                put("ref", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Ветка или коммит (по умолчанию HEAD)")
                                    put("default", "HEAD")
                                })
                            })
                            put("required", buildJsonArray {
                                add("file")
                            })
                        })
                    })
                    add(buildJsonObject {
                        put("name", "git_changed_files")
                        put("description", "Получает список измененных файлов между ветками")
                        put("inputSchema", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("base", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Базовая ветка")
                                    put("default", "main")
                                })
                                put("head", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Сравниваемая ветка (по умолчанию текущая)")
                                })
                            })
                        })
                    })
                })
            })
        }
    }
    
    private fun handleToolCall(id: JsonElement?, params: JsonObject?): JsonObject {
        val toolName = params?.get("name")?.jsonPrimitive?.content ?: ""
        val arguments = params?.get("arguments")?.jsonObject ?: buildJsonObject {}

        val result = when (toolName) {
            "git_branch" -> executeGitBranch()
            "git_status" -> executeGitStatus()
            "git_log" -> executeGitLog(arguments)
            "git_diff" -> executeGitDiff(arguments)
            "git_show_file" -> executeGitShowFile(arguments)
            "git_changed_files" -> executeGitChangedFiles(arguments)
            else -> buildJsonObject {
                put("error", "Unknown tool: $toolName")
            }
        }
        
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            put("result", buildJsonObject {
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", json.encodeToString(result))
                    })
                })
            })
        }
    }
    
    /**
     * Получает текущую ветку git.
     */
    private fun executeGitBranch(): JsonObject {
        return try {
            val process = ProcessBuilder("git", "branch", "--show-current")
                .redirectErrorStream(true)
                .start()
            
            val branch = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            
            if (exitCode == 0 && branch.isNotEmpty()) {
                buildJsonObject {
                    put("branch", branch)
                    put("detached", false)
                }
            } else {
                // Возможно detached HEAD
                val headProcess = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true)
                    .start()
                val head = headProcess.inputStream.bufferedReader().readText().trim()
                headProcess.waitFor()
                
                buildJsonObject {
                    put("branch", head)
                    put("detached", true)
                }
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Git not available: ${e.message}")
                put("branch", "unknown")
            }
        }
    }
    
    /**
     * Получает статус git репозитория.
     */
    private fun executeGitStatus(): JsonObject {
        return try {
            val process = ProcessBuilder("git", "status", "--porcelain")
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readLines()
            process.waitFor()
            
            val modified = mutableListOf<String>()
            val staged = mutableListOf<String>()
            val untracked = mutableListOf<String>()
            
            for (line in output) {
                if (line.length < 3) continue
                val status = line.substring(0, 2)
                val file = line.substring(3)
                
                when {
                    status.startsWith("?") -> untracked.add(file)
                    status[0] != ' ' -> staged.add(file)
                    status[1] != ' ' -> modified.add(file)
                }
            }
            
            buildJsonObject {
                put("modified", JsonArray(modified.map { JsonPrimitive(it) }))
                put("staged", JsonArray(staged.map { JsonPrimitive(it) }))
                put("untracked", JsonArray(untracked.map { JsonPrimitive(it) }))
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Git not available: ${e.message}")
            }
        }
    }
    
    /**
     * Получает историю коммитов.
     */
    private fun executeGitLog(arguments: JsonObject): JsonObject {
        val count = arguments["count"]?.jsonPrimitive?.intOrNull ?: 5
        
        return try {
            val process = ProcessBuilder(
                "git", "log", 
                "--pretty=format:%H|%s|%an|%ad",
                "--date=short",
                "-n", count.toString()
            ).redirectErrorStream(true).start()
            
            val lines = process.inputStream.bufferedReader().readLines()
            process.waitFor()
            
            val commits = lines.mapNotNull { line ->
                val parts = line.split("|", limit = 4)
                if (parts.size >= 4) {
                    buildJsonObject {
                        put("hash", parts[0].take(7))
                        put("message", parts[1])
                        put("author", parts[2])
                        put("date", parts[3])
                    }
                } else null
            }
            
            buildJsonObject {
                put("commits", JsonArray(commits))
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Git not available: ${e.message}")
                put("commits", JsonArray(emptyList()))
            }
        }
    }
    
    /**
     * Получает diff между ветками.
     */
    private fun executeGitDiff(arguments: JsonObject): JsonObject {
        val base = arguments["base"]?.jsonPrimitive?.content ?: "main"
        val head = arguments["head"]?.jsonPrimitive?.content ?: getCurrentBranchName()

        return try {
            val process = ProcessBuilder("git", "diff", "$base...$head")
                .redirectErrorStream(true)
                .start()

            val diff = process.inputStream.bufferedReader().readText()
            process.waitFor()

            buildJsonObject {
                put("base", base)
                put("head", head)
                put("diff", diff)
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Git not available: ${e.message}")
                put("diff", "")
            }
        }
    }

    /**
     * Показывает содержимое файла.
     */
    private fun executeGitShowFile(arguments: JsonObject): JsonObject {
        val file = arguments["file"]?.jsonPrimitive?.content ?: ""
        val ref = arguments["ref"]?.jsonPrimitive?.content ?: "HEAD"

        if (file.isEmpty()) {
            return buildJsonObject {
                put("error", "File path is required")
            }
        }

        return try {
            val process = ProcessBuilder("git", "show", "$ref:$file")
                .redirectErrorStream(true)
                .start()

            val content = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                buildJsonObject {
                    put("file", file)
                    put("ref", ref)
                    put("content", content)
                }
            } else {
                buildJsonObject {
                    put("error", "File not found: $file at $ref")
                    put("content", "")
                }
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Git not available: ${e.message}")
                put("content", "")
            }
        }
    }

    /**
     * Получает список измененных файлов.
     */
    private fun executeGitChangedFiles(arguments: JsonObject): JsonObject {
        val base = arguments["base"]?.jsonPrimitive?.content ?: "main"
        val head = arguments["head"]?.jsonPrimitive?.content ?: getCurrentBranchName()

        return try {
            val process = ProcessBuilder("git", "diff", "--name-status", "$base...$head")
                .redirectErrorStream(true)
                .start()

            val output = process.inputStream.bufferedReader().readLines()
            process.waitFor()

            val files = output.mapNotNull { line ->
                val parts = line.split("\t", limit = 2)
                if (parts.size == 2) {
                    buildJsonObject {
                        put("status", parts[0])
                        put("file", parts[1])
                    }
                } else null
            }

            buildJsonObject {
                put("base", base)
                put("head", head)
                put("files", JsonArray(files))
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Git not available: ${e.message}")
                put("files", JsonArray(emptyList()))
            }
        }
    }

    /**
     * Получает имя текущей ветки.
     */
    private fun getCurrentBranchName(): String {
        return try {
            val process = ProcessBuilder("git", "branch", "--show-current")
                .redirectErrorStream(true)
                .start()
            val branch = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (branch.isEmpty()) "HEAD" else branch
        } catch (e: Exception) {
            "HEAD"
        }
    }

    private fun buildErrorResponse(id: JsonElement?, code: Int, message: String): JsonObject {
        return buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }
    }
}

/**
 * Точка входа для MCP сервера.
 */
fun main() {
    GitMcpServer().start()
}
