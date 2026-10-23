# Mon planificateur — application Android

Version source 1.6.9 · application hors ligne, sans compte · Android 8.0 (API 26) ou ultérieur.

## Installation

Aucun APK précompilé n’est fourni dans ce dossier source. Pour générer et installer la version de développement, compile le projet avec les prérequis indiqués ci-dessous; l’APK sera créé dans `app/build/outputs/apk/debug/app-debug.apk`. Un APK de développement déjà installé ne peut être mis à jour que par un paquet signé avec la même clé.

Dernière version publiée (1.6.5) : https://github.com/Walter-001/MonPlanificateur/releases/download/v1.6.5/Mon-planificateur-Android-1.6.5.apk

## Utilisation

- Le planning accepte plusieurs activités à la même heure, des horaires précis à la minute, un commentaire et une répétition quotidienne, les jours ouvrés ou hebdomadaire.
- La plage affichée se règle de 00 h à 24 h. Les activités hors plage restent visibles dans leur propre liste.
- Les rappels peuvent être activés sur une activité. La notification propose « Fait » et « +10 min ». Android demande les notifications au premier rappel et peut demander l’autorisation spéciale « Alarmes et rappels » pour une heure précise; à défaut, l’application conserve un rappel approximatif.
- Un programme peut être extrait depuis une image, un PDF, DOC/DOCX, XLS/XLSX, CSV, TXT ou RTF. Une date suffit : les libellés associés sont importés sans horaire, et une date seule crée une entrée « Journée repérée dans le document »; les heures reconnues sont conservées. Vérifie l’aperçu avant de confirmer; l’extraction reste locale.
- Les onglets donnent un accès direct au planning, aux priorités, au bien-être, aux notes et au bilan. Les jours changent avec un glissement horizontal ou les flèches de semaine.
- Les données restent sur le téléphone. « Exporter » enregistre un fichier JSON via le sélecteur Android; « Restaurer » réimporte cette sauvegarde et ses rappels.

## Compilation

Prérequis : JDK 17 et SDK Android 35. Dans ce dossier, lance :

```bash
./gradlew assembleDebug
```

L’APK est généré dans `app/build/outputs/apk/debug/app-debug.apk`. Sur Windows, utilise `gradlew.bat assembleDebug`.

Le fichier `..\index.html` est la source unique de l’interface web; la compilation copie cette version dans les ressources Android.

`MainActivity.kt` est le point d’entrée Kotlin déclaré dans le manifeste. Il hérite de `MainActivityCore.java`, qui conserve l’implémentation Android existante et ses intégrations Java.

Sur Android 7.1+, maintiens l’icône de l’application appuyée pour ouvrir directement « Ajouter une tâche ». Les confirmations de tâches et rappels utilisent aussi le retour haptique système lorsqu’il est activé.

## Mise à jour Android 1.6.7

La palette Android associe un vert menthe vif à des accents bleu, violet, corail et soleil. La prochaine activité et le bouton d’ajout rapide ont leurs propres dégradés; le thème sombre système conserve des couleurs et contrastes lisibles. Les barres système suivent également le thème. Les changements restent limités à Android; l’interface iOS est inchangée.

## Mise à jour Android 1.6.8

Depuis les détails d’une activité, « Ajouter à l’agenda du téléphone » demande l’autorisation Android de lire les calendriers disponibles et d’y écrire, uniquement lorsque tu utilises cette action. Après autorisation, choisis le calendrier cible; l’activité (titre, date, heure, note et répétition) y est ajoutée directement. L’application ne lit pas les événements existants.

## Mise à jour interface 1.6.2

L’accueil utilise une progression ludique originale : série de jours actifs, XP quotidien calculé à partir des tâches terminées, mission du jour et navigation par sections. La palette émeraude, citron, corail et violet, les cartes en relief et le logo calendrier-coché ont été harmonisés. Les données et fonctions existantes sont conservées.

## Import sans horaire

Une date suffit pour importer une journée. Les libellés qui la suivent deviennent des activités; à défaut, une entrée « Journée repérée dans le document » apparaît sans horaire. Les heures détectées sont conservées et un rappel ne peut être activé que si une heure est renseignée.


## Import et inspirations — 1.5.7

Le bouton « Importer un programme » est maintenant placé dans la barre supérieure. Lorsqu’une ligne importée contient une date et une heure futures, son rappel est préactivé et programmé après confirmation; il reste possible de le désactiver dans l’aperçu. Une ligne sans heure est ajoutée au calendrier sans alarme. Les 60 inspirations originales changent avec la date, peuvent être parcourues manuellement et programmées sur une date choisie.


## Récapitulatif par période — 1.5.8

La rubrique Bilan propose un récapitulatif quotidien, hebdomadaire, mensuel ou annuel : activités prévues et terminées, avec la liste des rappels activés pendant la période. La période est calculée à partir du jour sélectionné. Le bouton « Programmer » a été retiré de la carte d’inspiration.


## Bibliothèque de citations et bilan personnalisable — 1.6.2

La bibliothèque permet de parcourir et de choisir parmi les 60 citations pour le jour affiché. « Suivante » parcourt la même bibliothèque. Le bilan accepte une plage de dates saisie librement ou les périodes jour/semaine/mois/année. Les listes des activités terminées et des rappels apparaissent l’une sous l’autre.


## Présentation du bilan et citations — 1.6.2

Le bilan utilise une seule date de référence. Le sélecteur Date contient Jour, Semaine, Mois et Année; les flèches de période restent horizontales. La sélection de date suit la date affichée dans le planning. La bibliothèque n’est pas exposée dans l’interface; le bouton « Suivante » continue de parcourir les 60 citations.


### Personnalisation du bilan et citations — 1.6.2

Le menu Date comprend Jour, Semaine, Mois, Année et Personnalisée. Le choix Personnalisée affiche les champs Du et Au et calcule le bilan sur la plage choisie. « Suivant » est renommé « Plus de Citations ? » et continue de faire défiler les 60 citations.

### Réorganisation de l’en-tête

« Ta quête du jour » apparaît maintenant au-dessus du bandeau « Mon espace de journée ».

## Mise à jour 1.6.3

Le bouton d’impression ouvre le dialogue natif. Dans Bilan, les flèches et la date de référence ont été retirées; les périodes prédéfinies suivent le jour choisi dans le planning, et la plage Personnalisée reste disponible. Chaque rappel permet d’activer ou couper sa sonnerie de notification.

Le logo Daily Flow fourni est utilisé dans l’interface et l’icône adaptative Android. L’original et l’emblème optimisé sont conservés dans les sources.
