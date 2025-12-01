# DevAssistant - Умный ассистент разработчика

## Описание проекта

DevAssistant - это интеллектуальный ассистент для разработчиков, который использует:
- **RAG (Retrieval-Augmented Generation)** для работы с документацией проекта
- **MCP (Model Context Protocol)** для интеграции с git-репозиторием
- **CLI интерфейс** с командой /help для получения помощи

## Структура проекта

```
dev-assistant/
├── src/main/kotlin/assistant/
│   ├── Main.kt              # Точка входа CLI
│   ├── mcp/
│   │   └── GitMcpServer.kt  # MCP сервер для git
│   ├── rag/
│   │   ├── DocumentStore.kt # Хранилище документов
│   │   ├── Embeddings.kt    # Генерация эмбеддингов
│   │   └── Indexer.kt       # Индексатор документов
│   └── commands/
│       └── HelpCommand.kt   # Обработчик /help
├── project/                  # Демо-проект для индексации
│   ├── docs/                 # Документация
│   └── src/                  # Исходный код
├── data/                     # Индекс RAG
└── build.gradle.kts
```

## Установка и запуск

### Требования
- JDK 17+
- Gradle 8+
- Git

### Сборка
```bash
./gradlew build
```

### Индексация документов
```bash
./gradlew indexDocs
```

### Запуск ассистента
```bash
./gradlew run
```

### Запуск MCP сервера
```bash
./gradlew runMcp
```

## Использование

После запуска доступны команды:
- `/help <вопрос>` - получить помощь по проекту
- `/branch` - показать текущую ветку git
- `/search <запрос>` - поиск по документации
- `/quit` - выход

## Примеры

```
> /help как устроена структура проекта?
Проект состоит из следующих модулей:
- mcp/ - MCP сервер для интеграции с git
- rag/ - система RAG для работы с документацией
- commands/ - обработчики команд

> /branch
Текущая ветка: main

> /search API endpoints
Найдено в docs/api.md:
POST /api/users - создание пользователя
GET /api/users/{id} - получение пользователя
```

## Конфигурация

### Claude Desktop

Скопируйте конфигурацию в `~/Library/Application Support/Claude/claude_desktop_config.json`:
```json
{
  "mcpServers": {
    "git-assistant": {
      "command": "/Users/sergeikrainyukov/Desktop/dev-assistant/run-mcp.sh",
      "args": [],
      "env": {}
    }
  }
}
```

**Важно:** Замените путь в `command` на актуальный абсолютный путь к файлу `run-mcp.sh` в вашем проекте.

После изменения конфигурации перезапустите Claude Desktop.

### LLM API (опционально)

Файл `config.json`:
```json
{
  "llm_api_url": "http://localhost:11434",
  "llm_model": "llama3.2",
  "embedding_model": "nomic-embed-text",
  "docs_path": "project/docs",
  "index_path": "data/index.json"
}
```

## Стиль кода

### Kotlin Style Guide
- Используем camelCase для функций и переменных
- PascalCase для классов
- Максимальная длина строки: 120 символов
- Все публичные функции должны иметь KDoc

### Пример
```kotlin
/**
 * Выполняет поиск по документации.
 * @param query поисковый запрос
 * @return список релевантных документов
 */
suspend fun searchDocuments(query: String): List<Document> {
    // реализация
}
```
