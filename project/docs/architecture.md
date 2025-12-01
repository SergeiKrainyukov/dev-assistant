# Архитектура проекта

## Общая схема

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│   CLI/UI    │────▶│  Assistant  │────▶│   LLM API   │
└─────────────┘     └─────────────┘     └─────────────┘
                           │
                    ┌──────┴──────┐
                    ▼             ▼
             ┌───────────┐ ┌───────────┐
             │    RAG    │ │    MCP    │
             │  (docs)   │ │   (git)   │
             └───────────┘ └───────────┘
```

## Компоненты

### 1. CLI Interface (Main.kt)
Основной интерфейс пользователя. Принимает команды и выводит результаты.

**Ответственности:**
- Парсинг команд (/help, /search, /branch)
- Форматирование вывода
- Управление сессией

### 2. RAG Module

#### DocumentStore
Хранит проиндексированные документы с эмбеддингами.

```kotlin
class DocumentStore(config: Config) {
    private val documents: MutableList<Document> = mutableListOf()
    
    suspend fun add(doc: Document)
    suspend fun search(query: String, topK: Int = 5): List<Document>
    fun save()
    fun load()
}
```

#### Embeddings
Генерирует векторные представления текста.

```kotlin
class EmbeddingService(config: Config) {
    suspend fun embed(text: String): FloatArray
    suspend fun embedBatch(texts: List<String>): List<FloatArray>
}
```

#### Indexer
Индексирует файлы документации.

```kotlin
class DocumentIndexer(config: Config) {
    suspend fun indexDirectory(path: String)
    suspend fun indexFile(path: String)
}
```

### 3. MCP Module

#### GitMcpServer
MCP сервер для интеграции с git.

**Поддерживаемые инструменты:**
- `git_branch` - текущая ветка
- `git_status` - статус репозитория
- `git_log` - история коммитов

### 4. Commands

#### HelpCommand
Обрабатывает команду /help, используя RAG для поиска релевантной информации.

```kotlin
class HelpCommand(
    private val store: DocumentStore,
    private val llm: LlmService
) {
    suspend fun execute(query: String): String
}
```

## Поток данных

1. Пользователь вводит `/help как работает API?`
2. CLI парсит команду и передаёт в HelpCommand
3. HelpCommand ищет релевантные документы через DocumentStore
4. Найденные фрагменты + вопрос отправляются в LLM
5. LLM генерирует ответ на основе контекста
6. Ответ выводится пользователю

## Схема данных

### Document
```kotlin
@Serializable
data class Document(
    val id: String,
    val content: String,
    val source: String,          // путь к файлу
    val embedding: FloatArray,   // вектор
    val metadata: Map<String, String> = emptyMap()
)
```

### Config
```kotlin
@Serializable
data class Config(
    val llmApiUrl: String = "http://localhost:11434",
    val llmModel: String = "llama3.2",
    val embeddingModel: String = "nomic-embed-text",
    val docsPath: String = "project/docs",
    val indexPath: String = "data/index.json",
    val chunkSize: Int = 500,
    val chunkOverlap: Int = 50
)
```

## Зависимости

- Kotlin Coroutines - асинхронность
- Ktor Client - HTTP запросы к LLM API
- kotlinx.serialization - JSON сериализация
