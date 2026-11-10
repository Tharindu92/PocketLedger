package com.billbook.app.data

/**
 * Suggests a category from a description (payee, booking text, bill text). Pure Kotlin.
 *
 * 1. What the user chose before for the same payee wins, so corrections are learnt.
 * 2. Otherwise keywords and well-known shop / company names (Sri Lankan and German).
 * 3. Money coming in from a shop or company that is normally an expense is a refund.
 */
object CategoryGuesser {

    private fun hints(vararg pairs: Pair<String, String>) =
        pairs.map { (category, pattern) -> category to Regex("""(?<![\p{L}\d])($pattern)(?![\p{L}\d])""", RegexOption.IGNORE_CASE) }

    // Order matters: the first match wins (e.g. "uber eats" is Dining before "uber" is Transport).
    private val EXPENSE = hints(
        // Before Bank Charges and the rest: "Sparplan … Gebühr" is still an investment.
        Categories.INVESTMENTS to "trade republic|scalable|scalable capital|traderepublic|degiro|flatex|comdirect|" +
            "consorsbank|onvista|smartbroker|justtrade|finanzen\\.net zero|interactive brokers|ibkr|etoro|" +
            "robinhood|vanguard|wertpapier|wertpapiere|wertpapierkauf|depot|sparplan|etf|etfs|aktie|aktien|" +
            "equities|stocks|shares|brokerage|investment|investments|invest|unit trust|cds|cse",
        // Money sent home. The payee is usually a person's name, so after the first time the
        // learnt choice for that name does most of the work.
        "Family Support" to "family|familie|mother|mom|mum|amma|ammi|thaththa|appachchi|father|dad|parents|" +
            "mutter|mama|papa|vater|eltern|remittance|remitly|western union|moneygram|worldremit|taptap send|" +
            "family support|unterhalt|taschengeld",
        "Sports & Fitness" to "fitness|gym|fitnessstudio|mcfit|fitx|clever fit|john reed|holmes place|urban sports|" +
            "urban sports club|urbansportsclub|egym|wellpass|sport|sports|sportverein|decathlon|intersport|" +
            "sportscheck|sport ?scheck|yoga|pilates|boulder|bouldering|kletterhalle|swimming|schwimmbad|" +
            "tennis|badminton|cricket|football|fussball|fußball|marathon|parkrun",
        // Cinema, concerts and shows; streaming services stay under subscriptions.
        "Entertainment" to "cinema|cinemas|kino|cinemaxx|cinestar|uci|yorck|cineplex|scope cinemas|savoy|" +
            "movie|movies|film|konzert|concert|eventim|ticketmaster|tixforgigs|dice|reservix|theater|theatre|" +
            "oper|opera|philharmonie|musical|festival|museum|zoo|tierpark|bowling|escape room|freizeitpark",
        "Gifts" to "gift|gifts|geschenk|geschenke|present|presents|blumen|florist|flowers|fleurop|" +
            "geburtstag|birthday|wedding gift|hochzeit",
        // Clothing before insurance so "TK Maxx" is never read as the TK health insurer.
        "Clothing" to "clothing|clothes|kleidung|fashion|schuhe|shoes|h&m|c&a|zara|primark|uniqlo|zalando|" +
            "about you|asos|tk maxx|peek & cloppenburg|p&c|new yorker|deichmann|snipes|bershka|pull&bear|" +
            "esprit|s\\.oliver|jack & jones|vero moda|nike|adidas|puma|odlo|jack wolfskin|" +
            "odel|nolimit|cool planet|fashion bug",
        "Insurance & Taxes" to "versicherung|versicherungen|insurance|allianz|huk|huk-coburg|huk24|axa|ergo|" +
            "generali|devk|r\\+v|signal iduna|debeka|cosmos direkt|check24|feather|getsafe|wgv|" +
            "techniker krankenkasse|aok|barmer|dak|ikk|krankenkasse|krankenversicherung|haftpflicht|hausrat|" +
            "rentenversicherung|finanzamt|kfz-steuer|einkommensteuer|lohnsteuer|gewerbesteuer|grundsteuer|" +
            "steuerzahlung|steuernachzahlung|income tax|tax office|tax payment|" +
            "inland revenue|ird|ceylinco|janashakthi|softlogic life|aia",
        "Drugstore & Personal Care" to "dm|dm-drogerie|dm drogerie|rossmann|müller drogerie|budni|douglas|" +
            "drogerie|drugstore|kosmetik|cosmetics|parfümerie|friseur|barber|hair salon|salon|nagelstudio|" +
            "body shop|lush|sephora",
        "Electronics" to "electronics|elektronik|mediamarkt|media markt|saturn|apple store|cyberport|" +
            "notebooksbilliger|alternate|conrad|euronics|gravis|samsung|xiaomi|singer|abans|softlogic|" +
            "damro|dialog store",
        "Household Supplies" to "kik|woolworth|woolworths|tedi|action|nanu-nana|thomas philipps|pepco|" +
            "søstrene grene|sostrene grene|ikea|jysk|haushalt|haushaltswaren|household|euroshop|1-euro|" +
            "mäc geiz|maec geiz|hema|rusta",
        "Education" to "education|school|schule|university|universität|universitaet|hochschule|uni|course|" +
            "courses|udemy|coursera|linkedin learning|volkshochschule|vhs|goethe|sprachschule|tuition|" +
            "studiengebühr|semesterbeitrag|thalia|hugendubel|bücher|buecher|books|exam|prüfung",
        "Fuel" to "fuel|petrol|diesel|filling station|ceypetco|lioc|octane|tankstelle|aral|shell|esso|jet|agip|" +
            "total ?energies|star tankstelle|orlen",
        "Groceries" to "supermarket|super|keells|cargills|food ?city|arpico|glomark|grocery|groceries|spar|lidl|aldi|" +
            "walmart|tesco|rewe|edeka|kaufland|netto|penny|norma|globus|tegut|alnatura|denns|bio company|" +
            "lebensmittel|carrefour|getränke|flaschenpost|picnic|knuspr",
        "Dining" to "restaurant|cafe|café|coffee|pizza|pizzeria|burger|kfc|mcdonald'?s|bakery|hotel|dine|kitchen|bistro|" +
            "lieferando|wolt|uber ?eats|deliveroo|starbucks|subway|burger king|imbiss|döner|doener|gaststätte|" +
            "ristorante|trattoria|sushi|bäckerei|baeckerei|backshop|kantine|mensa",
        "Medical" to "pharmacy|hospital|clinic|medical|chemist|healthguard|channell?ing|apotheke|arzt|ärztin|ärzte|" +
            "zahnarzt|praxis|pvs|privatärztliche|krankenhaus|klinik|klinikum|physio|physiotherapie|labor|optiker",
        "Utilities" to "electricity|ceb|leco|water board|dialog|mobitel|slt|hutch|airtel|telecom|broadband|internet|" +
            "vodafone|telekom|o2|telefonica|congstar|1&1|stadtwerke|vattenfall|e\\.on|eon|gasag|strom|energie",
        "Transport" to "taxi|uber|pickme|bus|train|railway|parking|toll|bvg|db vertrieb|deutsche bahn|db fernverkehr|" +
            "db regio|bahn|s-bahn|mvg|hvv|vbb|flixbus|flixtrain|bolt|free ?now|tier|lime|voi|miles|share ?now|parken",
        "Travel" to "airline|airways|flight|booking|booking\\.com|guest house|resort|lufthansa|ryanair|easyjet|" +
            "eurowings|condor|airbnb|expedia|hostel|flughafen|airport|srilankan",
        "Marketing" to "google ads|facebook ads|meta ads|advertising|werbung|linkedin ads",
        "Software & Subscriptions" to "software|subscription|hosting|domain|cloud|saas|license|licence|netflix|spotify|" +
            "apple\\.com|itunes|app store|google play|google|microsoft|adobe|github|openai|chatgpt|anthropic|claude|" +
            "dropbox|icloud|disney|youtube|amazon prime|prime video|jetbrains|notion|canva|abo",
        "Office Supplies" to "stationery|stationary|bookshop|printing|office supplies|toner|staples|mcpaper|bürobedarf",
        "Government & Fees" to "department|ministry|government|municipal|secretariat|registration of persons|" +
            "passport|immigration|motor traffic|stamp duty|bürgeramt|buergeramt|ausländerbehörde|" +
            "landesamt|bezirksamt|bundeskasse|landeshauptkasse|rundfunk|rundfunkbeitrag|bussgeld|bußgeld",
        "Rent" to "miete|kaltmiete|warmmiete|rent|hausverwaltung|wohnungsbau|wohnungsgesellschaft|vonovia|" +
            "deutsche wohnen|gewobag|degewo|howoge",
        "Repairs & Maintenance" to "repair|repairs|reparatur|werkstatt|handwerker|baumarkt|obi|bauhaus|hornbach|toom|" +
            "service center",
        "Professional Services" to "steuerberater|steuerberatung|rechtsanwalt|anwalt|notar|lawyer|attorney|accountant|" +
            "consulting|beratung|übersetzung|translation",
        "Bank Charges" to "kontoführung|kontofuehrung|kontoführungsentgelt|entgelt|gebühr|gebuehr|bank charge|" +
            "bank charges|account fee|card fee|service fee|fee|fees|überziehungszinsen|sollzinsen",
    )

