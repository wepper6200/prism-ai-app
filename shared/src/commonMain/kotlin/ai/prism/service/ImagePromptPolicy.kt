package ai.prism.service

/**
 * Правила для описаний картинок: распознать отказ модели вместо перевода и явно запрещённые слова
 * для безопасного режима. Это первая, грубая ступень проверки — дальше её дополняет модель чата.
 */
object ImagePromptPolicy {

    /** Ответ модели безопасной проверки, если описание нельзя рисовать. */
    const val BLOCKED_MARKER = "BLOCKED"

    private val refusalStarts = listOf(
        "i can't", "i cannot", "i can not", "i won't", "i will not", "i'm sorry", "i am sorry", "sorry", "i'm unable",
        "i am unable", "as an ai", "unfortunately",
        "я не могу", "не могу", "извините", "простите", "к сожалению", "я не буду",
    )

    /** Слова, с которых начинаются запрещённые в безопасном режиме слова (русские и английские). */
    private val blockedPrefixes = listOf(
        "nude", "naked", "nsfw", "porn", "sex", "erotic", "hentai", "topless", "gore", "decapitat", "dismember",
        "обнаж", "порн", "секс", "эрот", "расчлен", "обезглав", "кровав", "кровищ",
    )

    /** Слова целиком: их начало слишком общее (кровь — кровать, голый — голубой). */
    private val blockedWords = setOf(
        "blood", "кровь", "крови", "кровью", "голая", "голый", "голые", "голую", "голого", "голой", "голышом",
        "нагая", "нагой", "нагие",
    )

    fun hasCyrillic(text: String): Boolean = text.any { it in 'Ѐ'..'ӿ' }

    /** Ответ похож на отказ модели («I can't…», «Извините…»), а не на перевод. */
    fun isRefusal(text: String): Boolean {
        val start = text.trim().trimStart('"', '«', '*').lowercase()
        return refusalStarts.any { start.startsWith(it) }
    }

    /**
     * [translated] годится как английское описание для генератора: не пустое, не отказ,
     * в основном латиницей и не раздуто по сравнению с [source].
     */
    fun isUsableTranslation(source: String, translated: String): Boolean {
        val text = translated.trim()
        if (text.isEmpty() || isRefusal(text) || text.equals(BLOCKED_MARKER, ignoreCase = true)) return false
        val letters = text.count { it.isLetter() }
        val cyrillic = text.count { it in 'Ѐ'..'ӿ' }
        if (letters == 0 || cyrillic * 10 > letters * 3) return false
        return text.length <= source.length * 6 + 200
    }

    /** Ответ безопасной проверки означает запрет. */
    fun isBlockedVerdict(text: String): Boolean = text.trim().trim('"', '.', '*').uppercase().startsWith(BLOCKED_MARKER)

    /** В описании есть явно запрещённые для безопасного режима слова. */
    fun containsBlockedWords(text: String): Boolean = text.lowercase()
        .split(Regex("[^\\p{L}]+"))
        .any { word -> word in blockedWords || blockedPrefixes.any { word.startsWith(it) } }

    /** Ответ проверки готовой картинки означает «есть недопустимое». */
    fun isUnsafeReview(text: String): Boolean {
        val answer = text.trim().trim('"', '.', '*').uppercase()
        return answer.startsWith("YES") || answer.startsWith("ДА")
    }
}
