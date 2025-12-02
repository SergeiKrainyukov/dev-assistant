# DevAssistant - Анализатор Pull Request с RAG и AI

## Описание проекта

DevAssistant - это интеллектуальный ассистент для анализа Pull Request, который использует:
- **RAG (Retrieval-Augmented Generation)** для работы с документацией и кодом
- **MCP (Model Context Protocol)** для интеграции с git и GitHub
- **Ollama** для AI анализа кода
- **Web UI** для удобной работы с PR

## Основные возможности

### 🔍 Анализ GitHub PR через Web UI
1. Вставьте ссылку на Pull Request
2. Система автоматически:
   - Получает diff и файлы через GitHub API
   - Индексирует изменения в RAG
   - Анализирует код на паттерны (баги, уязвимости)
   - Использует Ollama для AI-рекомендаций
3. Получите полный отчет с проблемами и рекомендациями

## Структура проекта

```
dev-assistant/
├── src/main/kotlin/assistant/
│   ├── Main.kt              # Точка входа CLI
│   ├── Models.kt            # Модели данных
│   ├── github/
│   │   └── GitHubClient.kt  # Клиент GitHub API
│   ├── pr/
│   │   ├── GitHubPrAnalyzer.kt  # Анализатор PR с RAG и LLM
│   │   ├── PrAnalyzer.kt        # Локальный анализатор
│   │   └── PrAnalyzerCli.kt     # CLI для PR анализа
│   ├── mcp/
│   │   ├── GitMcpServer.kt      # MCP сервер для git
│   │   └── GitHubMcpServer.kt   # MCP сервер для GitHub
│   ├── rag/
│   │   ├── DocumentStore.kt # Хранилище документов
│   │   ├── Embeddings.kt    # Генерация эмбеддингов
│   │   └── Indexer.kt       # Индексатор документов
│   ├── commands/
│   │   └── HelpCommand.kt   # Обработчик /help
│   └── web/
│       └── WebServer.kt     # Веб-интерфейс
├── .github/workflows/
│   └── pr-review.yml        # GitHub Actions для PR анализа
└── build.gradle.kts
```

## Установка и запуск

### Требования
- JDK 17+
- Gradle 8+
- Git
- Ollama (для AI анализа)

### Установка Ollama
```bash
# macOS
brew install ollama

# Запуск сервера
ollama serve

# Загрузка модели
ollama pull llama3.2
ollama pull nomic-embed-text
```

### Сборка проекта
```bash
./gradlew build
```

### Запуск Web UI (рекомендуется)
```bash
./gradlew runWeb
```

Откройте в браузере: **http://localhost:8080**

### Для приватных репозиториев
Установите переменную окружения с GitHub токеном:
```bash
export GITHUB_TOKEN=ghp_xxxxxxxxxxxx
./gradlew runWeb
```

## Использование Web UI

### Анализ Pull Request

1. Откройте http://localhost:8080
2. В поле "Анализ Pull Request" вставьте ссылку на PR:
   - `https://github.com/owner/repo/pull/123`
   - или `owner/repo#123`
3. Нажмите "Анализировать"
4. Получите отчет с:
   - **Summary** - общая информация и AI-summary
   - **Проблемы** - найденные баги и уязвимости
   - **Рекомендации** - советы по улучшению
   - **Полный отчет** - Markdown версия

### Возможности анализа

Система проверяет:

#### 🔴 Критические (CRITICAL)
- Пароли и API ключи в коде
- SQL injection
- Использование eval/exec

#### 🟠 Высокий приоритет (HIGH)
- XSS уязвимости (innerHTML, dangerouslySetInnerHTML)
- Command injection (ProcessBuilder)
- Использование Runtime.getRuntime()

#### 🟡 Средний приоритет (MEDIUM)
- Пустые catch блоки
- Kotlin !! оператор (NPE)
- Сравнение с null через equals

#### 💡 Рекомендации
- TODO/FIXME комментарии
- Остатки отладочного кода
- Слишком большие изменения

## Команды Gradle

