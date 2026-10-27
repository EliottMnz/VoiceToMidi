# CONTEXTE — tout savoir pour développer la partie Drums de VoiceToMidi

*Écrit le 27 octobre 2026 pour l'agent IA (et le développeur) qui reprend la partie **Drums**. À lire en entier avant de toucher au code. Le détail des algorithmes de la partie Mélodie est dans [`docs/ETAT_DU_PROJET.md`](docs/ETAT_DU_PROJET.md) ; ce fichier-ci se concentre sur ce qu'il faut savoir pour construire les Drums et les brancher sur le reste.*

---

## 1. Le projet en une minute

VoiceToMidi est une appli **Android** (Kotlin, Jetpack Compose) qui transforme une mélodie **chantée** en fichier **MIDI**, à importer dans un logiciel de musique (FL Studio pour l'auteur, Eliott).

La vision : un **petit studio de poche pour quelqu'un qui ne connaît pas la musique**.

```
Accueil
├── Mélodie      on chante → l'appli écrit les notes          (fait)
├── Drums        on crée un rythme                             (À FAIRE : c'est ta partie)
└── Composition  des projets ; un projet = des pistes (mélodies + drums) qui jouent ensemble
                 → à terme, une production complète exportable
```

Le flux voulu : faire une mélodie → l'envoyer dans un projet ; faire des drums → les envoyer dans un projet ; dans le projet, tout écouter ensemble, couper (mute) des pistes, exporter.

## 2. Ce que l'auteur attend (à respecter absolument)

- **Simple, clair, ludique.** « Comme une appli Apple » : une action principale par écran, des phrases courtes qui disent quoi faire, pas de jargon. La clarté passe avant la couleur.
- **Aucune connaissance musicale requise.** Pas de termes techniques à l'écran sans explication (on écrit « Caler les notes sur le tempo », pas « quantize »).
- **Pour débutants comme confirmés** : les débutants doivent réussir sans réglage ; les confirmés retrouvent leurs repères de DAW (BPM, mute, MIDI).
- **Le résultat doit être utilisable dans FL Studio** : export MIDI propre, au bon tempo.
- **Langue : français partout** (interface, commentaires, messages de commit, docs). L'interface **tutoie** l'utilisateur (« Touche le bouton rouge et chante ta mélodie »).
- **On peut toujours exporter** ce qu'on a créé en MIDI, et l'envoyer dans la composition.
- L'auteur teste sur une **Samsung Galaxy Tab A8 (SM-X200, Android 13)**, dans une pièce souvent bruyante, et sur l'émulateur.

L'auteur n'a pas encore décidé **comment** on crée un rythme dans le mode Drums (voir § 9 : idées, à valider avec lui).

## 3. Installer, compiler, tester

- **Android Studio** (dernière version). Java et Gradle se téléchargent tout seuls (`gradle/gradle-daemon-jvm.properties` : JDK 25 via foojay).
- Versions : AGP **9.4.1**, Gradle **9.6.0**, Kotlin **2.2.10**, Compose BOM **2026.02.01** (Material 3), **compileSdk/targetSdk 37**, **minSdk 24**. Bibliothèques dans `gradle/libs.versions.toml`.
- Tests sur PC : `./gradlew testDebugUnitTest` (**90 tests**, dont 5 ignorés exprès : mesures sur le jeu de données vocadito, activées par la variable `VOCADITO_DIR`).
  - Sous Windows en ligne de commande : `set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr` d'abord.
  - Après un lancement avec `VOCADITO_DIR`, faire `./gradlew --stop` (le démon garde la variable).
- Tests sur l'appareil : `./gradlew connectedDebugAndroidTest`.
- Lancer : bouton ▶ d'Android Studio (téléphone en débogage USB ou émulateur).
- **Micro de l'émulateur** : désactivé par défaut, il ne livre que du silence (le voyant micro s'allume quand même). Bouton **⋯** de l'émulateur → **Microphone** → « Virtual microphone uses host audio input », à réactiver à chaque démarrage.
- Pièges : la machine de l'auteur est en **locale française** (tout texte pour une machine — JSON, CSV, SVG — doit utiliser `Locale.ROOT`, sinon les décimales sortent avec une virgule). Sous Git Bash, préfixer `adb` par `MSYS_NO_PATHCONV=1` pour les chemins `/sdcard/...`. Plus de pièges : `docs/ETAT_DU_PROJET.md` § 8.

