package com.foodcreator.core

import java.text.Normalizer
import java.util.Locale

object Ingredients {
    fun normalize(s: String): String = Normalizer.normalize(
        s.lowercase(Locale.CANADA_FRENCH).replace("œ", "oe").replace('’', '\''), Normalizer.Form.NFD,
    ).replace(Regex("""\p{M}"""), "")

    private val UNITS = listOf(
        "c\\. a soupe", "c\\. a the", "c\\. a cafe", "c\\. a s\\.", "c\\. a t\\.", "c\\.a\\.s\\.?", "c\\.a\\.t\\.?", "cs", "cc",
        "cuilleres? a (?:soupe|the|cafe)", "tasses?", "kg", "g", "mg", "ml", "cl", "dl", "l", "lbs?", "oz", "litres?", "grammes?",
        "gousses?", "boites?", "conserves?", "paquets?", "sachets?", "enveloppes?", "tranches?", "pincees?", "bottes?",
        "brins?", "branches?", "feuilles?", "morceaux?", "filets?", "pots?", "verres?", "bouquets?", "tiges?", "poignees?",
        "de", "d'", "du", "des", "la", "le", "les", "l'", "a", "environ", "gros", "grosses?", "petits?", "petites?", "moyens?", "moyennes?",
    )
    private val LEADING = Regex("""^(?:${UNITS.joinToString("|")})(?=\s|$|(?<=')) *""")
    private val NUMBER = Regex("""(?<![\d.,])(\d+(?:[.,]\d+)?(?:\s+\d+/\d+)?|\d+/\d+|[½¼¾⅓⅔])(?!\s*%)(?![\d/])""")
    private val FRACTIONS = mapOf('½' to 0.5, '¼' to 0.25, '¾' to 0.75, '⅓' to 1.0 / 3, '⅔' to 2.0 / 3)

    /** « 500 g (1 lb) de poulet haché, en cubes » → « poulet hache ». */
    fun keyword(line: String): String {
        var s = normalize(line).replace(Regex("""\([^)]*\)"""), " ")
            .substringBefore(",").substringBefore(" ou ").substringBefore(" pour ").substringBefore(" au gout")
            .replace(Regex("""\d+(?:[.,/]\d+)?|[½¼¾⅓⅔]"""), " ")
            .replace(Regex("""\s+"""), " ").trim()
        while (true) {
            val next = s.replaceFirst(LEADING, "").trim()
            if (next == s) break
            s = next
        }
        return s.replace(Regex("""[^\p{L}' -]"""), " ").replace(Regex("""\s+"""), " ").trim()
    }

    /** Recherches à essayer à l'épicerie, de la plus précise à la plus large (3 mots max). */
    fun queries(keyword: String): List<String> {
        val words = keyword.split(' ').filter { it.isNotBlank() }.take(3)
        return (words.size downTo 1).map { words.take(it) }
            .filter { it.last() !in setOf("de", "d'", "du", "des", "a", "en", "et") }
            .map { it.joinToString(" ") }
    }

    private val PANTRY = listOf("sel", "poivre", "eau", "huile", "glace", "glacons", "sel et poivre")

    /** Ingrédient qu'on a déjà à la maison : pas compté dans le coût. */
    fun isPantry(line: String): Boolean {
        val n = normalize(line)
        val kw = keyword(line)
        return "au gout" in n || kw.isEmpty() || PANTRY.any { kw == it || kw.startsWith("$it ") }
    }

    /** Multiplie les quantités d'une ligne (pas les pourcentages : « crème 35 % »). */
    fun scale(line: String, factor: Double): String {
        if (kotlin.math.abs(factor - 1) < 0.01) return line
        return NUMBER.replace(line) { m -> format(parse(m.value) * factor) }
    }

    private fun parse(v: String): Double = v.trim().split(Regex("""\s+""")).sumOf { part ->
        FRACTIONS[part.singleOrNull()] ?: if ('/' in part) part.split('/').let { it[0].toDouble() / it[1].toDouble() }
        else part.replace(',', '.').toDouble()
    }

