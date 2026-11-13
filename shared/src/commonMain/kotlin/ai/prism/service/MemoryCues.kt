package ai.prism.service

/**
 * Когда стоит спрашивать модель о фактах из сообщения: только если пользователь говорит о себе или просит запомнить.
 * Так лишняя генерация не идёт после каждого ответа (на телефоне это заметно).
 */
object MemoryCues {
    // Не \b: в Java он видит границы слов только у латиницы, и «Запомни» в начале фразы не находится.
    private val cues = Regex(
        "(?iu)(?<![\\p{L}\\d_])(меня зовут|моё имя|мое имя|зови меня|запомни|я живу|я работаю|я учусь|я люблю|я не люблю|" +
            "я предпочитаю|мне \\d+|у меня есть|я занимаюсь|мой |моя |моё |мои |я программист|я разработчик|" +
            "my name|call me|remember|i live|i work|i like|i love|i prefer|i'm a|i am a)",
    )

    fun mayContainFacts(text: String): Boolean = cues.containsMatchIn(text)

    /** Факты из ответа модели: строки «- …»; «нет» и мусор отбрасываются, не больше [MAX_FACTS]. */
    fun parseFacts(answer: String): List<String> = answer.lines()
        .map { it.trim().trimStart('-', '•', '*', ' ').trim() }
        .filter { it.length in 3..MAX_FACT_LENGTH }
        .filterNot { it.lowercase().trimEnd('.', '!') in NOTHING }
        .distinct()
        .take(MAX_FACTS)

    private val NOTHING = setOf("нет", "ничего", "фактов нет", "none", "no", "nothing")
    private const val MAX_FACTS = 5
    private const val MAX_FACT_LENGTH = 200
}
