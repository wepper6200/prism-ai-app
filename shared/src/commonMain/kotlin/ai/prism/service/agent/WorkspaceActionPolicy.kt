package ai.prism.service.agent

/** Only the original user's request can require a write, never retrieved file/tool contents. */
internal object WorkspaceActionPolicy {
    private val continuation = Regex("^(пожалуйста[, ]*)?(продолжай|продолжи|делай|давай|да|continue|go ahead)[.! ]*$")
    private val explanation = Regex("(?i)^\\s*(пожалуйста[, ]*|please[, ]*)?(как\\s|how\\s|объясни|расскажи|покажи|explain|show\\b|напиши\\s+(ответ|стих|шутку|сообщение))")
    private val noChanges = Regex("(?i)(не\\s+(меняй|изменяй|правь|пиши|создавай)|do not|don't|без\\s+(правок|изменений|записи))")
    // Explicit Unicode boundaries: JVM's default \\b treats Cyrillic differently across JDK versions.
    private val action = Regex("(?i)((?<![\\p{L}\\p{N}_])(создай|создайте|создать|напиши|напишите|написать|исправь|исправьте|исправить|пофикси|пофиксь|поправь|измени|измените|изменить|поменяй|замени|отредактируй|добавь|добавьте|добавить|реализуй|сохрани|перепиши|доработай|create|write|edit|modify|fix|implement|save|replace|update|add)(?![\\p{L}\\p{N}_])|сделай\\s+(правк|изменени|файл|программ|игр|сайт))")

    fun requiresWrite(request: String, previousRequests: List<String> = emptyList()): Boolean {
        if (continuation.matches(request.lowercase().trim())) {
            val previous = previousRequests.asReversed().firstOrNull { !continuation.matches(it.lowercase().trim()) }
            return previous != null && requiresWrite(previous)
        }
        val instruction = request.lowercase().substringBefore("```").lineSequence()
            .filterNot { it.trimStart().startsWith(">") }.joinToString("\n")
        return !explanation.containsMatchIn(instruction) && !noChanges.containsMatchIn(instruction) && action.containsMatchIn(instruction)
    }
}
