package com.foodcreator.app

import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.foodcreator.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.text.NumberFormat
import java.util.Locale

sealed interface Screen {
    data object Home : Screen
    data class Form(val week: Boolean) : Screen
    data class Loading(val text: String) : Screen
    data class Options(val meals: List<Meal>, val prefs: Preferences, val seen: Set<String>) : Screen
    data class Week(val meals: List<Meal>, val prefs: Preferences) : Screen
    data class Detail(val meal: Meal) : Screen
    data class Shopping(val meals: List<Meal>) : Screen
    data class Error(val text: String) : Screen
}

class MainViewModel : ViewModel() {
    private val planner = Planner()
    private var job: Job? = null
    val stack = mutableStateListOf<Screen>(Screen.Home)

    fun go(s: Screen) { stack += s }
    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    private fun replaceLoading(s: Screen) { if (stack.lastOrNull() is Screen.Loading) stack[stack.lastIndex] = s }

    fun generate(prefs: Preferences, week: Boolean, exclude: Set<String> = emptySet()) {
        go(Screen.Loading(if (week) "Préparation de la semaine…" else "Recherche de 5 idées de repas…"))
        job?.cancel()
        job = viewModelScope.launch {
            val progress = { done: Int, total: Int ->
                viewModelScope.launch { replaceLoading(Screen.Loading("${if (week) "Semaine" else "Idées"} : $done / $total repas")) }
                Unit
            }
            val result = runCatching {
                if (week) planner.week(prefs, progress).takeIf { it.isNotEmpty() }?.let { Screen.Week(it, prefs) }
                else planner.options(prefs, exclude = exclude, progress = progress).takeIf { it.isNotEmpty() }
                    ?.let { Screen.Options(it, prefs, exclude + it.map { m -> m.recipe.url }) }
            }.getOrNull()
            replaceLoading(result ?: Screen.Error("Aucune recette trouvée. Vérifiez la connexion Internet ou assouplissez vos critères."))
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface(Modifier.fillMaxSize()) { App() } } }
    }
}

private val money: NumberFormat = NumberFormat.getCurrencyInstance(Locale.CANADA_FRENCH)
private fun Double.money() = money.format(this)

@Composable
fun App(vm: MainViewModel = viewModel()) {
    val screen = vm.stack.last()
    BackHandler(enabled = vm.stack.size > 1) { vm.back() }
    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
        when (screen) {
            Screen.Home -> Home(vm)
            is Screen.Form -> Form(screen.week) { vm.generate(it, screen.week) }
            is Screen.Loading -> Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
                CircularProgressIndicator(); Spacer(Modifier.height(16.dp)); Text(screen.text)
            }
            is Screen.Options -> Options(screen, vm)
            is Screen.Week -> WeekPlan(screen, vm)
            is Screen.Detail -> MealDetail(screen.meal)
            is Screen.Shopping -> Shopping(screen.meals)
            is Screen.Error -> Column {
                Text(screen.text); Spacer(Modifier.height(16.dp)); Button({ vm.back() }) { Text("Retour") }
            }
        }
    }
}