## 4. Organisation du code

```
app/src/main/java/com/example/voicetomidi/
├── MainActivity.kt   navigation entre les écrans (voir § 6)
├── audio/            micro (AudioCaptureManager), import de fichiers (AudioFileDecoder),
│                     voix de démo (SyntheticVoiceSource, DemoScenario), Metronome,
│                     diagnostic des prises (TakeDiagnostics)
├── pitch/            détection de hauteur YIN
├── pipeline/         micro → fenêtres → hauteur + volume, frame par frame
├── notes/            tout l'algorithme musical de la mélodie (MelodyAnalyzer, Viterbi,
│                     justesse, tonalité, Quantizer, PitchBend, NoteEvent...)
├── midi/             MidiFileWriter (écriture SMF à la main)
├── project/          Project, ProjectTrack, TrackKind, ProjectJson (modèle + sauvegarde)
└── ui/
    ├── HomeScreen.kt          accueil (3 cartes) + enum Destination + DrumsScreen (vide)
    ├── RecordingScreen.kt     mode Mélodie (écran)        ┐ partie Mélodie :
    ├── RecordingViewModel.kt  mode Mélodie (logique)      │ éviter d'y toucher sans
    ├── SingingWidgets.kt      piano roll, pavé, bouton    ┘ prévenir Eliott
    ├── CompositionScreen.kt   liste des projets, écran d'un projet, « Envoyer dans… »
    ├── ProjectsViewModel.kt   projets : sauvegarde, mute, lecture synchro
    └── theme/                 couleurs, typo, formes, composants réutilisables

app/src/test/java/...          tests JUnit sur PC (mêmes paquets)
app/src/test/resources/prises  mesures de vraies prises de l'auteur (pas le son)
```

## 5. Le design system (à réutiliser tel quel)

Thème sombre unique (`ui/theme/`) :

- **Couleurs** (`Color.kt`) : fond `EmoBackground` #0D0B0E, cartes `EmoSurface` #17141A, éléments secondaires `EmoSurfaceVariant` #241E27, **accent rouge** `EmoAccent` #D12442, texte #F7F2F3, texte discret `EmoTextMuted` #9E959B, séparateurs `EmoSeparator`. Toujours passer par `MaterialTheme.colorScheme` (`primary` = rouge, `surface`, `surfaceVariant`, `onSurfaceVariant`, `outline`).
- **Typo** (`Type.kt`) : police système uniquement. `displayMedium` (52 sp gras) = grand texte d'état au centre ; `headlineSmall` = titre d'accueil ; `titleLarge`/`titleMedium` = titres de cartes ; `bodyLarge/Medium/Small` = texte ; `labelLarge` = boutons. Phrases en casse normale, pas de MAJUSCULES (sauf petits titres de sections de réglages).
- **Formes** (`Theme.kt`) : coins arrondis — `small` 12 dp, `medium` 16, `large` 22 (cartes), `extraLarge` 28.
- **Composants** (`EmoComponents.kt`) :
  - `EmoButton` : bouton rouge plein, **l'action principale** (une seule par écran).
  - `EmoOutlinedButton` : bouton gris à texte rouge, actions secondaires.
  - `EmoCard` : carte arrondie qui regroupe un bloc.
  - `ScreenHeader(title, onBack, backLabel = "Accueil", action)` : barre du haut « ‹ Accueil · Titre · action ».
  - `SettingsSection`, `SettingsSwitchRow`, `SettingsDivider` : réglages façon iOS (groupe titré + lignes à interrupteur avec une explication courte).
