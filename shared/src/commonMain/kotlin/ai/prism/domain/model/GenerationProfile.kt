package ai.prism.domain.model

import ai.prism.domain.inference.SamplingParams

enum class GenerationProfile(val sampling: SamplingParams, val defaultContext: Int, val maxTokens: Int) {
    STANDARD(SamplingParams(), 0, 2048),
    CODING(SamplingParams(temperature = 0.2f, topP = 0.9f, repeatPenalty = 1.05f), 8192, 4096),
    CONVERSATION(SamplingParams(temperature = 0.7f, topP = 0.95f), 4096, 1536),
    CREATIVE(SamplingParams(temperature = 0.95f, topP = 0.98f, repeatPenalty = 1.05f), 8192, 4096),
}