@Composable
private fun Home(vm: MainViewModel) = Column(Modifier.fillMaxSize(), Arrangement.Center) {
    Text("Planificateur de repas", style = MaterialTheme.typography.headlineMedium)
    Text("Recettes de sites de cuisine, prix SuperC", style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(32.dp))
    Button({ vm.go(Screen.Form(week = false)) }, Modifier.fillMaxWidth()) { Text("Repas unique") }
    Spacer(Modifier.height(12.dp))
    Button({ vm.go(Screen.Form(week = true)) }, Modifier.fillMaxWidth()) { Text("Semaine (midi et soir)") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Form(week: Boolean, onSubmit: (Preferences) -> Unit) {
    var people by rememberSaveable { mutableStateOf("2") }
    var budget by rememberSaveable { mutableStateOf(if (week) "150" else "20") }
    var diets by rememberSaveable { mutableStateOf(setOf<Diet>()) }
    var avoid by rememberSaveable { mutableStateOf("") }
    var wish by rememberSaveable { mutableStateOf("") }
    var maxMinutes by rememberSaveable { mutableStateOf<Int?>(null) }
    val peopleN = people.toIntOrNull()?.takeIf { it in 1..20 }
    val budgetN = budget.replace(',', '.').toDoubleOrNull()?.takeIf { it > 0 }

    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (week) "Plan de la semaine" else "Repas unique", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(people, { people = it }, Modifier.fillMaxWidth(), label = { Text("Nombre de personnes") },
            isError = peopleN == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
        OutlinedTextField(budget, { budget = it }, Modifier.fillMaxWidth(),
            label = { Text(if (week) "Budget pour la semaine ($)" else "Budget pour le repas ($)") },
            isError = budgetN == null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
        Text("Préférences alimentaires")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Diet.entries.forEach { d ->
                FilterChip(d in diets, { diets = if (d in diets) diets - d else diets + d }, { Text(d.label) })
            }
        }
        Text("Temps de préparation maximum")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(30, 60, null).forEach { m ->
                FilterChip(maxMinutes == m, { maxMinutes = m }, { Text(m?.let { "$it min" } ?: "Peu importe") })
            }
        }
        OutlinedTextField(avoid, { avoid = it }, Modifier.fillMaxWidth(), label = { Text("Ingrédients à éviter (séparés par des virgules)") })
        OutlinedTextField(wish, { wish = it }, Modifier.fillMaxWidth(), label = { Text("Une envie ? (ex. : poulet, mexicain)") }, singleLine = true)
        Button(
            { onSubmit(Preferences(peopleN!!, budgetN!!, diets, avoid.split(',').map { it.trim() }.filter { it.isNotEmpty() }, maxMinutes, wish)) },
            Modifier.fillMaxWidth(), enabled = peopleN != null && budgetN != null,
        ) { Text("Générer") }
    }
}

@Composable
private fun WeekPlan(s: Screen.Week, vm: MainViewModel) = Column(Modifier.fillMaxSize()) {
    val total = s.meals.sumOf { it.cost }
    Text("Votre semaine", style = MaterialTheme.typography.headlineSmall)
    Text("Total estimé : ${total.money()} / budget ${s.prefs.budget.money()}",
        color = if (total > s.prefs.budget) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    if (s.meals.size < 14) Text("${14 - s.meals.size} repas n'ont pas pu être trouvés.", color = MaterialTheme.colorScheme.error)
    Spacer(Modifier.height(8.dp))
    Button({ vm.go(Screen.Shopping(s.meals)) }, Modifier.fillMaxWidth()) { Text("Liste d'épicerie") }
    LazyColumn(Modifier.weight(1f)) {
        items(s.meals) { m ->
            ListItem(
                overlineContent = { Text(m.label) },
                headlineContent = { Text(m.recipe.title) },
                trailingContent = { Text(m.cost.money()) },
                modifier = Modifier.clickable { vm.go(Screen.Detail(m)) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun Options(s: Screen.Options, vm: MainViewModel) = Column(Modifier.fillMaxSize()) {
    Text("Choisissez votre repas", style = MaterialTheme.typography.headlineSmall)
    Text("Budget : ${s.prefs.budget.money()} pour ${s.prefs.people} personne(s)")
    Spacer(Modifier.height(8.dp))
    Button({ vm.back(); vm.generate(s.prefs, false, s.seen) }, Modifier.fillMaxWidth()) { Text("5 autres idées") }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(s.meals) { m ->
            Card({ vm.go(Screen.Detail(m)) }, Modifier.fillMaxWidth()) {
                RemoteImage(m.recipe.image, m.recipe.title, Modifier.fillMaxWidth().height(160.dp))
                Column(Modifier.padding(12.dp)) {
                    Text(m.recipe.title, style = MaterialTheme.typography.titleMedium)
                    Text(listOfNotNull(m.recipe.minutes?.let { "$it min" }, m.cost.money(), host(m.recipe.url)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// shortcut: pas de cache, l'image est retéléchargée à chaque affichage; passer à Coil si ça devient lent.
@Composable
private fun RemoteImage(url: String?, description: String, modifier: Modifier) {
    val image by produceState<ImageBitmap?>(null, url) {
        value = url?.let {
            withContext(Dispatchers.IO) { runCatching { URL(it).openStream().use(BitmapFactory::decodeStream)?.asImageBitmap() }.getOrNull() }
        }
    }
    image?.let { Image(it, description, modifier, contentScale = ContentScale.Crop) }
}

@Composable
private fun MealDetail(m: Meal) {
    val uri = LocalUriHandler.current
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            RemoteImage(m.recipe.image, m.recipe.title, Modifier.fillMaxWidth().height(220.dp))
            Text(m.recipe.title, style = MaterialTheme.typography.headlineSmall)
            Text(listOfNotNull(m.recipe.minutes?.let { "$it min" }, "Coût estimé : ${m.cost.money()}").joinToString(" · "))
            TextButton({ uri.openUri(m.recipe.url) }) { Text("Voir la recette sur ${host(m.recipe.url)}") }
            if (m.ingredients.any { it.product?.estimated == true })
                Text("* prix estimé : produit introuvable sur superc.ca", style = MaterialTheme.typography.bodySmall)
            Text("Ingrédients", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        }
        items(m.ingredients) { ing ->
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(Ingredients.scale(ing.line, m.scale))
                    ing.product?.takeUnless { it.estimated }?.let { p ->
                        Text("SuperC : ${p.name}", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.clickable(enabled = p.url != null) { p.url?.let(uri::openUri) })
                    }
                }
                val p = ing.product
                Text(when {
                    ing.pantry -> "garde-manger"
                    p == null -> ""
                    else -> p.price.money() + if (p.estimated) "*" else ""
                }, style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text("Préparation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp)) }
        items(m.recipe.steps.withIndex().toList()) { (i, step) -> Text("${i + 1}. $step") }
    }
}

@Composable
private fun Shopping(meals: List<Meal>) {
    val list = remember(meals) { Planner.shoppingList(meals) }
    Text("Liste d'épicerie SuperC", style = MaterialTheme.typography.headlineSmall)
    Text("À l'achat (un emballage par produit) : ${list.sumOf { it.first.price }.money()}", fontWeight = FontWeight.Bold)
    LazyColumn {
        items(list) { (p, lines) ->
            ListItem(
                headlineContent = { Text(p.name + if (p.estimated) " *" else "") },
                supportingContent = { Text(lines.joinToString("\n")) },
                trailingContent = { Text(p.price.money()) },
            )
            HorizontalDivider()
        }
    }
}

private fun host(url: String) = runCatching { java.net.URI(url).host.removePrefix("www.") }.getOrDefault(url)
