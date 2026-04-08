package com.example.wakewordassistant

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class GeminiClient(
    private val apiKey: String,
    private val client: OkHttpClient = OkHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    suspend fun ask(command: String): AssistantAction {
        require(apiKey.isNotBlank()) { "Gemini API key is empty. Fill BuildConfig.GEMINI_API_KEY." }

        val instruction = """
            Ты управляешь Android ассистентом.
            Верни только JSON одного из видов:
            {"action":"play_music","query":"Артист - Трек"}
            или
            {"action":"speak","response":"текст для озвучки"}
            Никакого markdown, комментариев, лишних полей и пояснений.
        """.trimIndent()

        val body = GeminiRequest(
            systemInstruction = SystemInstruction(parts = listOf(Part(instruction))),
            contents = listOf(Content(parts = listOf(Part(command)))),
            generationConfig = GenerationConfig(responseMimeType = "application/json")
        )

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=$apiKey")
            .post(json.encodeToString(GeminiRequest.serializer(), body).toRequestBody("application/json".toMediaType()))
            .build()

        val responseBody = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Gemini request failed with code ${response.code}")
            }
            response.body?.string().orEmpty()
        }

        val geminiResponse = json.decodeFromString(GeminiResponse.serializer(), responseBody)
        val textJson = geminiResponse.candidates
            .firstOrNull()
            ?.content
            ?.parts
            ?.firstOrNull()
            ?.text
            ?.trim()
            ?: throw IOException("Gemini returned empty candidate text")

        return json.decodeFromString(AssistantAction.serializer(), textJson)
    }
}

@Serializable
data class GeminiRequest(
    @SerialName("system_instruction")
    val systemInstruction: SystemInstruction,
    val contents: List<Content>,
    @SerialName("generationConfig")
    val generationConfig: GenerationConfig
)

@Serializable
data class SystemInstruction(val parts: List<Part>)

@Serializable
data class Content(val parts: List<Part>)

@Serializable
data class Part(val text: String)

@Serializable
data class GenerationConfig(
    @SerialName("responseMimeType")
    val responseMimeType: String
)

@Serializable
data class GeminiResponse(val candidates: List<Candidate> = emptyList())

@Serializable
data class Candidate(val content: CandidateContent)

@Serializable
data class CandidateContent(val parts: List<Part>)

@Serializable
data class AssistantAction(
    val action: String,
    val query: String? = null,
    val response: String? = null
)