    private fun format(v: Double): String = String.format(Locale.CANADA_FRENCH, "%.2f", v).trimEnd('0').trimEnd(',', '.')

    // Racines de mots interdites par régime. Les exceptions sont retirées avant la vérification.
    private val MEAT = listOf("boeuf", "porc", "poulet", "veau", "agneau", "jambon", "bacon", "saucisse", "dinde", "canard",
        "chorizo", "prosciutto", "pancetta", "lardon", "viande", "cuisse", "pepperoni", "salami", "merguez", "steak", "bouillon de poulet", "bouillon de boeuf")
    private val SEAFOOD = listOf("poisson", "saumon", "thon", "crevette", "morue", "tilapia", "petoncle", "homard", "crabe",
        "moule", "aiglefin", "truite", "sardine", "anchois", "calmar", "fruits de mer", "sauce de poisson")
    private val PORK = listOf("porc", "jambon", "bacon", "saucisse", "chorizo", "prosciutto", "pancetta", "lardon", "pepperoni", "salami")
    private val DAIRY = listOf("lait", "creme", "beurre", "fromage", "yogourt", "yaourt", "parmesan", "mozzarella", "cheddar",
        "ricotta", "feta", "mascarpone", "gruyere", "babeurre", "ghee", "brie", "emmental")
    private val ANIMAL = listOf("oeuf", "miel", "gelatine")
    private val GLUTEN = listOf("farine", "pate", "pates", "pain", "chapelure", "spaghetti", "linguine", "penne", "macaroni",
        "lasagne", "fusilli", "nouilles", "couscous", "orge", "boulgour", "biere", "tortilla", "biscuit", "panko", "seitan", "ble",
        "sauce soya", "baguette", "croutons", "gnocchi", "tortellini", "ravioli")
    private val NUTS = listOf("noix", "amande", "arachide", "pacane", "pistache", "cajou", "noisette", "pignon", "macadamia")
    private val EXCEPTIONS = listOf("lait de coco", "creme de coco", "noix de coco", "lait d'amande", "lait d'avoine",
        "lait de soya", "lait vegetal", "beurre d'arachide", "beurre de noix", "beurre d'amande", "pate de tomates",
        "pate de tomate", "pate de cari", "pate de curry", "pate de miso", "nouilles de riz", "vermicelles de riz",
        "noix de muscade", "ble d'inde")

    fun banned(diets: Set<Diet>): List<String> = diets.flatMap {
        when (it) {
            Diet.VEGETARIEN -> MEAT + SEAFOOD
            Diet.VEGANE -> MEAT + SEAFOOD + DAIRY + ANIMAL
            Diet.PESCETARIEN -> MEAT
            Diet.SANS_PORC -> PORK
            Diet.SANS_GLUTEN -> GLUTEN
            Diet.SANS_LACTOSE -> DAIRY
            Diet.SANS_NOIX -> NUTS
        }
    }

    // shortcut: filtre par mots-clés sur le texte des ingrédients; une allergie grave demande de relire la recette.
    fun allowed(recipe: Recipe, diets: Set<Diet>, avoid: List<String>): Boolean {
        val words = (banned(diets) + avoid.map(::normalize)).map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        // Une exception (« beurre d'arachide ») excuse son premier mot seulement : elle reste interdite sans noix.
        val exceptions = EXCEPTIONS.filter { e -> words.none { w -> w != e.substringBefore(' ') && hasWord(e, w) } }
        return recipe.ingredients.none { line ->
            val n = exceptions.fold(normalize(line)) { acc, e -> acc.replace(e, " ") }
            words.any { hasWord(n, it) }
        }
    }

    private fun hasWord(text: String, w: String) = Regex("""(?<!\p{L})${Regex.escape(w)}s?(?!\p{L})""").containsMatchIn(text)
}