    private val INCOME = hints(
        "Salary" to "gehalt|lohn|salary|payroll|bezüge|bezuege|wages|lohn/gehalt",
        "Dividends" to "dividende|dividenden|dividend|dividends|ertragsausschüttung|ausschüttung|ausschuettung|" +
            "distribution|kupon|coupon",
        "Interest" to "zinsen|zins|habenzinsen|interest|zinsgutschrift",
        "Refunds" to "erstattung|rückerstattung|rueckerstattung|refund|rücküberweisung|retoure|cashback|chargeback|" +
            "reversal|storno|rückzahlung|rueckzahlung",
        "Rent received" to "mieteinnahme|mieteinnahmen|rent received|untermiete",
        "Services / Fees" to "honorar|invoice|rechnung|upwork|fiverr|freelance",
        "Sales" to "ebay|kleinanzeigen|vinted|etsy|verkauf|sale",
    )

    /** Shortened payee used to recognise the same company again, e.g. "rewe markt" for "REWE Markt GmbH 1234". */
    fun payeeKey(party: String): String? =
        Regex("""\p{L}{2,}""").findAll(party.lowercase())
            .map { it.value }
            .filterNot { it in LEGAL_FORMS }
            .take(2)
            .joinToString(" ")
            .takeIf { it.isNotEmpty() }

