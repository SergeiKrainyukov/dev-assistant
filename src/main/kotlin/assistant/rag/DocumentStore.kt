package assistant.rag

import assistant.Config
import assistant.Document
import assistant.SearchResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Хранилище документов с поддержкой векторного поиска.
 */
class DocumentStore(private val config: Config) {
    
    private val documents = mutableListOf<Document>()
    private val embeddingService = EmbeddingService(config)
    private val json = Json { 
        prettyPrint = true 
        ignoreUnknownKeys = true
    }
    
    /**
     * Добавляет документ в хранилище.
     */
    suspend fun add(content: String, source: String, metadata: Map<String, String> = emptyMap()) {
        val id = "${source}_${documents.size}"
        val embedding = embeddingService.embed(content)
        val doc = Document(
            id = id,
            content = content,
            source = source,
            embedding = embedding,
            metadata = metadata
        )
        documents.add(doc)
    }
    
    /**
     * Ищет релевантные документы по запросу.
     */
    suspend fun search(query: String, topK: Int = 5): List<SearchResult> {
        if (documents.isEmpty()) return emptyList()
        
        val queryEmbedding = embeddingService.embed(query)
        
        return documents
            .map { doc -> 
                SearchResult(doc, doc.cosineSimilarity(queryEmbedding))
            }
            .sortedByDescending { it.score }
            .take(topK)
    }
    
    /**
     * Сохраняет индекс в файл.
     */
    fun save() {
        val file = File(config.indexPath)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(documents.toList()))
        println("📁 Сохранено ${documents.size} документов в ${config.indexPath}")
    }
    
    /**
     * Загружает индекс из файла.
     */
    fun load(): Boolean {
        val file = File(config.indexPath)
        if (!file.exists()) return false
        
        return try {
            val loaded = json.decodeFromString<List<Document>>(file.readText())
            documents.clear()
            documents.addAll(loaded)
            println("📂 Загружено ${documents.size} документов из ${config.indexPath}")
            true
        } catch (e: Exception) {
            println("⚠️ Ошибка загрузки индекса: ${e.message}")
            false
        }
    }
    
    /**
     * Очищает хранилище.
     */
    fun clear() {
        documents.clear()
    }
    
    /**
     * Возвращает количество документов.
     */
    fun size() = documents.size
    
    /**
     * Возвращает все документы.
     */
    fun all() = documents.toList()
    
    fun close() {
        embeddingService.close()
    }
}
