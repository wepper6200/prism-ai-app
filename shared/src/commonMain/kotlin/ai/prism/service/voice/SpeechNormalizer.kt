package ai.prism.service.voice

/**
 * Подготовка текста к русской озвучке: модели синтеза плохо читают латиницу и знаки. Известные термины
 * («Android», «GitHub») заменяются русским произношением, аббревиатуры латиницей («API») читаются по буквам
 * («эй-пи-ай»), проценты и градусы — словами с нужным окончанием. Для других языков текст не меняется.
 */
object SpeechNormalizer {

    fun normalize(text: String, language: String): String {
        if (language != "ru") return text
        var result = text
        TERMS.forEach { (pattern, spoken) -> result = pattern.replace(result, spoken) }
        result = ACRONYM.replace(result) { match -> spell(match.value) }
        result = PERCENT.replace(result) { match -> match.groupValues[1] + " " + plural(match.groupValues[1], "процент", "процента", "процентов") }
        result = DEGREES.replace(result) { match ->
            match.groupValues[1] + " " + plural(match.groupValues[1], "градус", "градуса", "градусов") +
                if (match.groupValues[2].isNotEmpty()) " по Цельсию" else ""
        }
        result = result.replace("№", "номер ").replace(" & ", " и ").replace(PLUS_NUMBER) { "плюс " + it.groupValues[1] }
        return result.replace(SPACES, " ").trim()
    }

    /** «API» → «эй-пи-ай». */
    private fun spell(acronym: String): String =
        acronym.map { letter -> LETTERS[letter.uppercaseChar()] ?: letter.toString() }.joinToString("-")

    /** Окончание для целого числа [number]: 1 процент, 2 процента, 5 процентов (дробные — как «процента»). */
    private fun plural(number: String, one: String, few: String, many: String): String {
        if (number.contains(',') || number.contains('.')) return few
        val n = number.toLongOrNull() ?: return many
        val lastTwo = n % 100
        val last = n % 10
        return when {
            lastTwo in 11..14 -> many
            last == 1L -> one
            last in 2..4 -> few
            else -> many
        }
    }

    private fun word(term: String) = Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(term) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    /** Длинные термины раньше коротких: «Prism AI» до «AI», «JavaScript» до «Java». */
    private val TERMS: List<Pair<Regex, String>> = listOf(
        "Prism AI" to "Призм эй-ай",
        "ChatGPT" to "чат-джи-пи-ти",
        "OpenAI" to "оупен-эй-ай",
        "JavaScript" to "джаваскрипт",
        "TypeScript" to "тайпскрипт",
        "Android" to "андроид",
        "iPhone" to "айфон",
        "iOS" to "ай-о-эс",
        "macOS" to "мак-о-эс",
        "Windows" to "виндоус",
        "Linux" to "линукс",
        "Google" to "гугл",
        "YouTube" to "ютуб",
        "GitHub" to "гитхаб",
        "Python" to "пайтон",
        "Kotlin" to "котлин",
        "Java" to "джава",
        "Gradle" to "грейдл",
        "Wi-Fi" to "вай-фай",
        "WiFi" to "вай-фай",
        "Bluetooth" to "блютуз",
        "Gemma" to "джемма",
        "Whisper" to "виспер",
        "Telegram" to "телеграм",
        "e-mail" to "имейл",
        "email" to "имейл",
        "online" to "онлайн",
        "offline" to "офлайн",
        "OK" to "окей",
    ).map { (term, spoken) -> word(term) to spoken }

    /** Аббревиатура латиницей: 2–6 заглавных букв (с цифрами), отдельное слово. */
    private val ACRONYM = Regex("(?<![\\p{L}\\p{N}])[A-Z][A-Z0-9]{1,5}(?![\\p{L}\\p{N}])")
    private val PERCENT = Regex("(\\d+(?:[.,]\\d+)?)\\s*%")
    private val DEGREES = Regex("(\\d+(?:[.,]\\d+)?)\\s*°\\s*(C|С)?")
    private val PLUS_NUMBER = Regex("(?<![\\p{L}\\p{N}])\\+(\\d)")
    private val SPACES = Regex("[ \\t]+")

    /** Английские буквы по-русски. */
    private val LETTERS = mapOf(
        'A' to "эй", 'B' to "би", 'C' to "си", 'D' to "ди", 'E' to "и", 'F' to "эф", 'G' to "джи", 'H' to "эйч",
        'I' to "ай", 'J' to "джей", 'K' to "кей", 'L' to "эл", 'M' to "эм", 'N' to "эн", 'O' to "оу", 'P' to "пи",
        'Q' to "кью", 'R' to "ар", 'S' to "эс", 'T' to "ти", 'U' to "ю", 'V' to "ви", 'W' to "дабл-ю", 'X' to "экс",
        'Y' to "уай", 'Z' to "зед",
        '0' to "ноль", '1' to "один", '2' to "два", '3' to "три", '4' to "четыре", '5' to "пять", '6' to "шесть",
        '7' to "семь", '8' to "восемь", '9' to "девять",
    )
}