- **Icônes** : pas de bibliothèque d'icônes dans le projet. Les icônes sont **dessinées en `Canvas`** (exemples : `drawDrumsIcon`, `drawMelodyIcon` dans `HomeScreen.kt` ; haut-parleur `MuteButton` et lecture/pause `Transport` dans `CompositionScreen.kt` ; bouton d'enregistrement `RecordButton` dans `SingingWidgets.kt`).
- **Habitudes d'interface** : options dans un **panneau qui monte du bas** (`ModalBottomSheet`, voir `SettingsSheet`, `NotePickerSheet`, `SendToProjectSheet`) ; confirmation (`AlertDialog`) avant toute suppression définitive ; un `Toast` court après un envoi (« « Mélodie 2 » ajoutée à « Projet 1 » ») ; une phrase d'aide sous un élément interactif (« Touche une note pour la changer · glisse pour choisir où écouter ») ; marges de 20 dp sur les côtés.

## 6. Navigation

Pas de bibliothèque de navigation : `MainActivity` garde l'écran courant (`Destination`, défini dans `HomeScreen.kt` : `HOME, COMPOSITION, PROJECT, MELODY, DRUMS`) dans un `rememberSaveable`, plus deux ids :

- `openProjectId` : projet ouvert dans l'écran `PROJECT` ;
- `melodyTargetId` : projet qui a lancé le mode Mélodie via « Ajouter une mélodie ». Dans ce cas le mode Mélodie affiche « ‹ Projet 1 » en retour et un bouton « Ajouter à « Projet 1 » » qui envoie directement puis revient au projet.

Les transitions (`AnimatedContent`) glissent selon une **profondeur** (`depth()` : HOME 0, COMPOSITION/DRUMS 1, PROJECT 2, MELODY 1 ou 3 s'il vient d'un projet). Le retour Android (`BackHandler`) suit la même logique (`back`).

**Pour les Drums**, il faudra faire comme la Mélodie : ajouter un `drumsTargetId` (Drums lancé depuis un projet → envoi direct + retour au projet), donner une profondeur à DRUMS quand il vient d'un projet, et remplacer `DrumsScreen` (aujourd'hui un simple « Bientôt » dans `HomeScreen.kt`) par le vrai écran, dans son propre fichier.

`ProjectsViewModel` est **unique pour toute l'activité** (`viewModel()` dans `MainActivity`) et passé aux écrans ; il est branché au stockage par `projects.attach(filesDir, cacheDir)`. `RecordingViewModel` est lui aussi à l'échelle de l'activité (la prise en cours survit aux allers-retours). Le futur `DrumsViewModel` devrait suivre le même modèle.

## 7. La Composition : modèle, sauvegarde, lecture

### Modèle (`project/Project.kt`)

```kotlin
enum class TrackKind { MELODY, DRUMS }          // DRUMS existe déjà, inutilisé

data class ProjectTrack(
    val id: Long, val name: String, val kind: TrackKind,
    val notes: List<NoteEvent>,                 // à partir de 0 ms
    val muted: Boolean = false
)

data class Project(val id: Long, val name: String, val tracks: List<ProjectTrack> = emptyList())
// Project.nextMelodyName() → "Mélodie 1", "Mélodie 2"... sans doublon (même après un retrait)

data class NoteEvent(val midiNote: Int, val startTimeMs: Long, val endTimeMs: Long, val velocity: Int)
```

**Les temps sont en millisecondes, pas en temps musicaux**, et **toutes les pistes d'un projet commencent à 0 ms**. Une mélodie envoyée sans tempo de projet est décalée pour commencer à sa 1re note (`RecordingViewModel.melodyForComposition()`) ; avec un tempo donné avant la prise, elle commence au 1er temps après le décompte.

### Sauvegarde (`ProjectJson`)

Un seul fichier `projets.json` dans `filesDir`, réécrit à chaque changement (écriture dans un `.tmp` puis renommage). Format **version 1** :

