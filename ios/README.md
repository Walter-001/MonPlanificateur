# Mon planificateur — iOS

Version 1.1.4 · application iOS native légère autour de l’interface du planificateur, avec stockage local et sans compte. Projet compatible Xcode, iOS 16 ou ultérieur, iPhone et iPad.

## Ouvrir et installer

1. Sur un Mac avec Xcode 15 ou ultérieur, ouvrez `MonPlanificateur.xcodeproj`.
2. Dans **Signing & Capabilities**, sélectionnez votre équipe Apple et modifiez au besoin l’identifiant `ci.monplanificateur.ios`.
3. Sélectionnez un iPhone ou un simulateur, puis cliquez sur **Run**. Pour un paquet `.ipa`, choisissez **Product > Archive**, puis exportez l’archive avec un profil de signature Apple.

## Fonctions

- Planning quotidien français avec données gardées localement dans le stockage WebKit.
- Rappels à la date et à la minute choisies via les notifications locales iOS, avec les actions « Fait » et « +10 min ».
- Import de planning depuis une image ou un PDF (reconnaissance locale Vision), ainsi que CSV, TXT et RTF. Une date suffit : les libellés associés sont importés sans horaire, et une date seule crée une entrée « Journée repérée dans le document »; les heures reconnues sont conservées. Vérifie l’aperçu avant de confirmer.
- Export et restauration des sauvegardes JSON via l’app Fichiers et les fournisseurs de documents iOS.
- Fonctionne hors ligne après installation.

Les fichiers Word et Excel peuvent être convertis en PDF ou CSV avant import sur iOS. Le traitement reste sur l’appareil. La création d’un IPA signé exige Xcode et un compte/profil de signature Apple; ces outils ne sont pas installés dans l’environnement de génération actuel.

Le fichier `..\index.html` est la source unique de l’interface web. La phase de build Xcode le copie dans le bundle iOS; aucun fichier HTML de remplacement n’est à maintenir dans le projet.

## Mise à jour interface 1.1.2

L’interface partagée avec Android a reçu une progression ludique originale : série de jours actifs, XP du jour, mission personnelle et navigation par sections. La palette émeraude, citron, corail et violet, les cartes en relief et le logo calendrier-coché sont harmonisés.

## Import sans horaire

Une date suffit pour importer une journée. Les libellés qui la suivent deviennent des activités; à défaut, une entrée « Journée repérée dans le document » apparaît sans horaire. Les heures détectées sont conservées et un rappel ne peut être activé que si une heure est renseignée.


## Import et inspirations — 1.0.7

Le bouton « Importer un programme » est maintenant placé dans la barre supérieure. Lorsqu’une ligne importée contient une date et une heure futures, son rappel est préactivé et programmé après confirmation; il reste possible de le désactiver dans l’aperçu. Une ligne sans heure est ajoutée au calendrier sans alarme. Les 60 inspirations originales changent avec la date, peuvent être parcourues manuellement et programmées sur une date choisie.


## Récapitulatif par période — 1.0.8

La rubrique Bilan propose un récapitulatif quotidien, hebdomadaire, mensuel ou annuel : activités prévues et terminées, avec la liste des rappels activés pendant la période. La période est calculée à partir du jour sélectionné. Le bouton « Programmer » a été retiré de la carte d’inspiration.


## Bibliothèque de citations et bilan personnalisable — 1.1.2

La bibliothèque permet de parcourir et de choisir parmi les 60 citations pour le jour affiché. « Suivante » parcourt la même bibliothèque. Le bilan accepte une plage de dates saisie librement ou les périodes jour/semaine/mois/année. Les listes des activités terminées et des rappels apparaissent l’une sous l’autre.


## Présentation du bilan et citations — 1.1.2

Le bilan utilise une seule date de référence. Le sélecteur Date contient Jour, Semaine, Mois et Année; les flèches de période restent horizontales. La sélection de date suit la date affichée dans le planning. La bibliothèque n’est pas exposée dans l’interface; le bouton « Suivante » continue de parcourir les 60 citations.


### Personnalisation du bilan et citations — 1.1.2

Le menu Date comprend Jour, Semaine, Mois, Année et Personnalisée. Le choix Personnalisée affiche les champs Du et Au et calcule le bilan sur la plage choisie. « Suivant » est renommé « Plus de Citations ? » et continue de faire défiler les 60 citations.

### Réorganisation de l’en-tête

« Ta quête du jour » apparaît maintenant au-dessus du bandeau « Mon espace de journée ».

La mise à jour 1.1.3 ouvre l’impression native, retire les flèches et la date de référence de Bilan tout en conservant la plage personnalisée, et règle la sonnerie de chaque rappel.

Le logo Daily Flow est intégré à l’en-tête et à l’icône iOS; les images originale et optimisée sont dans les sources.
