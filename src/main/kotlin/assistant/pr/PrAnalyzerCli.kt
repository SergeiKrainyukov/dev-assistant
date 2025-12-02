package assistant.pr

import assistant.Config
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * CLI для анализа Pull Request.
 *
 * Использование:
 * - Анализ текущей ветки vs main: ./gradlew analyzePr
 * - Анализ с параметрами: ./gradlew analyzePr -Pbase=main -Phead=feature-branch
 * - Анализ конкретного PR: ./gradlew analyzePr -Ppr=123
 */
fun main(args: Array<String>): Unit = runBlocking {
    println("🤖 DevAssistant - PR Analyzer")
    println("=" .repeat(60))
    println()

    // Парсим аргументы
    val argsMap = parseArgs(args)
    val config = loadConfig()

    val prNumber = argsMap["pr"]?.toIntOrNull()
    val baseBranch = argsMap["base"] ?: "main"
    val headBranch = argsMap["head"]
    val outputFormat = argsMap["format"] ?: "text" // text or json
    val outputFile = argsMap["output"]

    // Создаем анализатор
    val analyzer = PrAnalyzer(config)

    try {
        // Инициализируем
        analyzer.initialize()

        // Анализируем
        val result = if (prNumber != null) {
            println("📊 Анализируем PR #$prNumber...")
            analyzer.analyzePr(prNumber = prNumber)
        } else {
            val head = headBranch ?: getCurrentBranch()
            println("📊 Анализируем изменения: $baseBranch...$head")
            analyzer.analyzePr(baseBranch = baseBranch, headBranch = head)
        }

        // Форматируем вывод
        val output = when (outputFormat) {
            "json" -> formatAsJson(result)
            else -> result.formatAsComment()
        }

        // Выводим результат
        if (outputFile != null) {
            File(outputFile).writeText(output)
            println()
            println("✅ Результат сохранен в $outputFile")
        } else {
            println()
            println(output)
        }

        // Возвращаем exit code в зависимости от результата
        val exitCode = when {
            result.issues.any { it.severity == IssueSeverity.HIGH } -> 1
            result.issues.any { it.severity == IssueSeverity.MEDIUM } -> 0
            else -> 0
        }

        if (exitCode != 0) {
            println()
            println("⚠️ Обнаружены критические проблемы!")
        }

        // В CI режиме не блокируем выполнение
        val ciMode = System.getenv("CI") != null || argsMap["no-fail"] != null
        val finalExitCode = if (ciMode) 0 else exitCode

        kotlin.system.exitProcess(finalExitCode)

    } catch (e: Exception) {
        println()
        println("❌ Ошибка: ${e.message}")
        e.printStackTrace()
        kotlin.system.exitProcess(1)
    } finally {
        analyzer.close()
    }
}

/**
 * Парсит аргументы командной строки.
 */
private fun parseArgs(args: Array<String>): Map<String, String> {
    val result = mutableMapOf<String, String>()

    for (arg in args) {
        when {
            arg.startsWith("-Ppr=") -> result["pr"] = arg.substringAfter("=")
            arg.startsWith("-Pbase=") -> result["base"] = arg.substringAfter("=")
            arg.startsWith("-Phead=") -> result["head"] = arg.substringAfter("=")
            arg.startsWith("-Pformat=") -> result["format"] = arg.substringAfter("=")
            arg.startsWith("-Poutput=") -> result["output"] = arg.substringAfter("=")
            arg.startsWith("--pr=") -> result["pr"] = arg.substringAfter("=")
            arg.startsWith("--base=") -> result["base"] = arg.substringAfter("=")
            arg.startsWith("--head=") -> result["head"] = arg.substringAfter("=")
            arg.startsWith("--format=") -> result["format"] = arg.substringAfter("=")
            arg.startsWith("--output=") -> result["output"] = arg.substringAfter("=")
        }
    }

    return result
}

/**
 * Загружает конфигурацию.
 */
private fun loadConfig(): Config {
    val configFile = File("config.json")
    return if (configFile.exists()) {
        try {
            val json = Json { ignoreUnknownKeys = true }
            json.decodeFromString(configFile.readText())
        } catch (e: Exception) {
            println("⚠️ Не удалось загрузить config.json, используем настройки по умолчанию")
            Config.default()
        }
    } else {
        Config.default()
    }
}

/**
 * Получает текущую ветку.
 */
private fun getCurrentBranch(): String {
    return try {
        val process = ProcessBuilder("git", "branch", "--show-current")
            .redirectErrorStream(true)
            .start()

        val branch = process.inputStream.bufferedReader().readText().trim()
        process.waitFor()
        branch
    } catch (e: Exception) {
        "HEAD"
    }
}

/**
 * Форматирует результат в JSON.
 */
private fun formatAsJson(result: PrAnalysisResult): String {
    val json = Json { prettyPrint = true }
    return json.encodeToString(result)
}
