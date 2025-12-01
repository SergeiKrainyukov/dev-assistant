package assistant

import assistant.commands.HelpCommand
import assistant.rag.DocumentIndexer
import assistant.rag.DocumentStore
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * DevAssistant - Умный ассистент разработчика.
 * 
 * Функции:
 * - RAG для работы с документацией проекта
 * - MCP интеграция с git
 * - Команда /help для получения помощи
 */
class DevAssistant(private val config: Config) {
    
    private val store = DocumentStore(config)
    private val helpCommand = HelpCommand(config, store)
    
    /**
     * Инициализирует ассистента, загружая индекс.
     */
    fun initialize(): Boolean {
        println("🚀 DevAssistant v1.0.0")
        println()
        
        // Пробуем загрузить существующий индекс
        val loaded = store.load()
        
        if (!loaded) {
            println("📝 Индекс не найден. Выполните /index для индексации документов.")
        }
        
        return loaded
    }
    
    /**
     * Обрабатывает команду пользователя.
     */
    suspend fun handleCommand(input: String): String? {
        val trimmed = input.trim()
        
        return when {
            trimmed.equals("/quit", ignoreCase = true) || 
            trimmed.equals("/exit", ignoreCase = true) -> null
            
            trimmed.startsWith("/help") -> {
                val query = trimmed.removePrefix("/help").trim()
                helpCommand.execute(query)
            }
            
            trimmed.startsWith("/search") -> {
                val query = trimmed.removePrefix("/search").trim()
                searchDocuments(query)
            }
            
            trimmed.equals("/branch", ignoreCase = true) -> {
                getCurrentBranch()
            }
            
            trimmed.equals("/status", ignoreCase = true) -> {
                getGitStatus()
            }
            
            trimmed.equals("/index", ignoreCase = true) -> {
                indexDocuments()
            }
            
            trimmed.equals("/info", ignoreCase = true) -> {
                showInfo()
            }
            
            trimmed.startsWith("/") -> {
                "❌ Неизвестная команда: $trimmed\n   Введите /help для списка команд."
            }
            
            else -> {
                // Обычный текст обрабатываем как вопрос
                helpCommand.execute(trimmed)
            }
        }
    }
    
    /**
     * Поиск по документам.
     */
    private suspend fun searchDocuments(query: String): String {
        if (query.isBlank()) {
            return "❌ Укажите поисковый запрос: /search <текст>"
        }
        
        val results = store.search(query, topK = 5)
        
        if (results.isEmpty()) {
            return "❌ Ничего не найдено по запросу: $query"
        }
        
        return buildString {
            appendLine("🔍 Результаты поиска: \"$query\"")
            appendLine()
            results.forEachIndexed { index, result ->
                appendLine("${index + 1}. 📄 ${result.document.source}")
                appendLine("   Релевантность: ${String.format("%.1f%%", result.score * 100)}")
                val preview = result.document.content.take(150).replace("\n", " ")
                appendLine("   $preview...")
                appendLine()
            }
        }
    }
    
    /**
     * Получает текущую ветку git.
     */
    private fun getCurrentBranch(): String {
        return try {
            val process = ProcessBuilder("git", "branch", "--show-current")
                .redirectErrorStream(true)
                .start()
            
            val branch = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            
            if (exitCode == 0 && branch.isNotEmpty()) {
                "🌿 Текущая ветка: $branch"
            } else {
                // Проверяем detached HEAD
                val headProcess = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true)
                    .start()
                val head = headProcess.inputStream.bufferedReader().readText().trim()
                headProcess.waitFor()
                "🌿 Detached HEAD: $head"
            }
        } catch (e: Exception) {
            "❌ Git недоступен: ${e.message}"
        }
    }
    
    /**
     * Получает статус git.
     */
    private fun getGitStatus(): String {
        return try {
            val process = ProcessBuilder("git", "status", "--short")
                .redirectErrorStream(true)
                .start()
            
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            
            if (output.isBlank()) {
                "✅ Рабочая директория чистая"
            } else {
                buildString {
                    appendLine("📋 Git Status:")
                    appendLine(output)
                }
            }
        } catch (e: Exception) {
            "❌ Git недоступен: ${e.message}"
        }
    }
    
    /**
     * Индексирует документы.
     */
    private suspend fun indexDocuments(): String {
        val indexer = DocumentIndexer(config)
        indexer.indexDirectory(config.docsPath)
        
        // Обновляем store
        store.load()
        
        return "✅ Индексация завершена. Документов в индексе: ${store.size()}"
    }
    
    /**
     * Показывает информацию о конфигурации.
     */
    private fun showInfo(): String {
        return buildString {
            appendLine("ℹ️ Информация о конфигурации:")
            appendLine("   LLM API: ${config.llmApiUrl}")
            appendLine("   LLM модель: ${config.llmModel}")
            appendLine("   Embedding модель: ${config.embeddingModel}")
            appendLine("   Путь документов: ${config.docsPath}")
            appendLine("   Путь индекса: ${config.indexPath}")
            appendLine("   Документов в индексе: ${store.size()}")
        }
    }
    
    fun close() {
        store.close()
        helpCommand.close()
    }
}

/**
 * Точка входа приложения.
 */
fun main() = runBlocking {
    val config = Config.default()
    val assistant = DevAssistant(config)
    
    assistant.initialize()
    
    println()
    println("💡 Введите /help для списка команд или задайте вопрос о проекте.")
    println("   Для выхода введите /quit")
    println()
    
    while (true) {
        print("> ")
        val input = readlnOrNull() ?: break
        
        val response = assistant.handleCommand(input)
        
        if (response == null) {
            println("👋 До свидания!")
            break
        }
        
        println(response)
        println()
    }
    
    assistant.close()
}
