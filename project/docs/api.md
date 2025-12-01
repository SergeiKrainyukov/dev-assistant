# API Documentation

## Обзор

Ассистент предоставляет программный API для интеграции с другими инструментами.

## MCP Protocol

### Доступные инструменты (Tools)

#### git_branch
Получает текущую ветку git репозитория.

**Параметры:** нет

**Возвращает:**
```json
{
  "branch": "main",
  "detached": false
}
```

#### git_status
Получает статус git репозитория.

**Параметры:** нет

**Возвращает:**
```json
{
  "modified": ["file1.kt", "file2.kt"],
  "staged": ["file3.kt"],
  "untracked": []
}
```

#### git_log
Получает последние коммиты.

**Параметры:**
- `count` (int, optional): количество коммитов (по умолчанию 5)

**Возвращает:**
```json
{
  "commits": [
    {
      "hash": "abc123",
      "message": "feat: add new feature",
      "author": "developer",
      "date": "2024-01-15"
    }
  ]
}
```

## RAG API

### Индексация документов

```kotlin
val indexer = DocumentIndexer(config)
indexer.indexDirectory("project/docs")
indexer.indexFile("README.md")
```

### Поиск по документам

```kotlin
val store = DocumentStore(config)
val results = store.search("как создать API", topK = 5)

results.forEach { doc ->
    println("${doc.source}: ${doc.content}")
}
```

## CLI Commands

| Команда | Описание | Пример |
|---------|----------|--------|
| /help | Получить помощь по проекту | /help структура проекта |
| /search | Поиск по документации | /search API endpoints |
| /branch | Текущая ветка git | /branch |
| /status | Статус git | /status |
| /quit | Выход | /quit |

## Примеры интеграции

### С Claude Desktop

В файле `claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "dev-assistant": {
      "command": "./gradlew",
      "args": ["runMcp", "-q"],
      "cwd": "/path/to/dev-assistant"
    }
  }
}
```

### Программное использование

```kotlin
fun main() = runBlocking {
    val assistant = DevAssistant(Config.load())
    
    // Индексация при старте
    assistant.indexDocs()
    
    // Обработка запроса
    val response = assistant.handleCommand("/help как добавить новый endpoint?")
    println(response)
}
```