```json
{"version":1,"projects":[{"id":1,"name":"Projet 1","tracks":[
  {"id":3,"name":"Mélodie 1","kind":"MELODY","muted":false,
   "notes":[[60,0,450,90],[62,500,1200,70]]}]}]}
```

Une note = `[hauteur MIDI, début ms, fin ms, vélocité]`. Pour ajouter des données (motif de drums, tempo du projet...) : **ajouter des champs lus avec `opt...` et une valeur par défaut**, pour que les projets déjà sauvegardés se relisent ; monter `version` si le sens d'un champ change. Un fichier illisible est renommé `projets.json.illisible`, jamais écrasé. Test : `ProjectJsonTest` (la vraie bibliothèque `org.json` est ajoutée **pour les tests seulement**, celle d'Android n'est qu'une coquille vide sur PC).

### Ce que propose `ProjectsViewModel`

`createProject(name)`, `renameProject`, `deleteProject`, `addMelody(projectId, notes): String` (renvoie le nom de la piste), `toggleMute`, `removeTrack`, `togglePlay(projectId)` (lecture/pause, position gardée), `seek`, `resetPosition`, `stop`, `suggestedProjectName()`, `project(id)`. État : `ProjectsUiState(projects, playingProjectId, positionMs)`.

À ajouter pour les drums : un `addDrums(projectId, ...)` sur le modèle d'`addMelody` (nom « Drums 1 », « Drums 2 »... : écrire un `nextDrumsName()` comme `nextMelodyName()`).

### Lecture synchro et canaux MIDI

`ProjectsViewModel.play()` écrit **toutes les pistes non coupées dans un seul fichier MIDI** (`MidiFileWriter().writeTogether(audible.map { it.notes })`) et le joue avec `MediaPlayer` (synthé General MIDI intégré à Android, « Sonivox »). Un seul fichier = une seule horloge : la synchro est garantie. Couper une piste en pleine écoute réécrit le fichier et reprend à la même position.

`writeTogether` met la piste n° i sur le canal `MELODIC_CHANNELS[i]`, qui **saute volontairement le canal 10 (index 9)**, réservé aux percussions en General MIDI. **Pour les drums, il faut l'inverse : les écrire sur le canal 10 (index 9)**, où chaque hauteur MIDI est un instrument de batterie. Il faudra donc faire évoluer `writeTogether` pour qu'il connaisse le type de chaque piste (par exemple `writeTogether(parts: List<Pair<TrackKind, List<NoteEvent>>>)` : mélodies sur les canaux mélodiques, drums sur le canal 9), et adapter `ProjectsViewModel.play()`. Ajouter un test dans `MidiFileWriterTest` (les tests relisent les fichiers avec `javax.sound.midi`, disponible sur PC).

Repères General MIDI (canal 10) : 35/36 grosse caisse, 37 rimshot (side stick), 38/40 caisse claire, 39 clap, 42 charley fermé, 44 charley pédale, 46 charley ouvert, 41/43/45/47/48/50 toms, 49/57 crash, 51/59 ride, 54 tambourin, 56 cloche (cowbell). Le synthé d'Android lit en principe le canal 10 comme une batterie General MIDI ; **ce n'est pas encore vérifié dans ce projet** : à tester sur appareil en premier.

`MidiFileWriter` (`midi/`) : `MidiFileWriter(ticksPerQuarterNote = 480, beatsPerMinute = 120)`, `write(notes, pitchBends)` (une piste, canal 0), `writeTogether(parts)`. Les temps en ms sont convertis en ticks au tempo donné : un fichier écrit à 120 BPM garde les bons instants, mais pour qu'il tombe sur la grille de FL Studio il faut l'écrire **au tempo du projet de l'utilisateur** (c'est ce que fait l'export du mode Mélodie, qui demande le BPM).

### Affichage d'une piste

