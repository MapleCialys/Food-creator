package com.foodcreator.core

import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CoreTest {
    private fun res(name: String) = Jsoup.parse(javaClass.getResource("/$name")!!.readText(), "https://www.superc.ca/")

    @Test fun parsesJsonLdRecipe() {
        val r = JsonLd.parseRecipe(res("recipe.html"), "https://ex.com/r")!!
        assertEquals("Poulet au beurre & riz", r.title)
        assertEquals(4, r.servings)
        assertEquals(60, r.minutes)
        assertEquals(3, r.ingredients.size)
        assertEquals(listOf("Dorer le poulet.", "Ajouter la crème."), r.steps)
        assertEquals("https://ex.com/p.jpg", r.image)
    }

    @Test fun extractsKeywords() {
        assertEquals("poitrines de poulet", Ingredients.keyword("500 g (1 lb) de poitrines de poulet, en cubes"))
        assertEquals("creme", Ingredients.keyword("1 tasse (250 ml) de crème 35 %"))
        assertEquals("oignons haches", Ingredients.keyword("2 gros oignons hachés"))
        assertEquals("ail", Ingredients.keyword("2 gousses d'ail, hachées"))
        assertEquals("huile d'olive", Ingredients.keyword("30 ml (2 c. à soupe) d’huile d’olive"))
        assertEquals(listOf("poitrines de poulet", "poitrines"), Ingredients.queries("poitrines de poulet"))
    }

    @Test fun pantryAndScale() {
        assertTrue(Ingredients.isPantry("Sel et poivre"))
        assertTrue(Ingredients.isPantry("30 ml d'huile d'olive"))
        assertTrue(Ingredients.isPantry("Persil, au goût"))
        assertFalse(Ingredients.isPantry("1 oignon"))
        assertEquals("1000 g (2 lb) de boeuf", Ingredients.scale("500 g (1 lb) de boeuf", 2.0))
        assertEquals("3 tasse de crème 35 %", Ingredients.scale("1 1/2 tasse de crème 35 %", 2.0))
        assertEquals("0,5 oignon", Ingredients.scale("1 oignon", 0.5))
    }

    private fun recipe(vararg ing: String) = Recipe("t", "u", 4, 30, ing.toList(), emptyList(), null)

    @Test fun dietFilters() {
        val curry = recipe("1 boîte de lait de coco", "200 g de tofu")
        assertTrue(Ingredients.allowed(curry, setOf(Diet.VEGANE, Diet.SANS_LACTOSE), emptyList()))
        assertFalse(Ingredients.allowed(recipe("2 poitrines de poulet"), setOf(Diet.VEGETARIEN), emptyList()))
        assertTrue(Ingredients.allowed(recipe("1 laitue"), setOf(Diet.SANS_LACTOSE), emptyList()))
        val pb = recipe("60 ml de beurre d'arachide")
        assertTrue(Ingredients.allowed(pb, setOf(Diet.SANS_LACTOSE), emptyList()))
        assertFalse(Ingredients.allowed(pb, setOf(Diet.SANS_NOIX), emptyList()))
        assertTrue(Ingredients.allowed(recipe("30 ml de pâte de tomates"), setOf(Diet.SANS_GLUTEN), emptyList()))
        assertFalse(Ingredients.allowed(recipe("250 g de pâtes"), setOf(Diet.SANS_GLUTEN), emptyList()))
        assertFalse(Ingredients.allowed(recipe("1 Coriandre fraîche"), emptySet(), listOf("coriandre")))
    }

    @Test fun parsesSuperCTiles() {
        val p = SuperCPrices.parseSearch(res("superc-search.html"))
        assertEquals(1, p.size)
        assertEquals("Natrel Lait 2 % 2 L", p[0].name)
        assertEquals(4.99, p[0].price)
        assertEquals("https://www.superc.ca/allees/produits-laitiers-et-oeufs/lait-2/p/064420010124", p[0].url)
    }

    @Test fun superCFallsBackToEstimate() {
        val prices = SuperCPrices { error("hors ligne") }
        val p = prices.find("poitrines de poulet")!!
        assertTrue(p.estimated)
        assertEquals(12.0, p.price)
    }

    private class FakeRecipes : RecipeProvider {
        val all = (1..40).associate { i ->
            val ing = if (i % 2 == 0) listOf("1 kg de poulet", "1 oignon") else listOf("1 bloc de tofu", "1 oignon")
            "u$i" to Recipe("R$i", "u$i", 4, 20, ing, listOf("Cuire."), null)
        }
        override fun search(query: String) = all.keys.toList()
        override fun load(url: String) = all[url]
    }

    private object FakePrices : PriceSource {
        override val storeName = "Test"
        override fun find(keyword: String) = Product(keyword, if ("poulet" in keyword) 12.0 else 3.0, null)
    }

    @Test fun plansWeekWithinDietAndWithoutDuplicates() = runBlocking {
        val planner = Planner(FakeRecipes(), FakePrices, Random(1))
        val week = planner.week(Preferences(people = 2, budget = 150.0, diets = setOf(Diet.VEGETARIEN)))
        assertEquals(14, week.size)
        assertEquals(14, week.map { it.recipe.url }.toSet().size)
        assertTrue(week.all { m -> m.ingredients.none { "poulet" in it.line } })
        // tofu 3 $ + oignon 3 $, pour 2 personnes sur 4 portions
        assertEquals(3.0, week.first().cost)
        assertEquals(listOf("bloc de tofu", "oignon"), Planner.shoppingList(week).map { it.first.name })
    }

    @Test fun singleMealPrefersBudget() = runBlocking {
        val meal = Planner(FakeRecipes(), FakePrices, Random(2)).single(Preferences(people = 4, budget = 10.0))!!
        assertTrue(meal.cost <= 10.0, "coût ${meal.cost}")
    }
}
