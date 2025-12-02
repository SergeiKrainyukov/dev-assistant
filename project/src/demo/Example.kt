package demo

import java.sql.Connection

/**
 * Демонстрационный класс с различными проблемами для тестирования PR анализатора.
 */
class Example {

    // ❌ ПРОБЛЕМА: Пароль в открытом виде
    private val password = "admin123"

    // ❌ ПРОБЛЕМА: API ключ в открытом виде
    private val apiKey = "sk-abc123xyz789"

    /**
     * ❌ ПРОБЛЕМА: SQL injection уязвимость
     */
    fun getUserById(userId: String, connection: Connection): String {
        val query = "SELECT * FROM users WHERE id = " + userId
        val result = connection.createStatement().executeQuery(query)
        return if (result.next()) result.getString("name") else ""
    }

    /**
     * ❌ ПРОБЛЕМА: Пустой catch блок
     */
    fun processData(data: String) {
        try {
            val result = data.toInt()
            println("Result: $result")
        } catch (e: Exception) {
            // TODO: Обработать ошибку
        }
    }

    /**
     * ✅ Хороший пример с документацией
     */
    fun calculateSum(a: Int, b: Int): Int {
        return a + b
    }

    // ❌ ПРОБЛЕМА: Публичная функция без документации
    fun publicFunction(input: String): String {
        return input.uppercase()
    }
}
