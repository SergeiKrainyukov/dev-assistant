package assistant.rag

import assistant.Config
import assistant.OllamaEmbeddingResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Сервис для генерации эмбеддингов через Ollama API.
 */
class EmbeddingService(private val config: Config) {
    
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { 
                ignoreUnknownKeys = true 
                isLenient = true
            })
        }
    }
    
    @Serializable
    private data class EmbedRequest(
        val model: String,
        val prompt: String
    )
    
    /**
     * Генерирует эмбеддинг для текста.
     * 
     * Если Ollama недоступна, использует простой fallback на основе TF.
     */
    suspend fun embed(text: String): List<Float> {
        return try {
            val response = client.post("${config.llmApiUrl}/api/embeddings") {
                contentType(ContentType.Application.Json)
                setBody(EmbedRequest(config.embeddingModel, text))
            }
            response.body<OllamaEmbeddingResponse>().embedding
        } catch (e: Exception) {
            // Fallback: простой эмбеддинг на основе хеша слов
            // (для демонстрации, когда Ollama недоступна)
            simpleEmbedding(text)
        }
    }
    
    /**
     * Простой fallback эмбеддинг без внешних зависимостей.
     * Использует bag-of-words подход с хешированием.
     */
    private fun simpleEmbedding(text: String, dimensions: Int = 384): List<Float> {
        val embedding = FloatArray(dimensions) { 0f }
        val words = text.lowercase()
            .replace(Regex("[^a-zа-яё0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
        
        for (word in words) {
            // Используем хеш слова для определения позиции
            val hash = word.hashCode()
            val index = kotlin.math.abs(hash) % dimensions
            embedding[index] += 1f
        }
        
        // Нормализация
        val norm = kotlin.math.sqrt(embedding.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0) {
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }
        
        return embedding.toList()
    }
    
    fun close() {
        client.close()
    }
}
