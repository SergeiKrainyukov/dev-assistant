package assistant

class MyTest {
    val password = "secret123"

    fun getUser(id: String) = "SELECT * FROM users WHERE id = " + id

    fun process() {
        try {
            var d = 1 / 0
        } catch (e: Exception) {
        }
    }

    fun debug() {
        println("Debug message")
    }
}
