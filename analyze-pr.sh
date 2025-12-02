#!/bin/bash

# Скрипт для анализа Pull Request локально
#
# Использование:
#   ./analyze-pr.sh                    # Анализ текущей ветки vs main
#   ./analyze-pr.sh feature-branch     # Анализ указанной ветки vs main
#   ./analyze-pr.sh main feature       # Анализ feature vs main
#   ./analyze-pr.sh --pr 123           # Анализ PR #123 через GitHub API

set -e

# Цвета для вывода
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}🤖 DevAssistant - PR Analyzer${NC}"
echo "======================================"
echo ""

# Проверяем что мы в git репозитории
if ! git rev-parse --git-dir > /dev/null 2>&1; then
    echo -e "${RED}❌ Ошибка: Не найден git репозиторий${NC}"
    exit 1
fi

# Определяем параметры
BASE_BRANCH="main"
HEAD_BRANCH=""
PR_NUMBER=""
OUTPUT_FILE="pr-review.md"

# Парсим аргументы
while [[ $# -gt 0 ]]; do
    case $1 in
        --pr)
            PR_NUMBER="$2"
            shift 2
            ;;
        --base)
            BASE_BRANCH="$2"
            shift 2
            ;;
        --head)
            HEAD_BRANCH="$2"
            shift 2
            ;;
        --output)
            OUTPUT_FILE="$2"
            shift 2
            ;;
        --help)
            echo "Использование:"
            echo "  $0                          # Анализ текущей ветки vs main"
            echo "  $0 feature-branch           # Анализ feature-branch vs main"
            echo "  $0 main feature-branch      # Анализ feature-branch vs main"
            echo "  $0 --pr 123                 # Анализ PR #123"
            echo "  $0 --base main --head feat  # Анализ feat vs main"
            echo "  $0 --output result.md       # Сохранить в result.md"
            exit 0
            ;;
        *)
            if [ -z "$HEAD_BRANCH" ]; then
                if [ "$1" != "main" ] && [ "$1" != "master" ]; then
                    HEAD_BRANCH="$1"
                else
                    BASE_BRANCH="$1"
                fi
            else
                BASE_BRANCH="$HEAD_BRANCH"
                HEAD_BRANCH="$1"
            fi
            shift
            ;;
    esac
done

# Если HEAD не указан, используем текущую ветку
if [ -z "$HEAD_BRANCH" ] && [ -z "$PR_NUMBER" ]; then
    HEAD_BRANCH=$(git branch --show-current)
    if [ -z "$HEAD_BRANCH" ]; then
        HEAD_BRANCH="HEAD"
    fi
fi

# Индексируем документацию (если еще не сделано)
if [ ! -f "data/index.json" ]; then
    echo -e "${YELLOW}📚 Индексируем документацию...${NC}"
    ./gradlew indexDocs || echo -e "${YELLOW}⚠️ Индексация не удалась, продолжаем без RAG${NC}"
    echo ""
fi

# Запускаем анализ
echo -e "${BLUE}🔍 Запускаем анализ...${NC}"
if [ -n "$PR_NUMBER" ]; then
    echo "PR: #$PR_NUMBER"
    ./gradlew analyzePr -Ppr="$PR_NUMBER" -Pformat=text -Poutput="$OUTPUT_FILE"
else
    echo "Base: $BASE_BRANCH"
    echo "Head: $HEAD_BRANCH"
    ./gradlew analyzePr -Pbase="$BASE_BRANCH" -Phead="$HEAD_BRANCH" -Pformat=text -Poutput="$OUTPUT_FILE"
fi

EXIT_CODE=$?

echo ""
echo "======================================"

if [ $EXIT_CODE -eq 0 ]; then
    echo -e "${GREEN}✅ Анализ завершен успешно${NC}"
    echo ""
    echo "Результат сохранен в: $OUTPUT_FILE"
    echo ""

    # Показываем результат
    if [ -f "$OUTPUT_FILE" ]; then
        echo -e "${BLUE}📄 Результат анализа:${NC}"
        echo ""
        cat "$OUTPUT_FILE"
    fi
else
    echo -e "${RED}❌ Анализ завершен с ошибками${NC}"
    echo ""
    echo "Обнаружены критические проблемы!"
    echo "Подробности в: $OUTPUT_FILE"
fi

exit $EXIT_CODE
