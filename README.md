# VoiceToMidi

Une appli Android qui **transforme une mélodie chantée en fichier MIDI**, à ouvrir ensuite dans FL Studio (ou n'importe quel logiciel de musique).

On chante, on arrête, on récupère ses notes. Aucune connaissance en solfège n'est nécessaire.

## Ce que fait l'appli

- **Enregistrer au micro**, avec une petite courbe de la voix en direct.
- **Bouton « maintiens pendant la note »** (facultatif) : on appuie au début de chaque note et on relâche à la fin. Le résultat est nettement plus précis.
- **Importer un fichier audio** (m4a du Dictaphone de l'iPhone, mp3, wav…).
- **Exporter en MIDI** au BPM de ton projet, avec en option la quantification, le pitch bend (vibrato) et le tap tempo.
- **Mode démo** sans micro : des voix synthétiques, dont « Au clair de la lune ».

## Installer le projet

Il faut **Android Studio** (version récente) : https://developer.android.com/studio

1. Récupérer le code :
   ```
   git clone <adresse-du-dépôt>
   ```
   Ou sur GitHub : bouton vert **Code** → **Download ZIP**, puis dézipper.
2. Dans Android Studio : **File → Open**, puis choisir le dossier `VoiceToMidi`.
3. Attendre la fin de la synchronisation Gradle (barre en bas, la première fois peut prendre quelques minutes).
4. Lancer avec le bouton ▶ sur un téléphone Android (mode développeur + débogage USB activés) ou sur un émulateur.

Configuration requise : Android 7.0 (API 24) minimum. Le projet utilise Kotlin et Jetpack Compose.

## Tester sans téléphone

- **Mode démo** dans l'appli : aucun micro nécessaire.
- **Importer une prise** : enregistre-toi sur ton téléphone, glisse le fichier sur la fenêtre de l'émulateur (il arrive dans Téléchargements), puis utilise le bouton « Importer » de l'appli.
- Le MIDI exporté se récupère dans Android Studio : **View → Tool Windows → Device Explorer → `sdcard/Download`**.

## Lancer les tests

```
./gradlew testDebugUnitTest
```

Sous Windows en ligne de commande, il faut d'abord définir `JAVA_HOME` sur le JDK fourni avec Android Studio, par exemple :

```
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
gradlew.bat testDebugUnitTest
```

Les mesures sur vraies voix (jeu de données [vocadito](https://zenodo.org/records/5578807)) ne se lancent que si on les active. Voir `docs/ETAT_DU_PROJET.md`, section 5.

## Organisation du code

```
app/src/main/java/com/example/voicetomidi/
├── audio/      micro, import de fichiers, voix de démo, métronome
├── pitch/      détection de la hauteur de la voix (YIN)
├── pipeline/   micro → hauteur + volume, image par image
├── notes/      l'algorithme musical : découpage en notes, justesse, tonalité
├── midi/       écriture du fichier MIDI
└── ui/         écrans (Jetpack Compose)
```

## Pour aller plus loin

**[`docs/ETAT_DU_PROJET.md`](docs/ETAT_DU_PROJET.md)** explique tout en détail : le fonctionnement des algorithmes, les résultats mesurés, les limites connues et les pistes pour la suite.
