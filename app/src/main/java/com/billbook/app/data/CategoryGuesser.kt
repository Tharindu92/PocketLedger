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
        "Fuel" to "fuel|petrol|diesel|filling station|ceypetco|lioc|octane|tankstelle|aral|shell|esso|jet|agip|" +
            "total ?energies|star tankstelle|orlen",
        "Groceries" to "supermarket|super|keells|cargills|food ?city|arpico|glomark|grocery|groceries|spar|lidl|aldi|" +
            "walmart|tesco|rewe|edeka|kaufland|netto|penny|norma|globus|tegut|alnatura|denns|bio company|" +
            "lebensmittel|carrefour|dm|dm-drogerie|dm drogerie|rossmann|drogerie|getränke|flaschenpost|picnic|knuspr",
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
            "passport|immigration|motor traffic|stamp duty|finanzamt|bürgeramt|buergeramt|ausländerbehörde|" +
            "landesamt|bezirksamt|bundeskasse|landeshauptkasse|rundfunk|rundfunkbeitrag|bussgeld|bußgeld|kfz-steuer",
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
     * [history] comes from [learn].
     */
    fun guess(
        type: TxType,
        texts: List<String>,
        history: Map<Pair<TxType, String>, String> = emptyMap(),
    ): String {
        texts.firstOrNull()?.let(::payeeKey)?.let { history[type to it] }?.let { return it }
        val text = texts.filter { it.isNotBlank() }.joinToString("\n")
        if (text.isBlank()) return ""
        return if (type == TxType.EXPENSE) {
            match(EXPENSE, text).orEmpty()
        } else {
            match(INCOME, text) ?: if (match(EXPENSE, text) != null) "Refunds" else ""
        }
    }

    private fun match(hints: List<Pair<String, Regex>>, text: String) =
        hints.firstOrNull { (_, regex) -> regex.containsMatchIn(text) }?.first
}
