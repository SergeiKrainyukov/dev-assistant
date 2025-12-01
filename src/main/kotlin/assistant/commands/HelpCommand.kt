package assistant.commands

import assistant.Config
import assistant.OllamaGenerateRequest
import assistant.OllamaGenerateResponse
import assistant.rag.DocumentStore
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json

/**
 * Обработчик команды /help.
 * Использует RAG для поиска релевантной информации в документации
 * и LLM для генерации ответа.
 */
class HelpCommand(
    private val config: Config,
    private val store: DocumentStore
) {
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
    }
    
    /**
     * Выполняет команду /help с использованием RAG.
     */
    suspend fun execute(query: String): String {
        if (query.isBlank()) {
            return showGeneralHelp()
        }
        
        // 1. Ищем релевантные документы
        val results = store.search(query, topK = 3)
        
        if (results.isEmpty()) {
            return "❌ Не найдено релевантной информации по запросу: $query\n" +
                   "   Попробуйте переиндексировать документы командой /index"
        }
        
        // 2. Формируем контекст из найденных документов
        val context = results.joinToString("\n\n---\n\n") { result ->
            "📄 ${result.document.source} (релевантность: ${String.format("%.2f", result.score)})\n" +
            result.document.content
        }
        
        // 3. Пробуем использовать LLM для генерации ответа
        return try {
            generateWithLLM(query, context)
        } catch (e: Exception) {
            // Fallback: просто показываем найденные фрагменты
            buildString {
                appendLine("🔍 Найдено по запросу: \"$query\"")
                appendLine()
                results.forEach { result ->
                    appendLine("📄 ${result.document.source}")
                    appendLine("   Релевантность: ${String.format("%.1f%%", result.score * 100)}")
                    appendLine()
                    // Показываем первые 300 символов
                    val preview = result.document.content.take(300)
                    appendLine("   $preview${if (result.document.content.length > 300) "..." else ""}")
                    appendLine()
                }
            }
        }
    }
    
    /**
     * Генерирует ответ с помощью LLM.
     */
    private suspend fun generateWithLLM(query: String, context: String): String {
        val prompt = """
            |Ты - ассистент разработчика. Отвечай на вопросы о проекте на основе предоставленной документации.
            |
            |ДОКУМЕНТАЦИЯ:
            |$context
            |
            |ВОПРОС: $query
            |
            |Дай краткий и полезный ответ на основе документации. 
            |Если информации недостаточно, честно скажи об этом.
            |Отвечай на русском языке.
        """.trimMargin()
        
        val response = client.post("${config.llmApiUrl}/api/generate") {
            contentType(ContentType.Application.Json)
            setBody(OllamaGenerateRequest(config.llmModel, prompt))
        }
        
        val result = response.body<OllamaGenerateResponse>()
        return "🤖 ${result.response}"
    }
    
    /**
     * Показывает общую справку.
     */
    private fun showGeneralHelp(): String {
        return """
            |📚 DevAssistant - Справка
            |
            |Доступные команды:
            |  /help <вопрос>  - Получить помощь по проекту
            |  /search <текст> - Поиск по документации
            |  /branch         - Показать текущую ветку git
            |  /status         - Статус git репозитория
            |  /index          - Переиндексировать документы
            |  /quit           - Выход
            |
            |Примеры:
            |  /help структура проекта
            |  /help как работает RAG?
            |  /search API endpoints
            |
            |💡 Совет: Задавайте конкретные вопросы для лучших результатов.
        """.trimMargin()
    }
    
    fun close() {
        client.close()
    }
}
