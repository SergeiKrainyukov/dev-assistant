package assistant

import kotlinx.serialization.Serializable

/**
 * Конфигурация ассистента.
 */
@Serializable
data class Config(
    val llmApiUrl: String = "http://localhost:11434",
    val llmModel: String = "qwen3:4b",
    val embeddingModel: String = "nomic-embed-text",
    val docsPath: String = "project",
    val indexPath: String = "data/index.json",
    val chunkSize: Int = 500,
    val chunkOverlap: Int = 50
) {
    companion object {
        fun default() = Config()
    }
}

/**
 * Документ в RAG хранилище.
 */
@Serializable
data class Document(
    val id: String,
    val content: String,
    val source: String,
    val embedding: List<Float> = emptyList(),
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * Вычисляет косинусное сходство с другим документом.
     */
    fun cosineSimilarity(other: List<Float>): Float {
        if (embedding.isEmpty() || other.isEmpty()) return 0f
        if (embedding.size != other.size) return 0f
        
        var dotProduct = 0f
        var normA = 0f
        var normB = 0f
        
        for (i in embedding.indices) {
            dotProduct += embedding[i] * other[i]
            normA += embedding[i] * embedding[i]
            normB += other[i] * other[i]
        }
        
        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator > 0) dotProduct / denominator else 0f
    }
}

/**
 * Результат поиска.
 */
data class SearchResult(
    val document: Document,
    val score: Float
)

/**
 * Ответ от Ollama API.
 */
@Serializable
data class OllamaEmbeddingResponse(
    val embedding: List<Float>
)

@Serializable
data class OllamaGenerateRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = false
)

@Serializable
data class OllamaGenerateResponse(
    val response: String
)

@Serializable
data class OllamaStreamResponse(
    val model: String? = null,
    val response: String? = null,
    val done: Boolean = false
)
