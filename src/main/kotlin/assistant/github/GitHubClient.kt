package assistant.github

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Клиент для работы с GitHub API.
 * Позволяет получать информацию о PR, файлы и diff.
 */
class GitHubClient(private val token: String? = null) {

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    private val baseUrl = "https://api.github.com"

    /**
     * Парсит ссылку на PR и извлекает owner, repo и номер PR.
     * Поддерживает форматы:
     * - https://github.com/owner/repo/pull/123
     * - owner/repo/pull/123
     * - owner/repo#123
     */
    fun parsePrUrl(url: String): PrReference? {
        // Формат: https://github.com/owner/repo/pull/123
        val fullUrlPattern = Regex("""github\.com/([^/]+)/([^/]+)/pull/(\d+)""")
        fullUrlPattern.find(url)?.let { match ->
            return PrReference(
                owner = match.groupValues[1],
                repo = match.groupValues[2],
                number = match.groupValues[3].toInt()
            )
        }

        // Формат: owner/repo#123
        val shortPattern = Regex("""([^/]+)/([^/#]+)#(\d+)""")
        shortPattern.find(url)?.let { match ->
            return PrReference(
                owner = match.groupValues[1],
                repo = match.groupValues[2],
                number = match.groupValues[3].toInt()
            )
        }

        return null
    }

    /**
     * Получает информацию о Pull Request.
     */
    suspend fun getPullRequest(ref: PrReference): PullRequestInfo {
        val response = client.get("$baseUrl/repos/${ref.owner}/${ref.repo}/pulls/${ref.number}") {
            configureAuth()
            accept(ContentType.Application.Json)
        }

        if (response.status != HttpStatusCode.OK) {
            throw GitHubApiException("Failed to get PR info: ${response.status}")
        }

        return response.body()
    }

    /**
     * Получает список измененных файлов в PR.
     */
    suspend fun getPullRequestFiles(ref: PrReference): List<PrFile> {
        val response = client.get("$baseUrl/repos/${ref.owner}/${ref.repo}/pulls/${ref.number}/files") {
            configureAuth()
            accept(ContentType.Application.Json)
            parameter("per_page", 100)
        }

        if (response.status != HttpStatusCode.OK) {
            throw GitHubApiException("Failed to get PR files: ${response.status}")
        }

        return response.body()
    }

    /**
     * Получает diff для PR в текстовом формате.
     */
    suspend fun getPullRequestDiff(ref: PrReference): String {
        val response = client.get("$baseUrl/repos/${ref.owner}/${ref.repo}/pulls/${ref.number}") {
            configureAuth()
            accept(ContentType("application", "vnd.github.v3.diff"))
        }

        if (response.status != HttpStatusCode.OK) {
            throw GitHubApiException("Failed to get PR diff: ${response.status}")
        }

        return response.body()
    }

    /**
     * Получает содержимое файла из репозитория.
     */
    suspend fun getFileContent(owner: String, repo: String, path: String, ref: String = "main"): String {
        val response = client.get("$baseUrl/repos/$owner/$repo/contents/$path") {
            configureAuth()
            accept(ContentType.Application.Json)
            parameter("ref", ref)
        }

        if (response.status != HttpStatusCode.OK) {
            throw GitHubApiException("Failed to get file content: ${response.status}")
        }

        val fileResponse: GitHubFileContent = response.body()

        // Декодируем base64 контент
        return if (fileResponse.encoding == "base64") {
            java.util.Base64.getDecoder().decode(fileResponse.content.replace("\n", ""))
                .toString(Charsets.UTF_8)
        } else {
            fileResponse.content
        }
    }

    /**
     * Получает комментарии к PR.
     */
    suspend fun getPullRequestComments(ref: PrReference): List<PrComment> {
        val response = client.get("$baseUrl/repos/${ref.owner}/${ref.repo}/pulls/${ref.number}/comments") {
            configureAuth()
            accept(ContentType.Application.Json)
        }

        if (response.status != HttpStatusCode.OK) {
            throw GitHubApiException("Failed to get PR comments: ${response.status}")
        }

        return response.body()
    }

    private fun HttpRequestBuilder.configureAuth() {
        token?.let {
            header("Authorization", "token $it")
        }
        header("User-Agent", "DevAssistant-PR-Analyzer")
    }

    fun close() {
        client.close()
    }
}

/**
 * Ссылка на Pull Request.
 */
data class PrReference(
    val owner: String,
    val repo: String,
    val number: Int
) {
    override fun toString() = "$owner/$repo#$number"
}

/**
 * Информация о Pull Request.
 */
@Serializable
data class PullRequestInfo(
    val number: Int,
    val title: String,
    val body: String? = null,
    val state: String,
    val html_url: String,
    val diff_url: String,
    val user: GitHubUser,
    val head: GitHubBranch,
    val base: GitHubBranch,
    val changed_files: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    val mergeable: Boolean? = null,
    val created_at: String,
    val updated_at: String
)

@Serializable
data class GitHubUser(
    val login: String,
    val id: Long,
    val avatar_url: String? = null
)

@Serializable
data class GitHubBranch(
    val ref: String,
    val sha: String,
    val repo: GitHubRepo? = null
)

@Serializable
data class GitHubRepo(
    val name: String,
    val full_name: String,
    val html_url: String? = null
)

/**
 * Файл в Pull Request.
 */
@Serializable
data class PrFile(
    val sha: String,
    val filename: String,
    val status: String,  // added, removed, modified, renamed
    val additions: Int,
    val deletions: Int,
    val changes: Int,
    val patch: String? = null,  // diff для файла
    val contents_url: String? = null,
    val raw_url: String? = null,
    val previous_filename: String? = null
)

/**
 * Комментарий к PR.
 */
@Serializable
data class PrComment(
    val id: Long,
    val body: String,
    val user: GitHubUser,
    val path: String? = null,
    val line: Int? = null,
    val created_at: String
)

/**
 * Содержимое файла из GitHub.
 */
@Serializable
data class GitHubFileContent(
    val name: String,
    val path: String,
    val sha: String,
    val content: String,
    val encoding: String
)

class GitHubApiException(message: String) : Exception(message)
