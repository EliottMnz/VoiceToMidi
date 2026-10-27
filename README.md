# VoiceToMidi

Une appli Android qui **transforme une mélodie chantée en fichier MIDI**, à ouvrir ensuite dans FL Studio (ou n'importe quel logiciel de musique).

On chante, on arrête, on récupère ses notes. Aucune connaissance en solfège n'est nécessaire.

## Ce que fait l'appli

L'accueil propose trois modes :

- **Mélodie** : on chante, l'appli écrit les notes.
  - Enregistrement au micro, avec la courbe de la voix en direct.
  - Pavé **« Maintiens pendant la note »** (facultatif) : on appuie au début de chaque note et on relâche à la fin. Le résultat est nettement plus précis.
  - **Import d'un fichier audio** (m4a du Dictaphone de l'iPhone, mp3, wav…).
  - À la fin, la mélodie entière s'affiche comme un piano roll. On peut **l'écouter au piano** depuis n'importe quel endroit (en faisant glisser le curseur), et **corriger une note** en la touchant : une roue fait défiler les notes, en jouant chacune au piano.
  - **Export MIDI** au BPM de ton projet, ou **envoi dans la composition**.
  - **Mode démo** sans micro : des voix synthétiques, dont « Au clair de la lune ».
- **Composition** : rassemble les mélodies qu'on y envoie (aperçu, écoute). Le but à terme est d'en faire un morceau complet.
- **Drums** : bientôt.

## Installer le projet sur ton ordi

### 1. Installer Android Studio

Télécharge la **dernière version** d'Android Studio (gratuit, Windows / Mac / Linux) : https://developer.android.com/studio

Installe-le avec les options par défaut. Rien d'autre à installer : Java, Gradle et les outils Android sont téléchargés automatiquement.

### 2. Récupérer le projet

Le plus simple, directement depuis Android Studio :

1. Sur l'écran d'accueil d'Android Studio : **Clone Repository** (ou, si un projet est déjà ouvert : **File → New → Project from Version Control**).
2. Colle l'adresse : `https://github.com/EliottMnz/VoiceToMidi.git`
3. **Clone**, puis **Trust Project** si Android Studio le demande.

Sans Git : sur la page GitHub, bouton vert **Code → Download ZIP**, dézippe, puis dans Android Studio **File → Open** et choisis le dossier `VoiceToMidi`.

### 3. Attendre la synchronisation

La première ouverture télécharge tout ce qu'il faut (barre de progression en bas, **compter 5 à 15 minutes**). Si Android Studio propose d'installer un composant manquant (SDK, « platform »…), accepte.

### 4. Lancer l'appli

**Sur ton téléphone Android** (le mieux : le micro fonctionne vraiment) :

1. Active le mode développeur : **Paramètres → À propos du téléphone → Informations sur le logiciel**, puis touche **7 fois « Numéro de version »**.
2. Dans **Paramètres → Options de développement**, active **Débogage USB**.
3. Branche le téléphone en USB et accepte la fenêtre **« Autoriser le débogage USB ? »**.
4. Choisis ton téléphone en haut d'Android Studio, puis clique sur ▶.

**Sur l'émulateur** (sans téléphone) : **Tools → Device Manager → +** pour créer un appareil virtuel, puis ▶. Le **mode démo** marche sans micro. Pour chanter dans l'émulateur, il faut relier le micro du PC : bouton **⋯** de l'émulateur → **Microphone** → **« Virtual microphone uses host audio input »** (à réactiver à chaque démarrage de l'émulateur).

Il faut Android 7.0 ou plus récent.

## Récupérer les fichiers MIDI exportés

- Sur un téléphone : l'appli te demande où enregistrer le fichier (Téléchargements par exemple).
- Sur l'émulateur : dans Android Studio, **View → Tool Windows → Device Explorer → `sdcard/Download`**, clic droit → **Save As**.

Pour tester ta voix sans téléphone Android : enregistre-toi avec le Dictaphone de ton iPhone, glisse le fichier sur la fenêtre de l'émulateur (il arrive dans Téléchargements), puis utilise **Importer** dans le mode Mélodie.

## Lancer les tests

Dans Android Studio : clic droit sur le dossier `app/src/test` → **Run 'Tests in…'**.

En ligne de commande :

```
./gradlew testDebugUnitTest
```

Sous Windows, il faut d'abord indiquer le Java fourni avec Android Studio :

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
└── ui/         écrans (Jetpack Compose) : accueil, mélodie, composition
```

Le projet utilise Kotlin et Jetpack Compose.

## Pour aller plus loin

**[`CONTEXTE.md`](CONTEXTE.md)** : tout ce qu'il faut savoir pour développer une partie de l'appli (attentes, architecture, design, Composition, et la partie Drums à construire). À donner à ton agent IA si tu en utilises un.

**[`docs/ETAT_DU_PROJET.md`](docs/ETAT_DU_PROJET.md)** explique tout en détail : le fonctionnement des algorithmes, les résultats mesurés, les limites connues et les pistes pour la suite.
