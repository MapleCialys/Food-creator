# Food Creator

Application Android de planification de repas.

- **Repas unique** : une idée de repas qui respecte le budget du repas.
- **Semaine** : 14 repas (midi et soir, du lundi au dimanche) et une liste d'épicerie.

L'app pose quelques questions : nombre de personnes, budget, préférences alimentaires
(végétarien, végane, pescétarien, sans porc, sans gluten, sans lactose, sans noix),
temps maximum, ingrédients à éviter et une envie facultative.

## Fonctionnement

- **Recettes** : recherche sur Ricardo, Trois fois par jour et Marmiton, puis lecture du bloc
  schema.org `Recipe` (JSON-LD) de la page. Chaque recette affiche un lien vers sa source.
- **Prix** : chaque ingrédient est cherché sur `superc.ca` (recherche des allées). Si le site
  ne répond pas ou ne trouve rien, un prix moyen estimé est affiché avec un `*`.
  Le sel, le poivre, l'huile et l'eau comptent comme garde-manger et ne sont pas facturés.
- **Coût d'un repas** : prix des produits × (personnes / portions de la recette).
- Les quantités des ingrédients sont ajustées au nombre de personnes.

## Structure

- `core/` : logique en Kotlin pur (recettes, prix SuperC, filtres alimentaires, planification), avec tests.
- `app/` : interface Android (Jetpack Compose).

## Compiler

```
./gradlew :core:test :app:assembleDebug
```

L'APK se trouve dans `app/build/outputs/apk/debug/`. GitHub Actions le compile à chaque push
et le publie en Release :
[télécharger la dernière version](https://github.com/MapleCialys/Food-creator/releases/latest/download/FoodCreator.apk).

## Limites connues

- Les adresses de recherche des sites et la structure HTML de superc.ca sont dans
  `core/src/main/kotlin/com/foodcreator/core/Recipes.kt` et `SuperC.kt`; elles n'ont pas pu être
  vérifiées en direct et devront être ajustées si un site change.
- Les prix SuperC sont ceux du magasin par défaut du site (pas de choix de succursale).
- Le filtre alimentaire repose sur des mots-clés : en cas d'allergie grave, relisez la recette.