`CompositionScreen.kt` : `TrackRow` (bouton muet, nom, « 34 notes » ou « coupée », bande de notes, menu « ••• » → « Retirer du projet ») et `NoteStrip` (notes en barres sur une ligne de temps commune à tout le projet, rangées par hauteur ; toucher = écouter d'ici). **Pour les drums, une bande rangée par hauteur ne parle pas** : prévoir un affichage par instrument (une ligne grosse caisse, une ligne caisse claire, une ligne charley), et un résumé adapté (« 4 mesures » plutôt que « 34 notes »). Le bouton « Ajouter des drums · bientôt » (désactivé) de l'écran d'un projet est à activer.

## 8. Ce qui existe déjà et peut servir aux drums

- **Tempo et horloge** : `audio/Metronome.kt` joue un décompte et des clics continus dans un flux `AudioTrack` écrit d'avance ; `beatNanoTime(beat)` et `beatAt(nanoTime)` donnent l'instant **réellement entendu** de chaque temps (horloge du haut-parleur, latence comprise). Le clic est un **bruit court synthétisé** (`metronomeClick()`, aucun fichier son) : même principe possible pour une grosse caisse, une caisse claire ou un charley synthétisés, sans souci de licence.
- **Tempo du projet** (mode Mélodie, réglages) : `RecordingUiState.projectBpm` (40–300 BPM) déclenche 4 clics de décompte ; la prise est alors calée sur le 1er temps (`beatOriginMs`). Ce tempo est **un réglage du mode Mélodie, pas encore du projet**.
- **Quantification** : `notes/Quantizer.kt` — `quantize(notes, gridMs)` cale les débuts et fins sur une grille. `estimateGridMs` / `quantizeAuto` (médiane des écarts) existent mais ne sont plus utilisés.
- **Lecture d'un MIDI** : `MediaPlayer` sur un `.mid` écrit dans `cacheDir` (voir `ProjectsViewModel.play()` et `RecordingViewModel.togglePreview()`). **Latence de démarrage notable** (création + préparation du lecteur, de l'ordre d'une fraction de seconde) : bien pour écouter un morceau, **trop lent pour des pads qu'on tape en rythme**. Pour du jeu en direct, utiliser `SoundPool` (échantillons courts) ou un `AudioTrack` en flux avec des sons synthétisés.
- **Micro et analyse** : si les drums se font au beatbox, tout le pipeline micro existe (`AudioCaptureManager`, `VoiceToMidiPipeline` : fenêtres de 2048 échantillons, pas de 512 à 44,1 kHz, volume RMS et hauteur YIN par frame). La détection de *frappes* (attaques) et leur classement (grave = grosse caisse, bruit aigu = charley...) restent à écrire.

## 9. Pistes pour le mode Drums (idées, **à valider avec Eliott**)

Rien n'est décidé. Quelques directions, de la plus simple à la plus ambitieuse :

1. **Grille de pas (step sequencer)** : 16 cases par mesure × quelques instruments (grosse caisse, caisse claire, charley, clap). On touche une case pour l'allumer ; une boucle tourne au tempo choisi. Très lisible, rien à savoir, résultat toujours en rythme. Des **motifs prêts** (« Boom bap », « Trap », « Pop »...) pour démarrer en un tap.
2. **Pads à taper** : 4 à 8 gros pads, on enregistre ce qu'on tape sur un métronome, puis calage sur la grille. Demande des sons à faible latence (§ 8).
3. **Beatbox → drums** : on fait « boum tchak » au micro, l'appli reconnaît les frappes et les transforme en batterie. Le plus magique, le plus difficile (et à mesurer, comme la mélodie).

Dans tous les cas, ce qui rendra la composition cohérente : **un tempo par projet**. Aujourd'hui chaque piste démarre à 0 ms sans tempo commun ; des drums (forcément au tempo) et une mélodie chantée sans métronome ne tomberont pas ensemble. Proposition faite à Eliott (pas encore tranchée, à coordonner) : le projet porte un BPM ; le mode Mélodie lancé depuis un projet utilise ce BPM (décompte, métronome) ; le mode Drums aussi ; toutes les pistes partent du 1er temps. Le champ serait à ajouter dans `Project` + `ProjectJson` (lu avec une valeur par défaut).

Autres attentes déjà exprimées pour la Composition, qui concernent aussi les drums : mute par piste (fait), tout jouer ensemble et synchro (fait), plus tard export du projet entier en un MIDI, un son par piste, décaler une piste, volume, solo.

## 10. Conventions de code

- **Kotlin idiomatique, commentaires en français**, densité de commentaires comme le code existant : un KDoc court au-dessus de chaque classe/fonction publique qui dit *pourquoi*, et un commentaire sur les choix non évidents (« le clic est un bruit et non un bip, sinon YIN y verrait une note »). Pas de commentaire qui répète le code.
- Noms en anglais pour le code (`ProjectsViewModel`, `togglePlay`), textes et commentaires en français.
- État d'écran : `data class ...UiState` + `MutableStateFlow` dans un `ViewModel`, exposé en `StateFlow`, lu par `collectAsState()`.
- Constantes nommées en haut ou en bas de fichier avec un commentaire (`private const val PREVIEW_LEAD_IN_MS = 150L`).
- **Pas de nouvelle dépendance sans raison forte** (le projet n'a même pas de bibliothèque d'icônes ni de navigation). Les versions vont dans `gradle/libs.versions.toml`.
- **Tests JUnit sur PC pour toute logique** (modèle, MIDI, algorithmes). La logique ne doit pas dépendre d'Android pour être testable (comme `notes/`, `midi/`, `project/`).
- Toute idée d'algorithme se **mesure** avant d'être gardée (voir comment la partie Mélodie sépare réglage et test dans `docs/ETAT_DU_PROJET.md` § 5) : l'auteur préfère « on a mesuré, ça fait mieux » à « ça a l'air mieux ».

## 11. Git et travail à deux

- Dépôt : https://github.com/EliottMnz/VoiceToMidi (public), branche principale `main`.
- **Travailler sur une branche** (ex. `drums`) et proposer une pull request vers `main`, pour ne pas se marcher dessus avec Eliott qui travaille sur la Mélodie et la Composition.
- Fichiers que les Drums vont toucher et qui sont partagés (à modifier avec soin, en prévenant) : `MainActivity.kt` (navigation), `HomeScreen.kt` (carte Drums, `Destination`), `CompositionScreen.kt` (bouton « Ajouter des drums », affichage d'une piste de drums), `ProjectsViewModel.kt` (`addDrums`, lecture), `project/Project.kt` (modèle, JSON), `midi/MidiFileWriter.kt` (canal 10). Le reste du mode Drums dans de **nouveaux fichiers** (`ui/DrumsScreen.kt`, `ui/DrumsViewModel.kt`, un paquet `drums/` pour la logique testable).
- **Messages de commit en français**, au présent, 3e personne du singulier, titre court qui dit ce que ça apporte (« Ajoute l'écoute au piano avant l'export », « Organise la Composition en projets, avec lecture synchro et mute par piste »), puis une liste à tirets qui explique quoi et pourquoi.
- Ne jamais committer : `local.properties`, `.idea/`, `build/`, fichiers `.apk`, clés de signature (déjà dans `.gitignore`).

## 12. Avant de considérer une fonctionnalité finie

1. `./gradlew testDebugUnitTest` passe (90 tests aujourd'hui, + les tiens).
2. Lancée dans l'émulateur ou sur un téléphone, parcourue à la main, y compris : retour Android, appli mise en arrière-plan (le son doit s'arrêter, voir `ON_STOP` dans `RecordingScreen`), fermeture puis réouverture (les projets doivent être intacts).
3. Le son vérifié **à l'oreille sur un vrai appareil** (un agent ne peut pas l'entendre : le dire clairement plutôt que de supposer).
4. `docs/ETAT_DU_PROJET.md` et ce fichier mis à jour si l'architecture ou le modèle de données change.