```bash
# Сборка
./gradlew build

# Запуск Web UI
./gradlew runWeb

# Запуск CLI
./gradlew run

# Запуск MCP сервера для Git
./gradlew runMcp

# Запуск MCP сервера для GitHub
./gradlew runGitHubMcp

# Индексация документов
./gradlew indexDocs

# Локальный анализ PR
./gradlew analyzePr -Pbase=main -Phead=feature-branch
```

## MCP серверы

### Git MCP Server
Инструменты для работы с локальным git:
- `git_branch` - текущая ветка
- `git_status` - статус репозитория
- `git_log` - история коммитов
- `git_diff` - diff между ветками
- `git_show_file` - содержимое файла
- `git_changed_files` - список измененных файлов

### GitHub MCP Server
Инструменты для работы с GitHub API:
- `github_pr_info` - информация о PR
- `github_pr_files` - список файлов в PR
- `github_pr_diff` - diff PR
- `github_file_content` - содержимое файла из репо

### Конфигурация Claude Desktop
```json
{
  "mcpServers": {
    "git-assistant": {
      "command": "/path/to/dev-assistant/run-mcp.sh",
      "args": [],
      "env": {}
    },
    "github-assistant": {
      "command": "./gradlew",
      "args": ["runGitHubMcp"],
      "env": {
        "GITHUB_TOKEN": "your_token_here"
      }
    }
  }
}
```

## Архитектура

```
┌─────────────────────────────────────────────────────────────┐
│                      Web Browser                             │
│                   http://localhost:8080                      │
└─────────────────────────────┬───────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                     WebServer (Ktor)                         │
│  ├── GET /           - UI страница                          │
│  ├── POST /api/analyze-pr  - Анализ PR                      │
│  └── POST /api/command     - Git команды                    │
└─────────────────────────────┬───────────────────────────────┘
                              │
          ┌───────────────────┼───────────────────┐
          ▼                   ▼                   ▼
┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐
│  GitHubClient   │  │  DocumentStore  │  │  Ollama API     │
│  (GitHub API)   │  │  (RAG Storage)  │  │  (LLM Analysis) │
└────────┬────────┘  └────────┬────────┘  └────────┬────────┘
         │                    │                    │
         ▼                    ▼                    ▼
┌─────────────────────────────────────────────────────────────┐
│                   GitHubPrAnalyzer                          │
│  ├── Получение diff и файлов PR                             │
│  ├── Индексация изменений в RAG                             │
│  ├── Паттерн-анализ (security, bugs, style)                │
│  └── AI анализ через Ollama                                 │
└─────────────────────────────────────────────────────────────┘
```

## Конфигурация

Настройки в `Config.kt`:
```kotlin
Config(
    llmApiUrl = "http://localhost:11434",  // Ollama API
    llmModel = "llama3.2",                 // Модель для анализа
    embeddingModel = "nomic-embed-text",   // Модель для эмбеддингов
    docsPath = "project",                  // Путь к документации
    indexPath = "data/index.json",         // Путь к индексу RAG
    chunkSize = 500,                       // Размер чанка
    chunkOverlap = 50                      // Перекрытие чанков
)
```

## CI/CD интеграция

Файл `.github/workflows/pr-review.yml` автоматически анализирует PR при создании и обновлении:

```yaml
on:
  pull_request:
    types: [opened, synchronize, reopened]

jobs:
  analyze:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Setup JDK
        uses: actions/setup-java@v4
        with:
          java-version: '17'
      - name: Analyze PR
        run: ./gradlew analyzePr -Pbase=${{ github.base_ref }}
```

## Troubleshooting

### Ollama не доступен
```bash
# Проверьте что Ollama запущен
curl http://localhost:11434/api/tags

# Запустите если не запущен
ollama serve
```

### Ошибка доступа к GitHub
- Для публичных репо токен не нужен
- Для приватных установите `GITHUB_TOKEN`
- Проверьте правильность URL

### Медленный анализ
- AI анализ может занять 30-60 секунд
- Используйте более быструю модель (llama3.2 вместо llama2)
