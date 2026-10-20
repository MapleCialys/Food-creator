package com.foodcreator.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

enum class Slot { MIDI, SOIR }

class Planner(
    private val recipes: RecipeProvider = WebRecipes(),
    private val prices: PriceSource = SuperCPrices(),
    private val random: Random = Random.Default,
) {
    private val searches = ConcurrentHashMap<String, List<String>>()

    /** [count] idées de repas différentes, chacune dans le budget du repas si possible. */
    suspend fun options(
        p: Preferences, count: Int = 5, exclude: Set<String> = emptySet(), progress: (Int, Int) -> Unit = { _, _ -> },
    ): List<Meal> = plan(p, (1..count).map { "Idée $it" to Slot.SOIR }, p.budget, exclude, if (p.wish.isBlank()) 1 else 0, progress)

    /** 7 jours × midi et soir. [progress] reçoit (repas prêts, total). */
    suspend fun week(p: Preferences, progress: (Int, Int) -> Unit = { _, _ -> }): List<Meal> = plan(
        p, DAYS.flatMap { d -> Slot.entries.map { "$d ${it.name.lowercase()}" to it } }, p.budget / 14,
        rotateEvery = 2, progress = progress,
    )

    /** Un repas par case, en parallèle. Le thème tourne d'une case à l'autre ([rotateEvery] = 0 : toujours l'envie d'abord). */
    private suspend fun plan(
        p: Preferences, slots: List<Pair<String, Slot>>, perMeal: Double, exclude: Set<String> = emptySet(),
        rotateEvery: Int, progress: (Int, Int) -> Unit = { _, _ -> },
    ): List<Meal> = coroutineScope {
        val used = Collections.synchronizedSet(exclude.toMutableSet())
        val done = AtomicInteger()
        val themes = Slot.entries.associateWith { themes(p, it) }
        val io = Dispatchers.IO.limitedParallelism(4) // pour ne pas inonder les sites de requêtes
        slots.mapIndexed { i, (label, slot) ->
            async(io) {
                val list = themes.getValue(slot)
                val shift = if (rotateEvery == 0) 0 else i / rotateEvery % list.size
                (list.drop(shift) + list.take(shift))
                    .firstNotNullOfOrNull { pick(it, label, p, perMeal, used) }
                    .also { progress(done.incrementAndGet(), slots.size) }
            }
        }.awaitAll().filterNotNull()
    }

    fun price(recipe: Recipe, label: String, people: Int): Meal = Meal(
        label = label,
        recipe = recipe,
        ingredients = recipe.ingredients.map { line ->
            if (Ingredients.isPantry(line)) PricedIngredient(line, null, pantry = true)
            else PricedIngredient(line, prices.find(Ingredients.keyword(line)), pantry = false)
        },
        scale = people.toDouble() / (recipe.servings ?: 4),
    )

    /** Première recette du thème qui respecte le budget, sinon la moins chère trouvée. */
    private fun pick(theme: String, label: String, p: Preferences, maxCost: Double, used: MutableSet<String>): Meal? {
        val urls = searches.getOrPut(theme) { recipes.search(theme) }.shuffled(random)
        var cheapest: Meal? = null
        for (url in urls.take(MAX_TRIES)) {
            if (url in used) continue
            val r = recipes.load(url) ?: continue
            if (!Ingredients.allowed(r, p.diets, p.avoid)) continue
            if (p.maxMinutes != null && (r.minutes ?: 0) > p.maxMinutes) continue
            val meal = price(r, label, p.people)
            if (meal.cost <= maxCost && used.add(url)) return meal
            if (cheapest == null || meal.cost < cheapest.cost) cheapest = meal
        }
        return cheapest?.takeIf { used.add(it.recipe.url) }
    }

    private fun themes(p: Preferences, slot: Slot): List<String> {
        val veg = Diet.VEGETARIEN in p.diets || Diet.VEGANE in p.diets
        val base = when {
            slot == Slot.MIDI -> listOf("salade repas", "soupe repas", "wrap", "sandwich", "bol", "quiche", "pâtes")
            veg -> listOf("tofu", "lentilles", "pois chiches", "végétarien", "haricots noirs", "légumes rôtis")
            Diet.PESCETARIEN in p.diets -> listOf("saumon", "crevettes", "poisson", "tofu", "lentilles", "pâtes")
            else -> listOf("poulet", "boeuf haché", "porc", "saumon", "pâtes", "mijoté", "curry", "chili", "sauté", "tofu")
        }.filter { Diet.SANS_PORC !in p.diets || it != "porc" }.shuffled(random)
        return (listOf(p.wish.trim()).filter { it.isNotEmpty() } + base).distinct()
    }

    companion object {
        const val MAX_TRIES = 6
        val DAYS = listOf("Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi", "Samedi", "Dimanche")

        /** Liste d'épicerie : un produit par ligne, avec les ingrédients qu'il couvre. */
        fun shoppingList(meals: List<Meal>): List<Pair<Product, List<String>>> = meals
            .flatMap { m -> m.ingredients.filter { it.product != null }.map { it.product!! to Ingredients.scale(it.line, m.scale) } }
            .groupBy({ it.first.name }, { it })
            .map { (_, items) -> items.first().first to items.map { it.second } }
            .sortedBy { it.first.name }
    }
}
