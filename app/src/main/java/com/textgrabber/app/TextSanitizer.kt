package com.textgrabber.app

/**
 * Universal matn tozalash — TTS uchun.
 * Harf, raqam, whitespace va foydali punctuation qoldiriladi.
 * Emoji, bayroq, boshqa maxsus belgilar tashlanadi.
 */
object TextSanitizer {
    fun sanitize(text: String): String {
        return try {
            val sb = StringBuilder()
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val cc = Character.charCount(cp)
                val keep = when {
                    Character.isLetterOrDigit(cp) -> true
                    Character.isWhitespace(cp) -> true
                    cp == '.'.code || cp == ','.code || cp == '!'.code || cp == '?'.code ||
                    cp == ':'.code || cp == ';'.code || cp == '-'.code || cp == '_'.code ||
                    cp == '/'.code || cp == '('.code || cp == ')'.code ||
                    cp == '['.code || cp == ']'.code || cp == '{'.code || cp == '}'.code ||
                    cp == '"'.code || cp == '\''.code || cp == '«'.code || cp == '»'.code ||
                    cp == '\u2018'.code || cp == '\u2019'.code ||
                    cp == '\u201C'.code || cp == '\u201D'.code ||
                    cp == '\u2014'.code || cp == '\u2013'.code ||
                    cp == '%'.code || cp == '+'.code || cp == '='.code ||
                    cp == '&'.code || cp == '@'.code || cp == '#'.code || cp == '*'.code -> true
                    cp == 0x02BB || cp == 0x02BC || cp == 0x2018 || cp == 0x2019 -> true
                    else -> false
                }
                if (keep) sb.appendCodePoint(cp)
                i += cc
            }
            var result = sb.toString().replace(Regex("\\s+"), " ").trim()
            
            // Sayt manzillari (Kun.uz, google.com) va +2 teglarini olib tashlash
            result = result.replace(Regex("""\b[a-z0-9.-]+\.(com|uz|ru|org|net|io|gov|edu)\b""", RegexOption.IGNORE_CASE), "")
            result = result.replace(Regex("""\+\d{1,2}\b"""), "")
            
            // Ortiqcha bo'shliqlarni tozalash
            result = result.replace(Regex("\\s+"), " ").trim()
            result
        } catch (e: Throwable) {
            android.util.Log.e("TextSanitizer", "sanitize error", e)
            text
        }
    }

    /**
     * O'zbek kirill alifbosini lotin alifbosiga o'girish.
     * Edge TTS (Madina) faqat lotinni tushungani uchun kerak.
     */
    fun cyrillicToLatinUz(input: String): String {
        var text = input
        // O'zbek kirilliga xos maxsus harflar (birinchi navbatda almashtiriladi)
        text = text.replace("Ў", "O'").replace("ў", "o'")
        text = text.replace("Қ", "Q").replace("қ", "q")
        text = text.replace("Ғ", "G'").replace("ғ", "g'")
        text = text.replace("Ҳ", "H").replace("ҳ", "h")

        // Digraflar
        text = text.replace("Ё", "Yo").replace("ё", "yo")
        text = text.replace("Ю", "Yu").replace("ю", "yu")
        text = text.replace("Я", "Ya").replace("я", "ya")
        text = text.replace("Ч", "Ch").replace("ч", "ch")
        text = text.replace("Ш", "Sh").replace("ш", "sh")
        text = text.replace("Щ", "Shch").replace("щ", "shch")
        text = text.replace("Ж", "J").replace("ж", "j")
        text = text.replace("Ц", "Ts").replace("ц", "ts")

        // Tutuq belgilari (o'zbekchada ishlatilmaydi, o'chiriladi)
        text = text.replace("Ъ", "'").replace("ъ", "'")
        text = text.replace("Ь", "").replace("ь", "")

        // Qolgan oddiy harflar
        text = text.replace("А", "A").replace("а", "a")
        text = text.replace("Б", "B").replace("б", "b")
        text = text.replace("В", "V").replace("в", "v")
        text = text.replace("Г", "G").replace("г", "g")
        text = text.replace("Д", "D").replace("д", "d")
        text = text.replace("Е", "E").replace("е", "e")
        text = text.replace("З", "Z").replace("з", "z")
        text = text.replace("И", "I").replace("и", "i")
        text = text.replace("Й", "Y").replace("й", "y")
        text = text.replace("К", "K").replace("к", "k")
        text = text.replace("Л", "L").replace("л", "l")
        text = text.replace("М", "M").replace("м", "m")
        text = text.replace("Н", "N").replace("н", "n")
        text = text.replace("О", "O").replace("о", "o")
        text = text.replace("П", "P").replace("п", "p")
        text = text.replace("Р", "R").replace("р", "r")
        text = text.replace("С", "S").replace("с", "s")
        text = text.replace("Т", "T").replace("т", "t")
        text = text.replace("У", "U").replace("у", "u")
        text = text.replace("Ф", "F").replace("ф", "f")
        text = text.replace("Х", "X").replace("х", "x")
        text = text.replace("Ы", "I").replace("ы", "i")
        text = text.replace("Э", "E").replace("э", "e")

        return text
    }


    /**
     * "1-variant" -> "birinchi variant", "2-sinf" -> "ikkinchi sinf"
     * lang: "uz" yoki "ru"
     */
    fun convertOrdinals(input: String, lang: String): String {
        val uzMap = mapOf(
            "1" to "birinchi", "2" to "ikkinchi", "3" to "uchinchi",
            "4" to "to'rtinchi", "5" to "beshinchi", "6" to "oltinchi",
            "7" to "yettinchi", "8" to "sakkizinchi", "9" to "to'qqizinchi",
            "10" to "o'ninchi", "11" to "o'n birinchi", "12" to "o'n ikkinchi",
            "13" to "o'n uchinchi", "14" to "o'n to'rtinchi", "15" to "o'n beshinchi",
            "16" to "o'n oltinchi", "17" to "o'n yettinchi", "18" to "o'n sakkizinchi",
            "19" to "o'n to'qqizinchi", "20" to "yigirmanchi"
        )
        val ruMap = mapOf(
            "1" to "первый", "2" to "второй", "3" to "третий",
            "4" to "четвёртый", "5" to "пятый", "6" to "шестой",
            "7" to "седьмой", "8" to "восьмой", "9" to "девятый",
            "10" to "десятый", "11" to "одиннадцатый", "12" to "двенадцатый",
            "13" to "тринадцатый", "14" to "четырнадцатый", "15" to "пятнадцатый",
            "16" to "шестнадцатый", "17" to "семнадцатый", "18" to "восемнадцатый",
            "19" to "девятнадцатый", "20" to "двадцатый"
        )
        val enMap = mapOf(
            "1" to "first", "2" to "second", "3" to "third",
            "4" to "fourth", "5" to "fifth", "6" to "sixth",
            "7" to "seventh", "8" to "eighth", "9" to "ninth",
            "10" to "tenth", "11" to "eleventh", "12" to "twelfth",
            "13" to "thirteenth", "14" to "fourteenth", "15" to "fifteenth",
            "16" to "sixteenth", "17" to "seventeenth", "18" to "eighteenth",
            "19" to "nineteenth", "20" to "twentieth"
        )
        val map = when (lang) { "ru" -> ruMap; "en" -> enMap; else -> uzMap }
        val suffix = when (lang) { "ru" -> "-й"; "en" -> "th"; else -> "-inchi" }

        val whitelist = "sinf|variant|bob|qism|bet|sahifa|dars|mavzu|misol|masala|sinif|" +
                        "класс|вариант|глава|часть|страница|урок|пример|" +
                        "class|grade|chapter|part|page|lesson|example"
        var result = Regex("""\b(\d{1,3})[-.\s]*($whitelist)\b""", RegexOption.IGNORE_CASE).replace(input) { m ->
            val num = m.groupValues[1]
            val word = m.groupValues[2]
            val ord = map[num] ?: "$num$suffix"
            "$ord $word"
        }

        // Faqat mustaqil 1-3 xonali sonlarni tekshiramiz (boshqa sonlarning bo'lagi emasligiga ishonch hosil qilamiz)
        result = Regex("""(?<!\d\s)\b(\d{1,3})[-.\s]+(\p{L}+)\b""", RegexOption.IGNORE_CASE).replace(result) { m ->
            val num = m.groupValues[1]
            val word = m.groupValues[2]
            val cleanWord = word.lowercase().replace(Regex("[^a-zа-я]"), "")
            
            // Valyuta va o'lchov birliklarining ertaga qarab tekshirish (сум, сумов, рублей, долларов)
            val skipStems = listOf(
                "so", "som", "sum", "сум", "сўм", "руб", "доллар", "долл", "dollar", "dollars", "usd", "eur",
                "евро", "euro", "тенге", "gb", "гб", "mb", "мб", "mp", "мп", "mah", "мач", "hz", "гц"
            )
            
            if (skipStems.any { cleanWord.startsWith(it) }) {
                m.value // Valyuta bo'lsa, hech narsa qo'shmaymiz
            } else {
                val ord = map[num] ?: "$num$suffix"
                "$ord $word"
            }
        }

        result = Regex("""(?<=^|[.!?]\s)(\d{1,3})[.)]\s""").replace(result) { m ->
            val num = m.groupValues[1]
            val ord = map[num] ?: "$num$suffix"
            "$ord. "
        }

        return result
    }


    /** Raqamlarni so'zga aylantirish: 0 -> nol, 5 -> besh, 100 -> bir yuz */
    fun convertDigits(input: String, lang: String): String {
        // 1. Raqamlar orasidagi bo'shliqlarni olib tashlash (16 500 000 -> 16500000)
        val normalized = input.replace(Regex("(?<=\\d)\\s+(?=\\d)"), "")
        
        return Regex("""\b\d+\b""").replace(normalized) { m ->
            val numStr = m.value
            val num = numStr.toLongOrNull() ?: return@replace numStr
            if (numStr.length > 1 && numStr.startsWith("0")) {
                // Boshidagi nollarni saqlash: "007" -> "nol nol yetti"
                numStr.map { d -> numToWord(d.digitToInt().toLong(), lang) }.joinToString(" ")
            } else if (numStr.length > 9) {
                // Uzun raqam (telefon, ID) -> raqamma-raqam
                numStr.map { d -> numToWord(d.digitToInt().toLong(), lang) }.joinToString(" ")
            } else {
                numToWord(num, lang)
            }
        }
    }

    private fun numToWord(n: Long, lang: String): String = when (lang) {
        "ru" -> numToRu(n)
        "en" -> numToEn(n)
        else -> numToUz(n)
    }

    private fun numToUz(n: Long): String {
        if (n == 0L) return "нол"  // Cyrillic — Madina to'g'ri o'qiydi
        if (n < 0) return "minus ${numToUz(-n)}"
        val ones = arrayOf("", "bir", "ikki", "uch", "to'rt", "besh", "olti", "yetti", "sakkiz", "to'qqiz")
        val tens = arrayOf("", "o'n", "yigirma", "o'ttiz", "qirq", "ellik", "oltmish", "yetmish", "sakson", "to'qson")
        return when {
            n < 10 -> ones[n.toInt()]
            n < 100 -> {
                val t = (n / 10).toInt(); val o = (n % 10).toInt()
                if (o == 0) tens[t] else "${tens[t]} ${ones[o]}"
            }
            n < 1000 -> {
                val h = (n / 100).toInt(); val rest = n % 100
                // "yuz" (100), "ikki yuz" (200), "bir yuz bir" (101)
                val hStr = if (h == 1 && rest == 0L) "yuz" else "${ones[h]} yuz"
                if (rest == 0L) hStr else "$hStr ${numToUz(rest)}"
            }
            n < 1_000_000 -> {
                val th = n / 1000; val rest = n % 1000
                // "ming" (1000), "ikki ming" (2000), "bir ming yuz" (1100)
                val thStr = if (th == 1L && rest == 0L) "ming" else "${numToUz(th)} ming"
                if (rest == 0L) thStr else "$thStr ${numToUz(rest)}"
            }
            n < 1_000_000_000L -> {
                val m = n / 1_000_000L; val rest = n % 1_000_000L
                val mStr = "${numToUz(m)} million"
                if (rest == 0L) mStr else "$mStr ${numToUz(rest)}"
            }
            else -> n.toString()
        }
    }

    private fun numToRu(n: Long): String {
        if (n == 0L) return "нол"
        if (n < 0) return "минус ${numToRu(-n)}"
        val ones = arrayOf("", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять",
            "десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать", "пятнадцать",
            "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать")
        val tens = arrayOf("", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят",
            "семьдесят", "восемьдесят", "девяносто")
        val hundreds = arrayOf("", "сто", "двести", "триста", "четыреста", "пятьсот",
            "шестьсот", "семьсот", "восемьсот", "девятьсот")
        return when {
            n < 20 -> ones[n.toInt()]
            n < 100 -> {
                val t = (n / 10).toInt(); val o = (n % 10).toInt()
                if (o == 0) tens[t] else "${tens[t]} ${ones[o]}"
            }
            n < 1000 -> {
                val h = (n / 100).toInt(); val rest = n % 100
                // "сто" (100), "двести" (200)
                val hStr = hundreds[h]
                if (rest == 0L) hStr else "$hStr ${numToRu(rest)}"
            }
            n < 1_000_000 -> {
                val th = n / 1000; val rest = n % 1000
                // "тысяча" (1000), "две тысячи" (2000), "пять тысяч" (5000)
                val thStr = if (th == 1L && rest == 0L) "тысяча" else "${numToRu(th)} тысяч"
                if (rest == 0L) thStr else "$thStr ${numToRu(rest)}"
            }
            n < 1_000_000_000L -> {
                val m = n / 1_000_000L; val rest = n % 1_000_000L
                val mStr = if (m == 1L && rest == 0L) "миллион" else "${numToRu(m)} миллионов"
                if (rest == 0L) mStr else "$mStr ${numToRu(rest)}"
            }
            else -> n.toString()
        }
    }

    private fun numToEn(n: Long): String {
        if (n == 0L) return "zero"
        if (n < 0) return "minus ${numToEn(-n)}"
        val ones = arrayOf("", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
            "sixteen", "seventeen", "eighteen", "nineteen")
        val tens = arrayOf("", "", "twenty", "thirty", "forty", "fifty", "sixty",
            "seventy", "eighty", "ninety")
        return when {
            n < 20 -> ones[n.toInt()]
            n < 100 -> {
                val t = (n / 10).toInt(); val o = (n % 10).toInt()
                if (o == 0) tens[t] else "${tens[t]}-${ones[o]}"
            }
            n < 1000 -> {
                val h = (n / 100).toInt(); val rest = n % 100
                val hStr = "${ones[h]} hundred"
                if (rest == 0L) hStr else "$hStr ${numToEn(rest)}"
            }
            n < 1_000_000 -> {
                val th = n / 1000; val rest = n % 1000
                val thStr = "${numToEn(th)} thousand"
                if (rest == 0L) thStr else "$thStr ${numToEn(rest)}"
            }
            else -> n.toString()
        }
    }

}
