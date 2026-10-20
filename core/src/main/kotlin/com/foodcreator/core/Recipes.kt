package com.foodcreator.core

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.net.URLEncoder
import java.time.Duration

object Http {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    fun get(url: String): Document =
        Jsoup.connect(url).userAgent(UA).header("Accept-Language", "fr-CA,fr;q=0.9").timeout(15_000).get()

    fun enc(q: String): String = URLEncoder.encode(q, "UTF-8")
}

/** Un site de cuisine : page de recherche + forme des liens vers les recettes. */
class RecipeSite(val name: String, private val searchUrl: String, private val recipeLink: Regex) {
    fun searchUrl(query: String) = searchUrl.replace("{q}", Http.enc(query))

    fun recipeLinks(doc: Document): List<String> = doc.select("a[href]")
        .map { it.absUrl("href").substringBefore('#').substringBefore('?') }
        .filter { recipeLink.matches(it) }
        .distinct()

    companion object {
        // Les pages de recette de ces sites publient un bloc JSON-LD schema.org/Recipe.
        val DEFAULTS = listOf(
            RecipeSite("Ricardo", "https://www.ricardocuisine.com/recherche?searchValue={q}",
                Regex("""https://www\.ricardocuisine\.com/recettes/\d+-[\w-]+""")),
            RecipeSite("Trois fois par jour", "https://www.troisfoisparjour.com/fr/?s={q}",
                Regex("""https://www\.troisfoisparjour\.com/fr/recettes/[\w-]+/[\w-]+/?""")),
            RecipeSite("Marmiton", "https://www.marmiton.org/recettes/recherche.aspx?aqt={q}",
                Regex("""https://www\.marmiton\.org/recettes/recette_[\w-]+\.aspx""")),
        )
    }
}

class WebRecipes(
    private val sites: List<RecipeSite> = RecipeSite.DEFAULTS,
    private val fetch: (String) -> Document = Http::get,
) : RecipeProvider {
    /** Liens de toutes les sources, entrelacés pour varier les sites. Un site en panne est ignoré. */
    override fun search(query: String): List<String> {
        val perSite = sites.map { site -> runCatching { site.recipeLinks(fetch(site.searchUrl(query))) }.getOrDefault(emptyList()) }
        return (0 until (perSite.maxOfOrNull { it.size } ?: 0)).flatMap { i -> perSite.mapNotNull { it.getOrNull(i) } }
    }

    override fun load(url: String): Recipe? = runCatching { JsonLd.parseRecipe(fetch(url), url) }.getOrNull()
}

object JsonLd {
    fun parseRecipe(doc: Document, url: String): Recipe? = doc.select("script[type=application/ld+json]")
        .firstNotNullOfOrNull { s -> runCatching { findRecipe(JSONTokener(s.data()).nextValue()) }.getOrNull() }
        ?.let { toRecipe(it, url) }

    private fun findRecipe(v: Any?): JSONObject? = when (v) {
        is JSONArray -> (0 until v.length()).firstNotNullOfOrNull { findRecipe(v.get(it)) }
        is JSONObject -> if (strings(v.opt("@type")).any { it == "Recipe" }) v else findRecipe(v.opt("@graph"))
        else -> null
    }

    private fun toRecipe(o: JSONObject, url: String): Recipe? {
        val ingredients = strings(o.opt("recipeIngredient")).map(::clean).filter { it.isNotBlank() }
        if (ingredients.isEmpty()) return null
        val minutes = minutes(o.optString("totalTime"))
            ?: listOfNotNull(minutes(o.optString("prepTime")), minutes(o.optString("cookTime"))).takeIf { it.isNotEmpty() }?.sum()
        return Recipe(
            title = clean(o.optString("name")),
            url = url,
            servings = strings(o.opt("recipeYield")).firstNotNullOfOrNull { Regex("""\d+""").find(it)?.value?.toInt() },
            minutes = minutes,
            ingredients = ingredients,
            steps = steps(o.opt("recipeInstructions")).map(::clean).filter { it.isNotBlank() },
            image = images(o.opt("image")).firstOrNull(),
        )
    }

    private fun steps(v: Any?): List<String> = when (v) {
        is String -> v.split(Regex("""\n+|(?<=\.)\s+(?=\d+\.\s)""")) // texte brut, parfois numéroté
        is JSONArray -> (0 until v.length()).flatMap { steps(v.get(it)) }
        is JSONObject -> if (v.has("itemListElement")) steps(v.opt("itemListElement")) else listOf(v.optString("text"))
        else -> emptyList()
    }

    private fun images(v: Any?): List<String> = when (v) {
        is String -> listOf(v)
        is JSONArray -> (0 until v.length()).flatMap { images(v.get(it)) }
        is JSONObject -> listOf(v.optString("url"))
        else -> emptyList()
    }

    private fun strings(v: Any?): List<String> = when (v) {
        is JSONArray -> (0 until v.length()).flatMap { strings(v.get(it)) }
        null, JSONObject.NULL -> emptyList()
        else -> listOf(v.toString())
    }

    private fun clean(s: String) = Jsoup.parse(Parser.unescapeEntities(s, false)).text().trim()

    internal fun minutes(iso: String): Int? = runCatching { Duration.parse(iso).toMinutes().toInt() }.getOrNull()?.takeIf { it > 0 }
}
