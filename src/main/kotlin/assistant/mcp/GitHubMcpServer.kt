package assistant.mcp

import assistant.github.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter

/**
 * MCP сервер для интеграции с GitHub API.
 *
 * Поддерживает:
 * - github_pr_info - информация о PR
 * - github_pr_files - список файлов в PR
 * - github_pr_diff - diff для PR
 * - github_file_content - содержимое файла из репозитория
 */
class GitHubMcpServer {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    private val token = System.getenv("GITHUB_TOKEN")
    private val githubClient = GitHubClient(token)

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

        githubClient.close()
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
                    put("name", "github-mcp-server")
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
                        put("name", "github_pr_info")
                        put("description", "Получает информацию о Pull Request по URL или owner/repo#number")
                        put("inputSchema", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("url", buildJsonObject {
                                    put("type", "string")
                                    put("description", "URL PR или формат owner/repo#number")
                                })
                            })
                            put("required", buildJsonArray { add("url") })
                        })
                    })
                    add(buildJsonObject {
                        put("name", "github_pr_files")
                        put("description", "Получает список файлов в Pull Request")
                        put("inputSchema", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("url", buildJsonObject {
                                    put("type", "string")
                                    put("description", "URL PR или формат owner/repo#number")
                                })
                            })
                            put("required", buildJsonArray { add("url") })
                        })
                    })
                    add(buildJsonObject {
                        put("name", "github_pr_diff")
                        put("description", "Получает diff для Pull Request")
                        put("inputSchema", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("url", buildJsonObject {
                                    put("type", "string")
                                    put("description", "URL PR или формат owner/repo#number")
                                })
                            })
                            put("required", buildJsonArray { add("url") })
                        })
                    })
                    add(buildJsonObject {
                        put("name", "github_file_content")
                        put("description", "Получает содержимое файла из репозитория")
                        put("inputSchema", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("owner", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Владелец репозитория")
                                })
                                put("repo", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Название репозитория")
                                })
                                put("path", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Путь к файлу")
                                })
                                put("ref", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Ветка или коммит (по умолчанию main)")
                                    put("default", "main")
                                })
                            })
                            put("required", buildJsonArray {
                                add("owner")
                                add("repo")
                                add("path")
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

        val result = runBlocking {
            when (toolName) {
                "github_pr_info" -> executeGetPrInfo(arguments)
                "github_pr_files" -> executeGetPrFiles(arguments)
                "github_pr_diff" -> executeGetPrDiff(arguments)
                "github_file_content" -> executeGetFileContent(arguments)
                else -> buildJsonObject {
                    put("error", "Unknown tool: $toolName")
                }
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

    private suspend fun executeGetPrInfo(arguments: JsonObject): JsonObject {
        val url = arguments["url"]?.jsonPrimitive?.content ?: ""

        val ref = githubClient.parsePrUrl(url)
            ?: return buildJsonObject { put("error", "Invalid PR URL: $url") }

        return try {
            val pr = githubClient.getPullRequest(ref)
            buildJsonObject {
                put("number", pr.number)
                put("title", pr.title)
                put("body", pr.body ?: "")
                put("state", pr.state)
                put("url", pr.html_url)
                put("author", pr.user.login)
                put("base", pr.base.ref)
                put("head", pr.head.ref)
                put("changed_files", pr.changed_files)
                put("additions", pr.additions)
                put("deletions", pr.deletions)
                put("created_at", pr.created_at)
                put("updated_at", pr.updated_at)
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to get PR info: ${e.message}")
            }
        }
    }

    private suspend fun executeGetPrFiles(arguments: JsonObject): JsonObject {
        val url = arguments["url"]?.jsonPrimitive?.content ?: ""

        val ref = githubClient.parsePrUrl(url)
            ?: return buildJsonObject { put("error", "Invalid PR URL: $url") }

        return try {
            val files = githubClient.getPullRequestFiles(ref)
            buildJsonObject {
                put("pr", ref.toString())
                put("files", buildJsonArray {
                    for (file in files) {
                        add(buildJsonObject {
                            put("filename", file.filename)
                            put("status", file.status)
                            put("additions", file.additions)
                            put("deletions", file.deletions)
                            put("changes", file.changes)
                            file.patch?.let { put("patch", it) }
                        })
                    }
                })
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to get PR files: ${e.message}")
            }
        }
    }

    private suspend fun executeGetPrDiff(arguments: JsonObject): JsonObject {
        val url = arguments["url"]?.jsonPrimitive?.content ?: ""

        val ref = githubClient.parsePrUrl(url)
            ?: return buildJsonObject { put("error", "Invalid PR URL: $url") }

        return try {
            val diff = githubClient.getPullRequestDiff(ref)
            buildJsonObject {
                put("pr", ref.toString())
                put("diff", diff)
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to get PR diff: ${e.message}")
            }
        }
    }

    private suspend fun executeGetFileContent(arguments: JsonObject): JsonObject {
        val owner = arguments["owner"]?.jsonPrimitive?.content ?: ""
        val repo = arguments["repo"]?.jsonPrimitive?.content ?: ""
        val path = arguments["path"]?.jsonPrimitive?.content ?: ""
        val ref = arguments["ref"]?.jsonPrimitive?.content ?: "main"

        if (owner.isEmpty() || repo.isEmpty() || path.isEmpty()) {
            return buildJsonObject {
                put("error", "owner, repo, and path are required")
            }
        }

        return try {
            val content = githubClient.getFileContent(owner, repo, path, ref)
            buildJsonObject {
                put("owner", owner)
                put("repo", repo)
                put("path", path)
                put("ref", ref)
                put("content", content)
            }
        } catch (e: Exception) {
            buildJsonObject {
                put("error", "Failed to get file content: ${e.message}")
            }
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

fun main() {
    GitHubMcpServer().start()
}
