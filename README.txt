MON PLANIFICATEUR — APPLICATION ANDROID 1.6.5

L’APK Android 1.6.5 prêt à installer est fourni : `Mon-planificateur-Android-1.6.5.apk`. Pour le reconstruire, installe le JDK 17 et le SDK Android 35, puis lance `android\gradlew.bat assembleDebug`. Le fichier généré se trouve dans `android\app\build\outputs\apk\debug\app-debug.apk`.

Téléchargement direct : https://github.com/Walter-001/MonPlanificateur/releases/download/v1.6.5/Mon-planificateur-Android-1.6.5.apk

Le dossier android contient aussi le projet Android Studio et le wrapper Gradle. Le SDK Android 35 et ses licences doivent être installés et acceptés avant la compilation.

Le fichier `index.html` à la racine est la source de l’interface utilisée par Android et iOS. Chaque build natif l’intègre automatiquement; ne modifie pas de copie HTML sous les dossiers de plateforme.

L’interface a été repensée avec une navigation plus légère, un nouveau logo, des cartes pastel et une progression quotidienne. L’application propose un ajout rapide, la prochaine activité en évidence, la reprise de la veille, un aperçu d’import modifiable avec annulation, un indicateur d’analyse, une sauvegarde différée, des alarmes/rappels et l’import de programmes depuis des images, PDF, DOC/DOCX, XLS/XLSX, CSV, TXT et RTF. L’OCR des images et PDF scannés s’exécute sur le téléphone. Aucune donnée n’est envoyée en ligne; le manifeste ne demande pas la permission Internet. Les tâches restent sur le téléphone, sans compte.

Configuration requise : Android 8.0 ou plus récent. Pour les tableaux Excel, l’heure doit être en première colonne et la tâche dans la colonne suivante.

Au premier rappel, autorise les notifications. Android 12+ peut aussi demander l’accès « Alarmes et rappels » pour garantir l’heure précise; sans cet accès, un rappel approximatif est proposé.


La mise à jour 1.5.7 ajoute une quête quotidienne, des XP et une série de jours actifs avec une identité originale vert émeraude, menthe, citron, corail et violet, inspirée des mécaniques de progression des applications d’apprentissage.


Import : l’heure est facultative. Une date seule crée une journée repérée; les libellés associés sont importés sans horaire. Lorsqu’une heure existe, elle est conservée et peut recevoir un rappel.


Mise à jour 1.5.9 : le bouton unique « Importer un programme » est plus visible en haut de l’écran. Les lignes importées avec une date et une heure future reçoivent un rappel préactivé, programmé après confirmation et désactivable dans l’aperçu. Les lignes sans heure restent sans alarme. Une bibliothèque de 60 inspirations change chaque jour; la citation peut aussi être choisie et programmée à une date voulue.


Mise à jour 1.5.9 : Bilan contient un récapitulatif par jour, semaine, mois ou année, avec les activités prévues/terminées et les rappels activés de la période. Le bouton « Programmer » de la rubrique inspiration a été retiré.


Mise à jour 1.5.9 : la bibliothèque de 60 citations est restaurée avec recherche et sélection pour le jour affiché; le bouton « Suivante » avance dans cette bibliothèque. Le bilan accepte deux dates libres pour analyser une période personnalisée et affiche activités et rappels en colonnes verticales.


Mise à jour 1.6.0 : Bilan propose un seul sélecteur Date avec les vues Jour, Semaine, Mois et Année, une date de référence et des flèches horizontales de période. L’accès visuel à la bibliothèque est masqué; « Suivante » parcourt toujours les 60 citations. Interface synchronisée sur Android et iOS.

Mise à jour Android 1.6.1 / iOS 1.1.1 : le menu Date accepte maintenant une plage Personnalisée avec choix de début et de fin. Le bouton citation affiche « Plus de Citations ? » et fait défiler les citations.

Mise à jour 1.6.2 : « Ta quête du jour » et son titre passent au-dessus de « Mon espace de journée » et de ses commandes. L’interface iOS et Android suit la même disposition.

Mise à jour 1.6.3 / 1.1.3 : le bouton d’impression ouvre le dialogue natif, le bilan ne présente plus le sélecteur de date de référence et ses flèches, et chaque rappel permet d’activer ou de couper son. Le menu Date et la plage personnalisée restent disponibles.

Mise à jour 1.6.4 / 1.1.4 : le logo Daily Flow fourni est intégré à l’en-tête, aux icônes de lancement Android et iOS et au manifeste de l’application web installable. L’image originale est conservée dans les projets source.

Mise à jour Android 1.6.5 : un raccourci « Ajouter » est accessible en maintenant l’icône de l’application; les actions principales ont un retour haptique et une meilleure prise en charge des lecteurs d’écran.
