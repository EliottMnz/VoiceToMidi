# VoiceToMidi — état du projet

*Mis à jour le 27 octobre 2026 (refonte de l'interface, écoute au piano, correction des notes, accueil à trois modes). Ce document s'adresse à un humain ou à un agent IA qui reprend le projet : il explique le but, ce qui existe, comment ça marche, comment c'est mesuré, et ce qui reste ouvert.*

---

## 1. Le but

Une appli Android qui **transforme une mélodie chantée en fichier MIDI**, à importer ensuite dans un logiciel de musique (FL Studio pour l'auteur).

Contraintes qui guident tous les choix :

- **Aucune connaissance musicale requise.** Pas de choix de gamme, pas de termes techniques à l'écran : on chante, on arrête, on récupère ses notes.
- **Pour les débutants comme pour les confirmés.** Les débutants chantent faux, hésitent, dérivent ; les confirmés font des ornements, des notes hors gamme volontaires et des sauts d'octave. L'appli ne doit casser ni les uns ni les autres.
- **Le résultat doit être utilisable dans un DAW** : les bonnes notes, au bon moment, sans notes parasites.

## 2. Ce que fait l'appli aujourd'hui

L'accueil propose trois modes : **Mélodie** (tout ce qui suit), **Composition** et **Drums** (annoncé, vide pour l'instant). La Composition contient des **projets**, sauvegardés sur le téléphone (`projets.json`, via `ProjectJson`). Un projet regroupe des pistes (mélodies, plus tard drums) qui démarrent toutes à 0 ; « Tout écouter » écrit les pistes non coupées dans **un seul fichier MIDI, une piste par canal** (`MidiFileWriter.writeTogether`), donc elles jouent forcément synchro. Chaque piste a un bouton muet (on peut couper en pleine écoute sans perdre la position), toucher une piste place la lecture. Envoyer une mélodie depuis le mode Mélodie ouvre « Envoyer dans… » (projet existant ou nouveau) ; « Ajouter une mélodie » depuis un projet ouvre le mode Mélodie, qui envoie alors directement dans ce projet puis y revient. Le but à terme : faire une mélodie, des drums, et assembler un morceau complet dans la composition.

Dans le mode Mélodie :

- **Enregistrement au micro** (PCM 16 bits mono, 44,1 kHz), avec une **petite courbe en direct** de la voix (mini piano roll sur 2 octaves, nom des notes en français, note courante en surbrillance).
- **Gros bouton "maintiens pendant la note"** (facultatif) : l'utilisateur appuie au début de chaque note et relâche à la fin. Le premier appui lance l'enregistrement s'il n'a pas commencé.
- **Analyse complète après l'arrêt** (pas en temps réel) : justesse, découpage en notes, tonalité, nettoyage.
- **Import d'un fichier audio** (bouton « Importer ») : m4a du Dictaphone de l'iPhone, mp3, wav... décodés par `MediaExtractor`/`MediaCodec`, convertis en mono 44,1 kHz, puis analysés exactement comme une prise au micro. Permet de tester sa voix sans téléphone Android (enregistrer sur iPhone, glisser le fichier sur la fenêtre de l'émulateur, l'importer).
- **Export MIDI** (SMF format 0). À l'export, l'appli demande **le BPM du projet FL Studio** (pré-rempli avec le tempo du projet s'il a été donné) : le MIDI est écrit à ce tempo, donc les notes tombent au bon endroit à l'import.
- **Options repliables, toutes facultatives** :
  - **Tempo du projet (BPM)** : s'il est donné, chaque prise au micro commence par **4 clics de décompte** (`Metronome`), et le MIDI exporté commence sur le 1er temps qui suit. L'instant où chaque clic est *entendu* est connu par l'horloge du haut-parleur (`AudioTrack.getTimestamp`), et placé dans l'enregistrement par l'horloge du micro. Le clic est un bref bruit sans hauteur, pour que le micro ne le prenne pas pour une note. Avec un tempo seulement : « Entendre le métronome pendant la prise » et « Caler les notes sur le tempo » (quantification sur les doubles-croches). Sans tempo, pas de quantification : la grille ne tomberait pas sur les temps chantés.
  - **Gamme de mon projet** (automatique par défaut, ou une des 24 gammes majeures / mineures) : chaque note est ramenée à la note de la gamme la plus proche (voir 5.5).
  - **« Rendre plus juste »** (voir 4.3 et 4.7) et **pitch bend** (vibrato).
  - Le tap tempo a été retiré (il ne servait qu'à pré-remplir le BPM).
- **Mode démo** sans micro : des voix synthétiques, dont « Au clair de la lune » chanté par un débutant simulé (~30 s, chant 30 cents trop bas, fausses notes légères, hésitations). L'appli y retrouve les 33 notes exactes ; l'export ouvert dans FL Studio a été vérifié par l'auteur.
- **Diagnostic des prises réelles** : chaque prise au micro est sauvegardée (WAV brut + appuis + notes + mesures frame par frame) dans le dossier `prises` de l'appli, les 10 dernières sont gardées. Sert à comprendre un résultat étrange après coup.
- **Garde-fous de volume** : en dessous de ~-50 dBFS, le son est traité comme du silence (sinon YIN trouve des "notes" dans le bruit de fond) ; une prise entièrement trop faible affiche un message au lieu de notes.
- **Source micro `VOICE_RECOGNITION`** (sinon `MIC`) : gain calibré, sans gain automatique ni débruitage. **Jamais `UNPROCESSED`** : sur la Galaxy Tab A8 (SM-X200, Android 13) de l'auteur, l'appareil la déclare non prise en charge mais l'ouvre quand même et livre un son ~35 dB trop faible (aucune note détectée). Le test `MicSourceProbe` (sur l'appareil, lancé à la main) mesure chaque source en jouant un La par le haut-parleur.
- **Datation des appuis** : par l'horloge du micro (`AudioRecord.getTimestamp`), qui inclut la latence matérielle ; à défaut, estimation par les frames reçues. Sur la tablette, dater les appuis avec « la dernière frame analysée » les plaçait ~300 ms trop tôt. Les appuis de moins de 80 ms (ex. celui qui lance l'enregistrement) ne sont pas des notes.

- **Vue d'ensemble de la prise** : à la fin de l'analyse, la courbe se dézoome sur toute la prise et les notes trouvées apparaissent en barres (piano roll). Toucher une note ouvre une **roue de notes** : chaque note qui passe au centre est jouée au piano et redessinée dans le graphique ; on valide ou on supprime la note. Le vibrato exporté (pitch bend) est mesuré autour de la hauteur trouvée par l'analyse, donc une note corrigée garde son vibrato.
- **Écoute au piano** : le fichier MIDI tel qu'il serait exporté est joué par le synthé General MIDI intégré à Android (`MediaPlayer` lit les `.mid`). Un curseur sur la vue d'ensemble choisit d'où écouter (toucher pour le placer, glisser pour le déplacer) ; la note jouée s'allume.
- **Envoi dans la composition** : les notes exportées (mêmes options), décalées pour commencer à 0 si aucun tempo de projet n'a été donné.

Interface : Jetpack Compose, thème sombre (noir, rouge sang), police système, coins arrondis, une seule action principale par écran (bouton d'enregistrement rond en bas, pavé « maintiens » au-dessus, réglages dans un panneau qui monte du bas). Changer « Rendre plus juste » ou la gamme ré-analyse la prise et **efface les corrections faites à la main**.

## 3. Architecture

```
app/src/main/java/com/example/voicetomidi/
├── audio/      capture micro (AudioCaptureManager), fenêtrage (PcmWindower),
│               voix synthétique de démo (SyntheticVoiceSource, DemoScenario),
│               métronome, diagnostic des prises (TakeDiagnostics),
│               import de fichiers audio (AudioFileDecoder, FileAudioSource)
├── pitch/      détection de hauteur YIN (YinPitchDetector)
├── pipeline/   VoiceToMidiPipeline : micro -> fenêtres -> YIN + RMS -> frames
├── notes/      tout l'algorithme musical :
│               MelodyAnalyzer (chef d'orchestre de l'analyse finale,
│                 étapes activables via AnalysisSteps : AS_SUNG par défaut, CORRECTED)
│               TuningEstimator (justesse / dérive)
│               KeyDetector (tonalité)
│               ViterbiNoteSegmenter (découpage HMM)
│               NoteSegmenter (découpage temps réel, pour l'affichage en direct)
│               Quantizer, PitchBendEvent, Scale, PitchMath
├── midi/       MidiFileWriter (écriture SMF à la main, javax.sound.midi n'existe pas sur Android)
└── ui/         HomeScreen (accueil, écran Drums), RecordingScreen + RecordingViewModel (mode Mélodie),
                SingingWidgets (courbe / piano roll, pavé, bouton d'enregistrement),
                CompositionScreen + CompositionViewModel (pistes reçues), thème

app/src/test/java/com/example/voicetomidi/
├── notes/, midi/, pitch/, pipeline/   tests unitaires et d'intégration
└── benchmark/  VocaditoBenchmark (mesure sur vraies voix), VocaditoMidiExport (export pour DAW + page HTML)
```

Flux de données :

1. `AudioCaptureManager` lit le micro par paquets.
2. `VoiceToMidiPipeline` découpe en fenêtres de **2048 échantillons (~46 ms), pas de 512 (~11,6 ms)**. Pour chaque fenêtre : volume RMS et hauteur YIN. Cela donne une `RawPitchFrame(timestampMs, frequencyHz, confidence, rmsAmplitude)`.
3. Pendant l'enregistrement, `NoteSegmenter` (règles avec hystérésis) donne une note "stabilisée" pour l'affichage en direct uniquement.
4. À l'arrêt, `MelodyAnalyzer.analyze(frames, taps)` produit les notes finales. C'est le cœur du projet.

## 4. Les algorithmes, étape par étape

### 4.1 Détection de hauteur : YIN

YIN (de Cheveigné & Kawahara, 2002) : fonction de différence, normalisation cumulative, premier minimum sous un seuil de 0,15, interpolation parabolique. Plage 50 à 1500 Hz. Confiance = 1 − valeur du minimum. Ses erreurs d'octave ne représentent qu'environ 1 % des notes (voir 5.4) : contrairement à ce que disait une version précédente de ce document, YIN n'est **pas** la principale source d'erreurs mesurée.

### 4.2 Portes de volume

- Volume absolu minimal `MIN_VOICE_RMS = 0.003` (~-50 dBFS) : en dessous, frame non voisée.
- Porte relative : une frame plus faible que 6 % du volume "fort" de la prise (90e percentile des frames voisées) est considérée comme du souffle ou du bruit.

### 4.3 Calibrage de justesse : `TuningEstimator` (désactivé par défaut)

**Actif seulement avec l'option « Rendre plus juste »** (`AnalysisSteps.CORRECTED`). Par défaut (`AnalysisSteps.AS_SUNG`), l'appli garde la hauteur chantée : sur vocadito, le calibrage faisait mieux sur 4 extraits mais moins bien sur 7, et coûtait 3 à 4 points au total (voir 5.4).

Problème : quelqu'un qui chante tout 40 cents trop bas a des intervalles justes, mais chaque note tombe entre deux demi-tons et l'arrondi oscille au hasard. Un débutant dérive aussi lentement au fil de la prise.

- Modèle : **décalage(t) = a + b·t** (décalage de départ + vitesse de dérive), ajusté sur toute la prise.
- Pour chaque frame, on garde l'écart au demi-ton le plus proche (−50 à +50 cents). Ces écarts sont **circulaires** (+50 et −50 sont le même point). Pour chaque dérive candidate b (−8 à +8 cents/s, pas de 0,25), le meilleur a est une **moyenne circulaire**. On garde le couple qui aligne le mieux les frames sur la grille.
- Pondération : confiance YIN × stabilité de la hauteur (les glissés ne disent rien du diapason).
- Avant la mesure, la hauteur est **lissée sur ±100 ms** : cela annule le vibrato. Sans ce lissage, un vibrato de ±60 cents faisait croire à un décalage de 50 cents.
- Garde-fous : une dérive n'est acceptée que si l'alignement obtenu est bon (concentration ≥ 0,6) et nettement meilleur que sans dérive (+0,3). Sinon, sur une voix très dispersée, on "inventait" des dérives dans le bruit. Si l'alignement est mauvais même sans dérive (< 0,2), aucune correction n'est appliquée.
- **Pourquoi une droite et pas une fenêtre glissante** : une fenêtre courte absorbait les erreurs propres à chaque note (le chanteur avait l'air juste, alors qu'il ne l'était pas), et une fenêtre longue était en retard sur la dérive aux extrémités.

### 4.4 Découpage en notes

**Mode automatique (sans bouton) : HMM + Viterbi** (`ViterbiNoteSegmenter`, dans l'esprit de pYIN-notes / Tony) :

- États : silence + un état par demi-ton de La1 à Do7.
- Émission : gaussienne en cents (σ = 35) autour de la note, pondérée par la confiance YIN.
- Transitions : rester sur la même note 0,965, rester en silence 0,9, entrer ou sortir du silence 0,02, note vers note ln(0,03) − 0,4 × distance en demi-tons.
- Post-traitement : les morceaux de moins de 60 ms sont fusionnés avec leur voisin, et une 1re note brève à une ou deux octaves de la 2e est recalée (artefact d'attaque).
- **Deux passages** : le 1er donne des notes, d'où une tonalité. Le 2e relance Viterbi avec un léger coût d'entrée dans les notes hors gamme (0,5 × ln(prior)).

**Mode bouton** : chaque appui donne une note. Ses bornes sont recalées sur la voix :

0. **décalage systématique** : s'il existe un décalage (±400 ms) qui fait coïncider nettement plus d'appuis avec des événements de voix (débuts de son, changements de note) que l'absence de décalage, et qu'il dépasse 130 ms, tous les appuis sont décalés d'autant (corrélation, pas médiane : avec des notes courtes, l'événement le plus proche d'un appui en avance est souvent la note précédente). Sur la tablette de l'auteur, les appuis arrivaient ~250 ms avant la voix ; benchmark avec un doigt simulé 150-300 ms en avance : 0,36 → 0,70 ; doigt en retard de 40-120 ms : inchangé (0,73) ;
1. si un début ou une fin de son (silence → voix, petits trous ≤ 60 ms comblés) se trouve à ±150 ms de l'appui, on s'y cale ;
2. sinon (chant lié, sans silence entre les notes), on cherche **juste avant l'appui** le moment où la hauteur médiane change d'au moins 0,7 demi-ton : le doigt arrive presque toujours après la voix ;
3. une note se termine au plus tard quand la suivante commence ;
4. ce qui est chanté à plus d'1 s de tout appui est découpé en automatique (Viterbi), au lieu d'être perdu : sur une prise de 40 s avec 3 appuis au début, 3 → 29 notes. Sans effet sur vocadito (les appuis simulés couvrent toutes les notes).

Ce recalage sur le changement de hauteur a fait passer le mode bouton de 0,52 à 0,68 sur le benchmark.

### 4.5 Une hauteur par note

Médiane pondérée (par la confiance) du **cœur** de la note : les 20 % de chaque bord sont ignorés, dans la limite de 80 ms, car c'est là que la voix attaque et glisse. En mode bouton, les frames à plus de 6 demi-tons de la médiane sont écartées (erreurs d'octave ponctuelles). En mode automatique, la médiane est bornée à ±0,49 demi-ton autour de la note choisie par Viterbi : la laisser contredire Viterbi coûtait des notes justes.

### 4.6 Tonalité : `KeyDetector`

- Approche bayésienne sur les 24 tonalités (12 majeures, 12 mineures naturelles), avec les profils de **Krumhansl-Kessler élevés au carré** (bruts, ils étaient trop timides : 68 % de confiance sur 15 notes d'une gamme de Do). Chaque note vote, pondérée par sa durée (durée/300 ms, bornée entre 0,5 et 2). Une note entre deux demi-tons vote pour les deux (assignation douce, σ = 0,3 demi-ton).
- Résultat : une distribution de probabilités. La confiance affichée est celle du *jeu de notes* (majeur et relatif mineur confondus). La tonalité n'est montrée à l'utilisateur que si la confiance dépasse 60 %.
- Pour la correction, les probabilités sont « tranchées » (élevées à la puissance 3) : un verdict à 98 % devient quasi certain, une vraie hésitation reste une hésitation.

### 4.7 La gamme comme arbitre, pas comme dictateur

Chaque note choisit le demi-ton qui maximise `gaussienne(hauteur chantée, σ = 25 cents) × prior(gamme)`, avec un poids de 0,3 pour une note hors gamme. Résultat : **seules les notes vraiment ambiguës** (dont le centre tombe à ±8 cents du milieu entre deux demi-tons) basculent vers la gamme. Une note chantée franchement est respectée, même hors gamme.

En mode automatique, cet arbitrage est fait par le 2e passage Viterbi (pas de seconde correction derrière, elle coûtait 2 points) ; son effet est minime (il ne tranche qu'à ±1 cent du milieu). En mode bouton, il est fait note par note (`snap`), **seulement avec l'option « Rendre plus juste »** : par défaut, la note est la médiane arrondie (l'arbitrage coûtait 1 à 2 points sur vocadito).

### 4.8 Nettoyage final (mode automatique)

- **Recollage** de deux morceaux contigus (≤ 30 ms d'écart) tombés sur le même demi-ton.
- **Absorption des hésitations** (« doooo do# doo ») : une note brève (< 150 ms), proche (≤ 2 demi-tons), collée entre deux morceaux de la *même* note au moins deux fois plus longs, est absorbée. Une vraie broderie rapide (« mi fa mi » en notes égales) n'est pas touchée.
- **Séparation des notes répétées** (« Do-Do-Do » avec de simples consonnes) : la hauteur ne bouge pas et la fenêtre de 46 ms déborde sur des coupures aussi courtes, donc le seul indice est le volume. Une note est coupée là où le volume tombe sous 35 % du plus faible des deux pics qui l'entourent, si chaque morceau dure au moins 80 ms.

### 4.9 Pitch bend

À l'export, si l'option est cochée, les frames sont recentrées note par note sur la note finale. Le pitch bend ne garde donc que le vibrato, pas l'erreur de justesse que l'analyse a corrigée. La plage de pitch bend (±2 demi-tons, RPN 0) est écrite explicitement dans le fichier.

## 5. Comment c'est mesuré

### 5.1 Tests automatiques

- 84 tests JUnit sur PC (`./gradlew testDebugUnitTest`), dont 4 mesures sur vraies voix qui ne s'exécutent que si le jeu de données est fourni (voir 5.3). Ils couvrent YIN, Viterbi, le découpage temps réel, la justesse (décalage constant, dérive, bruit à ne pas prendre pour une dérive), la tonalité, l'analyseur (hésitations, notes répétées, appuis en retard, glissés, notes ambiguës, notes hors gamme volontaires, réglage par défaut et « Rendre plus juste »), le MIDI, le pipeline complet sur chaque scénario de démo, et les prises réelles de l'auteur (5.5).
- 2 tests sur l'appareil (`./gradlew connectedDebugAndroidTest`, `AudioFileDecoderTest`) : le test fabrique un vrai m4a (AAC stéréo 48 kHz, comme le Dictaphone) et un wav, et vérifie que l'import les décode en mono 44,1 kHz avec la bonne durée et la bonne hauteur.
- Validation synthétique (scénarios de démo, stress-tests) : utile pour vérifier que le code fait ce qu'on croit, mais **circulaire** (la voix simulée suit les hypothèses de l'algorithme). Elle ne prouve rien sur les vrais chanteurs.

### 5.2 Benchmark sur vraies voix : vocadito

- Jeu de données **vocadito** (CC BY 4.0, https://zenodo.org/records/5578807) : 40 extraits de chant solo, 7 langues, chanteurs de niveaux variés, appareils variés, notes annotées à la main par **deux** musiciens (A1, A2).
- Lancement : `VOCADITO_DIR=<dossier contenant Audio/ et Annotations/> ./gradlew testDebugUnitTest --tests '*VocaditoBenchmark*' -i` (ignoré sans la variable). Après une mesure, faire `./gradlew --stop` : le démon Gradle garde la variable en mémoire et relancerait le benchmark (long) à chaque test.
- **Séparation réglage / test** : les extraits 4, 8, 12... 40 (10 extraits) ne servent à régler aucun paramètre ; les 30 autres (DEV) oui.
- Deux métriques, avec un **couplage maximum** des notes (comme `mir_eval`) :
  - règle « appli » : début à ±100 ms, moins d'un demi-ton d'écart, meilleure transposition globale (−1, 0, +1 demi-ton) ;
  - **COnP standard** : début à ±50 ms, ±50 cents, sans transposition (comparable à la littérature).
- **Intervalle à 95 %** du gain sur l'ancien algorithme, par bootstrap apparié sur les extraits (1000 tirages).
- **Décomposition des erreurs** (`errorBreakdown`) : chaque note de référence est classée OK / début décalé (100-250 ms) / demi-ton / octave / coupée / fusionnée / autre hauteur / ratée, plus les notes « en trop » et le biais médian des débuts.
- **Ablation** (`ablation`) : chaque étape retirée (ou ajoutée) seule.
- Mode bouton simulé : doigt en retard de 40 à 120 ms au début de chaque note, relâché à ±60 ms.
- `VocaditoMidiExport` (variables `VOCADITO_DIR` et `EXPORT_DIR`) exporte pour les 4 plus longs extraits le WAV, le MIDI de l'appli, le MIDI de référence et une page `comparaison.html` (vert = juste, orange = faux ou en trop, pointillés = raté).

### 5.3 Résultats actuels (règle « appli », référence A1)

| Version | DEV (30) | TEST (10, jamais utilisés pour régler) | COnP DEV / TEST |
|---|---|---|---|
| Ancien algorithme (Viterbi seul) | 0,637 | 0,710 | 0,553 / 0,623 |
| Auto (défaut) | 0,644 | 0,716 | 0,550 / 0,618 |
| **Bouton (défaut)** | **0,723** | **0,754** | 0,612 / 0,675 |
| Auto + « Rendre plus juste » | 0,642 | 0,675 | 0,546 / 0,572 |
| Bouton + « Rendre plus juste » | 0,692 | 0,693 | 0,585 / 0,625 |
| Annotateur A2 vs A1 (repère) | 0,757 | 0,817 | 0,718 / 0,766 |

Gain du mode bouton sur l'ancien : [+0,057 ; +0,113] sur DEV (significatif), [+0,010 ; +0,075] sur TEST (10 extraits seulement, intervalle large). Mode auto : gain minime, à peine significatif sur DEV.

Décomposition (A1, % des notes de référence, mesurée le 20 octobre) :

| | OK | début décalé | demi-ton | octave | fusionnée | autre hauteur | ratée | en trop | biais début |
|---|---|---|---|---|---|---|---|---|---|
| ancien | 65 % | 10 % | 6 % | 0,4 % | 4 % | 6 % | 8 % | 0,2 % | 0 ms |
| auto | 66 % | 10 % | 7 % | 0,4 % | 4 % | 5 % | 8 % | 0 % | 0 ms |
| bouton | 65 % | 6 % | **13 %** | 0,6 % | 1,7 % | 9 % | 5 % | 0 % | −17 ms |

La version du 13 octobre de ce tableau donnait 71 % de notes OK en mode bouton : écart non expliqué (le F global, lui, a légèrement progressé), à vérifier.

Ablation (DEV, réglage par défaut) : chaque étape du mode auto (porte de bruit, 2e passage, recollage/hésitations, séparation par volume) pèse moins d'un demi-point ; ajouter le calibrage coûte 2,3 points en mode bouton, ajouter l'arbitrage par la gamme 1,2 point.

### 5.4 Ce que les mesures ont appris (important)

1. **Forcer les notes dans la gamme fait plus de mal que de bien.** La version initiale déplaçait 21 % des notes, et dans 295 cas sur 463 la note était juste avant. Sur de vraies voix, une note hors gamme chantée franchement est presque toujours voulue.
2. **Ne pas inventer de dérive** : sans garde-fous, l'ajustement trouvait des dérives absurdes sur des voix dispersées.
3. **Les erreurs d'octave ne se distinguent pas des sauts d'octave voulus** (même durée, même confiance YIN). Elles ne pèsent qu'environ 1 % des notes. Aucune règle a posteriori n'a été ajoutée.
4. **Le calibrage de justesse divise** : meilleur sur 4 extraits (ex. vocadito 37 : 0,69 → 0,78, chanteur visiblement entre deux notes), moins bon sur 7 (ex. vocadito 24 : 0,66 → 0,44). En moyenne il coûte, d'où le réglage par défaut « tel que chanté » et l'option.
5. **Surapprentissage réel** : avant la séparation réglage/test, plusieurs étapes semblaient utiles sur les 40 extraits mais faisaient *moins bien* que l'ancien algorithme sur les 10 extraits mis de côté. Toute nouvelle idée doit être validée sur TEST.
6. **Le bouton règle le découpage, pas la hauteur** : avec lui, presque plus de notes ratées ou fusionnées, mais les erreurs d'un demi-ton deviennent la première cause d'échec (13 %).
7. **Ce que vocadito ne peut pas mesurer** : l'intention (« fausse note de débutant », chanteur qui veut une note mais en chante une autre). L'annotation note ce qui est chanté. Les jeux « query by humming » (référence = partition) seraient l'outil idéal : MTG-QBH (CC BY 4.0) n'a pas les notes des chansons, et MIR-QBSH n'est plus téléchargeable (lien officiel en 404 en octobre 2026). Reste la voix de l'utilisateur, via l'import.

### 5.5 Prises réelles de l'auteur (`MesPrisesTest`)

Prises à la tablette, dans une pièce bruyante, avec le bouton, sur des chansons connues : 2 × Au clair de la lune, Joyeux anniversaire, Frère Jacques (45 notes). Seules les mesures (frames YIN, appuis) sont dans le dépôt (`app/src/test/resources/prises`), pas le son. Une note est juste si la *mélodie* l'est : alignement (plus longue sous-suite commune) avec la mélodie attendue, à la meilleure transposition près. C'est la seule mesure de l'intention disponible.

| Réglage | Notes justes |
|---|---|
| Tel que chanté (défaut) | 26 / 45 |
| « Rendre plus juste » | 29 / 45 |
| Gamme imposée = celle où la voix chante (la moins bonne des 2 toniques plausibles) | 32 / 45 |
| Meilleure des 12 gammes majeures, choisie après coup (borne, irréalisable) | 39 / 45 |

Ce que ça apprend :

1. La voix de l'auteur tombe à ±20-50 cents des notes, **sans décalage régulier** (concentration circulaire 0,1 à 0,3) : aucun recalage global ne peut corriger ça.
2. **La gamme aide, mais à moitié** : une partie des notes est chantée franchement un demi-ton à côté, sur une note qui est *aussi* dans la gamme (ex. le « fa » de Frère Jacques chanté presque sur le « mi »).
3. **Deviner la gamme sur la voix seule** est difficile quand chaque note est aussi incertaine : plusieurs gammes collent aussi bien.
4. Le bruit fait perdre des notes (appuis où la voix n'est détectée que 5 à 30 % du temps).
5. Contradiction avec vocadito (où « Rendre plus juste » coûte 3 points) : sur ces prises, il gagne 3 notes. vocadito note ce qui est chanté, ces prises ce qui était voulu. 4 prises seulement : indicatif.

## 6. Limites connues et problèmes ouverts

- **Erreurs d'un demi-ton en mode bouton** (14 % des notes) : première cause d'erreur restante. Pistes : bords de note encore pris dans la médiane, notes réellement chantées entre deux demi-tons, désaccord d'annotation.
- **Débuts de notes** : 7 à 10 % de notes à la bonne hauteur mais décalées de 100 à 250 ms ; en mode bouton, les débuts sont en moyenne 17 ms trop tôt.
- **Découpage du chant lié sans bouton** : notes longues parfois coupées, notes rapides parfois fusionnées (vocadito 2 : 53 %).
- **YIN** : erreurs d'octave (~1 %), pas de candidats multiples comme pYIN.
- **Retour sonore et édition limités** : on peut écouter le MIDI au piano et corriger la hauteur d'une note ou la supprimer, mais pas encore modifier son début ou sa durée, ni réécouter sa propre voix. Le piano est celui, assez basique, du synthé intégré à Android.
- **Composition** : les pistes d'un projet démarrent toutes à leur 1re note (ou au 1er temps si un tempo de projet était donné à l'enregistrement) : deux mélodies chantées séparément, sans tempo commun, ne tombent pas rythmiquement ensemble. Pas encore d'export du projet entier, de décalage d'une piste, ni d'instrument ou de volume par piste.
- **Analyse uniquement après l'arrêt**, le temps réel ne sert qu'à l'affichage.
- **Premiers tests sur la vraie voix de l'auteur** (tablette Samsung, « Au clair de la lune » avec le bouton) : après correction du micro et de la datation des appuis, 10 notes justes sur 10 appuis (la 11e note n'avait pas d'appui). Erreur restante typique : une note chantée ~40 cents trop haut qui bascule sur la note voisine, elle aussi dans la gamme (indécidable automatiquement).
- **Tonalités mineures** : seul le mineur naturel est modélisé.

## 7. Pistes pour la suite (à valider sur DEV **et** TEST)

1. **Plus de prises de l'auteur sur des mélodies connues**, au calme et avec le métronome (5.5 n'en a que 4, dans le bruit). À ajouter dans `MesPrisesTest`.
2. **Choix automatique du calibrage, prise par prise** : détecter quand une voix se tient durablement près de ±50 cents (cas où le calibrage aide, ex. vocadito 37) et ne l'activer que là.
3. **Erreurs d'un demi-ton en mode bouton** : analyser les cas (où tombe la médiane, quelle part de la note est un glissé), essayer une médiane sur une fenêtre plus centrée, ou Viterbi contraint à une seule note par appui.
4. **HMM avec durées** (sous-états attaque → stable, comme pYIN-notes) et émission pondérée par la stabilité de la hauteur (moins de faux Ré dans un glissé Do→Mi).
5. **YIN à plusieurs candidats** donnés à Viterbi, ou modèle neuronal léger (PESTO, CREPE, FCPE, Basic Pitch ; licences et tailles à vérifier) via ONNX Runtime, évaluable d'abord dans le benchmark sur PC.
6. **Interface pour les erreurs indécidables** : la réécoute et la correction à la roue existent ; reste à surligner les notes incertaines (probabilités a posteriori du HMM) et proposer l'alternative en un tap (« Mi ou Fa ? »).
7. **Appuis du bouton comme indice dans le HMM** plutôt que découpage imposé (un appui oublié ou doublé ne casserait plus tout). Gros chantier.
8. **Dérive non linéaire** (filtre de Kalman) : à valider sur des prises longues, impossible sur vocadito.
9. **Deviner le tempo sans métronome** pour caler les notes sur les temps : chercher ensemble tempo et position des temps sur les débuts de notes, suivre un tempo qui varie, convertir en temps musicaux puis écrire au BPM du projet. Ambiguïté ×2 / ÷2 à laisser trancher par l'utilisateur. L'ancienne `estimateGridMs` (médiane des écarts, sans position des temps) est toujours dans `Quantizer.kt` mais n'est plus utilisée.

## 8. Environnement et pièges pratiques

- Windows 11, Android Studio, Kotlin 2.2, AGP 9.4, compileSdk/targetSdk 37, minSdk 24, Jetpack Compose.
- En ligne de commande, il faut définir `JAVA_HOME` sur le JDK d'Android Studio (`C:\Program Files\Android\Android Studio\jbr`).
- L'émulateur actuel est une tablette sous Android 12. Pour y mettre un fichier (prise iPhone, etc.) : le glisser sur la fenêtre de l'émulateur (il arrive dans Téléchargements), ou `adb push`. Sous Git Bash, préfixer par `MSYS_NO_PATHCONV=1` sinon `/sdcard/...` est réécrit en chemin Windows.
- **Ancien émulateur Android 17 (API 37, préversion)** : un processus du noyau se bloquait (`kworker … cgroup_release` en état D), et l'arrêt des applis se fige. Résultat dans Android Studio : « Couldn't terminate previous instance of app ». Contournement : `adb shell run-as com.example.voicetomidi kill -9 <pid>`. Vraie solution : un émulateur API 35 ou 36.
- Formatage numérique : la machine est en locale française. Tout texte destiné à une machine (SVG, CSV) doit utiliser `Locale.ROOT` ou `Locale.US`, sinon les décimales sortent avec une virgule.
- Pour récupérer un MIDI exporté depuis l'émulateur : Android Studio → View → Tool Windows → Device Explorer → `sdcard/Download`.

## 9. Questions ouvertes pour un agent qui voudrait aider

- Quel modèle de détection de hauteur embarquable sur Android (taille, latence, licence) donnerait le meilleur gain sur vocadito par rapport à YIN ?
- Comment découper le chant lié sans bouton de façon plus fiable (attaques douces, consonnes voisées comme « l », « m », « n ») ?
- Existe-t-il un moyen fiable de distinguer une erreur d'octave d'un saut d'octave voulu (harmoniques, timbre, contexte) ?
- Quelle métrique refléterait le mieux « ce MIDI est utilisable tel quel dans FL Studio » ?
- Pour la « fausse note de débutant », faut-il une correction automatique ou plutôt une aide à la correction manuelle ?
