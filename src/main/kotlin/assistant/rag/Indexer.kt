package assistant.rag

import assistant.Config
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Индексатор документов для RAG.
 * Обрабатывает README и файлы из docs/.
 */
class DocumentIndexer(private val config: Config) {
    
    private val store = DocumentStore(config)
    
    // Поддерживаемые расширения
    private val supportedExtensions = setOf("md", "txt", "json", "yaml", "yml", "kt", "java")
    
    /**
     * Индексирует директорию с документами.
     */
    suspend fun indexDirectory(path: String) {
        val dir = File(path)
        if (!dir.exists()) {
            println("❌ Директория не найдена: $path")
            return
        }
        
        println("📚 Индексация директории: $path")
        store.clear()
        
        // Ищем README в корне
        val readme = File("README.md")
        if (readme.exists()) {
            indexFile(readme)
        }
        
        // Рекурсивно обходим директорию
        indexDirectoryRecursive(dir)
        
        store.save()
        println("✅ Индексация завершена: ${store.size()} чанков")
    }
    
    private suspend fun indexDirectoryRecursive(dir: File) {
        dir.listFiles()?.forEach { file ->
            when {
                file.isDirectory && !file.name.startsWith(".") -> {
                    indexDirectoryRecursive(file)
                }
                file.isFile && file.extension in supportedExtensions -> {
                    indexFile(file)
                }
            }
        }
    }
    
    /**
     * Индексирует отдельный файл.
     */
    suspend fun indexFile(file: File) {
        if (!file.exists()) {
            println("❌ Файл не найден: ${file.path}")
            return
        }
        
        println("  📄 ${file.path}")
        
        val content = file.readText()
        val chunks = chunkText(content)
        
        chunks.forEachIndexed { index, chunk ->
            store.add(
                content = chunk,
                source = file.path,
                metadata = mapOf(
                    "chunk_index" to index.toString(),
                    "total_chunks" to chunks.size.toString(),
                    "file_type" to file.extension
                )
            )
        }
    }
    
    /**
     * Разбивает текст на чанки с перекрытием.
     */
    private fun chunkText(text: String): List<String> {
        val chunks = mutableListOf<String>()
        val words = text.split(Regex("\\s+"))
        
        if (words.size <= config.chunkSize) {
            return listOf(text)
        }
        
        var start = 0
        while (start < words.size) {
            val end = minOf(start + config.chunkSize, words.size)
            val chunk = words.subList(start, end).joinToString(" ")
            chunks.add(chunk)
            start += config.chunkSize - config.chunkOverlap
        }
        
        return chunks
    }
    
    fun getStore() = store
}

/**
 * Точка входа для индексации документов.
 */
fun main(args: Array<String>) = runBlocking {
    val path = args.firstOrNull() ?: "project"
    val config = Config.default()
    val indexer = DocumentIndexer(config)
    
    println("🚀 Запуск индексации...")
    println("   Путь: $path")
    println("   Размер чанка: ${config.chunkSize} слов")
    println("   Перекрытие: ${config.chunkOverlap} слов")
    println()
    
    indexer.indexDirectory(path)
    indexer.getStore().close()
}
