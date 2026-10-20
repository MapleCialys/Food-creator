package com.foodcreator.core

enum class Diet(val label: String) {
    VEGETARIEN("Végétarien"),
    VEGANE("Végane"),
    PESCETARIEN("Pescétarien"),
    SANS_PORC("Sans porc"),
    SANS_GLUTEN("Sans gluten"),
    SANS_LACTOSE("Sans lactose"),
    SANS_NOIX("Sans noix"),
}

data class Preferences(
    val people: Int,
    /** $ pour le repas (mode repas unique) ou pour toute la semaine (mode semaine). */
    val budget: Double,
    val diets: Set<Diet> = emptySet(),
    val avoid: List<String> = emptyList(),
    val maxMinutes: Int? = null,
    /** Envie libre (« poulet », « italien »...), utilisée comme recherche. */
    val wish: String = "",
)

data class Recipe(
    val title: String,
    val url: String,
    val servings: Int?,
    val minutes: Int?,
    val ingredients: List<String>,
    val steps: List<String>,
    val image: String?,
)

/** Produit à l'épicerie. [estimated] = prix de repli, l'épicerie n'a pas répondu. */
data class Product(val name: String, val price: Double, val url: String?, val estimated: Boolean = false)

data class PricedIngredient(val line: String, val product: Product?, val pantry: Boolean)

data class Meal(val label: String, val recipe: Recipe, val ingredients: List<PricedIngredient>, val scale: Double) {
    // shortcut: coût = prix des emballages × portions demandées / portions de la recette, pas de calcul au gramme.
    val cost: Double get() = ingredients.sumOf { it.product?.price ?: 0.0 } * scale
}

interface RecipeProvider {
    fun search(query: String): List<String>
    fun load(url: String): Recipe?
}

interface PriceSource {
    val storeName: String
    fun find(keyword: String): Product?
}
