package com.lionotter.recipes.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AnthropicRequest(
    val model: String,
    @SerialName("max_tokens")
    val maxTokens: Int,
    val messages: List<AnthropicMessage>,
    val system: String? = null
)

@Serializable
data class AnthropicMessage(
    val role: String,
    val content: String
)

@Serializable
data class AnthropicResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<AnthropicContent>,
    val model: String,
    @SerialName("stop_reason")
    val stopReason: String?,
    val usage: AnthropicUsage
)

@Serializable
data class AnthropicContent(
    val type: String,
    val text: String
)

@Serializable
data class AnthropicUsage(
    @SerialName("input_tokens")
    val inputTokens: Int,
    @SerialName("output_tokens")
    val outputTokens: Int
)

@Serializable
data class AnthropicError(
    val type: String,
    val error: AnthropicErrorDetail
)

@Serializable
data class AnthropicErrorDetail(
    val type: String,
    val message: String
)
