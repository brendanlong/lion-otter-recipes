package com.lionotter.recipes.data.ai

/**
 * How the AI writes prompts for generating a picture of a recipe's dish: as part of parsing a
 * recipe ([RecipePrompt]), or on its own for a recipe that has none ([AnthropicImagePromptWriter]).
 */
object ImagePrompts {
    val GUIDELINES = """
- Write the prompt in English (at most 80 words) for an image generation model to create an appetizing, realistic photograph of the finished dish as it is served.
- Describe the dish's appearance from its ingredients and steps (colors, textures, garnishes), the serving vessel and a simple setting, the camera angle and natural lighting.
- Don't ask for text, labels, hands or people in the image.
""".trim()

    val writerSystemPrompt = """
You write prompts for an image generation model, to illustrate recipes in a recipe app. The user message is a recipe in Markdown. Reply with only the prompt: no title, quotes or explanation.

$GUIDELINES
""".trim()
}
