package com.foodcreator.core

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Prix SuperC tirés de la recherche des « allées » de superc.ca (même plateforme que metro.ca).
 * Si le site ne répond pas ou ne trouve rien, un prix moyen estimé est utilisé et marqué comme tel.
 */
class SuperCPrices(private val fetch: (String) -> Document = Http::get) : PriceSource {
    override val storeName = "SuperC"
    private val cache = HashMap<String, Product?>()

    override fun find(keyword: String): Product? {
        synchronized(cache) { if (keyword in cache) return cache[keyword] }
        val found = Ingredients.queries(keyword).firstNotNullOfOrNull { q ->
            runCatching { parseSearch(fetch(SEARCH + Http.enc(q))) }.getOrDefault(emptyList()).firstOrNull()
        } ?: Fallback.estimate(keyword)
        synchronized(cache) { cache[keyword] = found }
        return found
    }

    companion object {
        const val SEARCH = "https://www.superc.ca/allees/recherche?filter="
        private val PRICE = Regex("""(\d+)[.,](\d{2})""")

        fun parseSearch(doc: Document): List<Product> = doc.select("[data-product-code]").mapNotNull(::tile)

        private fun tile(e: Element): Product? {
            val name = listOf(e.selectFirst(".head__brand")?.text(), e.attr("data-product-name").ifBlank { e.selectFirst(".head__title")?.text() },
                e.selectFirst(".head__unit-details")?.text()).filter { !it.isNullOrBlank() }.joinToString(" ")
            // Le prix de vente (rabais) apparaît avant le prix régulier sur la tuile.
            val priceText = e.selectFirst(".pricing__sale-price .price-update, .price-update")?.text() ?: e.text()
            val price = PRICE.find(priceText)?.let { "${it.groupValues[1]}.${it.groupValues[2]}".toDouble() } ?: return null
            val url = e.selectFirst("a[href*=/p/]")?.absUrl("href")?.ifBlank { null }
            return Product(name.ifBlank { return null }, price, url)
        }
    }
}

object Fallback {
    // shortcut: prix moyens approximatifs d'une épicerie à escompte au Québec (2026); à remplacer si SuperC expose une API.
    private val PRICES = linkedMapOf(
        "poulet" to 12.0, "boeuf" to 11.0, "porc" to 8.0, "veau" to 14.0, "agneau" to 16.0, "dinde" to 10.0, "jambon" to 5.0,
        "bacon" to 6.5, "saucisse" to 7.0, "saumon" to 14.0, "crevette" to 13.0, "thon" to 2.0, "poisson" to 10.0, "tofu" to 3.5,
        "oeuf" to 4.5, "lait de coco" to 2.5, "lait" to 5.5, "beurre" to 6.5, "creme" to 4.0, "fromage" to 7.0, "parmesan" to 7.0,
        "mozzarella" to 7.0, "cheddar" to 7.0, "feta" to 6.0, "yogourt" to 5.0, "riz" to 4.0, "pates" to 2.5, "spaghetti" to 2.5,
        "nouilles" to 3.0, "farine" to 5.0, "sucre" to 4.0, "pain" to 3.5, "tortilla" to 4.0, "quinoa" to 6.0, "avoine" to 5.0,
        "pois chiches" to 1.8, "lentilles" to 2.5, "haricots" to 1.8, "tomates en conserve" to 1.9, "pate de tomates" to 1.2,
        "bouillon" to 3.0, "sauce soya" to 4.0, "oignon" to 3.5, "ail" to 1.5, "carotte" to 2.5, "pomme de terre" to 5.0,
        "patate" to 5.0, "tomate" to 4.0, "poivron" to 2.5, "brocoli" to 3.0, "chou" to 3.0, "laitue" to 3.0, "epinards" to 4.0,
        "champignon" to 3.0, "courgette" to 2.0, "concombre" to 1.5, "celeri" to 2.5, "mais" to 1.8, "avocat" to 2.0,
        "citron" to 1.0, "lime" to 0.8, "pomme" to 5.0, "banane" to 1.6, "persil" to 2.0, "coriandre" to 2.0, "basilic" to 3.0,
        "miel" to 7.0, "moutarde" to 3.0, "mayonnaise" to 5.0, "ketchup" to 4.0, "vinaigre" to 3.0, "sirop d'erable" to 9.0,
        "noix" to 6.0, "amande" to 7.0, "arachide" to 4.0, "gingembre" to 2.0, "cumin" to 3.5, "paprika" to 3.5,
        "cari" to 3.5, "origan" to 3.5, "thym" to 3.0, "cannelle" to 3.5,
    )
    private const val DEFAULT = 4.0

    fun estimate(keyword: String): Product {
        val key = PRICES.keys.firstOrNull { keyword.startsWith(it) } ?: PRICES.keys.firstOrNull { it in keyword }
        return Product(keyword, key?.let { PRICES.getValue(it) } ?: DEFAULT, null, estimated = true)
    }
}
