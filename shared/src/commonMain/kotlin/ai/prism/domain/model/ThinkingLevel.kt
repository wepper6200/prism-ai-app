package ai.prism.domain.model

/**
 * Насколько долго модель рассуждает перед ответом. Рассуждения скрыты (в чате — «думает…»), в историю попадает
 * только ответ. Работает у моделей, обученных рассуждать (форматы Gemma 4 и Qwen); у остальных не влияет.
 *
 * @param budgetTokens сколько токенов рассуждений разрешено; дальше рассуждение закрывается и модель отвечает.
 */
enum class ThinkingLevel(val budgetTokens: Int) {
    OFF(0),
    LOW(1_024),
    MEDIUM(4_096),
    HIGH(16_384),
    ;

    companion object {
        /** Модель этого формата умеет рассуждать перед ответом. */
        fun supports(format: ChatFormat): Boolean = format == ChatFormat.GEMMA4 || format == ChatFormat.QWEN
    }
}