    private val LEGAL_FORMS = setOf("gmbh", "ag", "kg", "ug", "ltd", "llc", "inc", "co", "ohg", "pvt", "plc", "se")

    /** Most used category per payee and type, from entries the user saved. */
    fun learn(entries: List<PartyCategory>): Map<Pair<TxType, String>, String> =
        entries.mapNotNull { e -> payeeKey(e.party)?.let { (e.type to it) to e.category } }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, categories) -> categories.groupingBy { it }.eachCount().maxBy { it.value }.key }

    /**
     * Best category for an entry of [type] described by [texts] (payee first), or "" when nothing fits.
     * [history] comes from [learn]; [rules] the user asked to remember win over everything.
     */
    fun guess(
        type: TxType,
        texts: List<String>,
        history: Map<Pair<TxType, String>, String> = emptyMap(),
        rules: List<CategoryRule> = emptyList(),
    ): String {
        matchingRule(rules, type, texts)?.let { return it.category }
        texts.firstOrNull()?.let(::payeeKey)?.let { history[type to it] }?.let { return it }
        val text = texts.filter { it.isNotBlank() }.joinToString("\n")
        if (text.isBlank()) return ""
        return if (type == TxType.EXPENSE) {
            match(EXPENSE, text).orEmpty()
        } else {
            // Money back from a shop is a refund; money from a broker (a sale or payout) is left to the user.
            match(INCOME, text) ?: when (match(EXPENSE, text)) {
                null, Categories.INVESTMENTS -> ""
                else -> "Refunds"
            }
        }
    }

    /** The rule whose keyword appears as whole words in [texts]; the longest keyword wins. */
    fun matchingRule(rules: List<CategoryRule>, type: TxType, texts: List<String>): CategoryRule? {
        val text = texts.filter { it.isNotBlank() }.joinToString("\n")
        if (text.isBlank()) return null
        return rules.filter { it.type == type && keywordRegex(it.keyword).containsMatchIn(text) }
            .maxByOrNull { it.keyword.length }
    }

    private fun keywordRegex(keyword: String): Regex {
        val words = keyword.trim().split(Regex("""\s+""")).filter { it.isNotEmpty() }.joinToString("""\s+""") { Regex.escape(it) }
        return Regex("""(?<![\p{L}\d])$words(?![\p{L}\d])""", RegexOption.IGNORE_CASE)
    }

    /**
     * Keyword to offer when asking to remember a choice: the payee's first two words, as written,
     * without numbers or legal forms ("REWE Markt GmbH 1234" → "REWE Markt").
     */
    fun suggestKeyword(party: String): String =
        Regex("""\p{L}[\p{L}&'.]*""").findAll(party)
            .map { it.value.trimEnd('.') }
            .filter { it.length >= 2 && it.lowercase() !in LEGAL_FORMS }
            .take(2)
            .joinToString(" ")

    /**
     * Keyword to offer after the user chose [category] for [party], or null when there is nothing
     * to remember (no payee, no category, or a rule already gives this category).
     */
    fun offerKeyword(party: String, type: TxType, category: String, rules: List<CategoryRule>): String? {
        if (category.isBlank()) return null
        val keyword = suggestKeyword(party).ifBlank { return null }
        if (matchingRule(rules, type, listOf(party))?.category == category) return null
        return keyword
    }

    /** Normalised form a rule is stored under. */
    fun normaliseKeyword(keyword: String): String = keyword.trim().replace(Regex("""\s+"""), " ").lowercase()

    private fun match(hints: List<Pair<String, Regex>>, text: String) =
        hints.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.first
}
