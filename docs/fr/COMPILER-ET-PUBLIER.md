# Compiler, signer et publier une version

*[English version](../BUILDING.md)*

## Prérequis

- **JDK 21** (Temurin ou autre). Sur le PC de développement, il est déjà dans `.tools/jdk-21.0.13+9`.
- **SDK Android** avec la plateforme 37 et les build-tools 36 (Android Studio les installe, ou
  `sdkmanager`). Son chemin va dans `local.properties` : `sdk.dir=C\:\\Users\\…\\Android\\Sdk`.
- Gradle n'a pas besoin d'être installé : le script `gradlew` télécharge la bonne version (9.9.1).

Versions utilisées : Kotlin 2.4, Android Gradle Plugin 9.5, Compose Material 3, Media3 1.11,
NewPipe Extractor 0.27. `minSdk` 26 (Android 8.0), `targetSdk` 36.

## Compiler une version de test (debug)

Dans Git Bash, à la racine du projet :

```bash
export JAVA_HOME="$PWD/.tools/jdk-21.0.13+9"
./gradlew assembleDebug
```

Résultat : `app/build/outputs/apk/debug/app-debug.apk`. La version debug s'installe à côté de la
vraie (`app.chudmusic.debug`), avec ses propres données.

Les tests unitaires (échange de playlists, égaliseur, paroles, versions, machines à états) :
`./gradlew testDebugUnitTest`. Deux familles de tests ne tournent que sur demande : `-Pchud.online=true`
(YouTube Music, LRCLIB, Radio Browser) et `-Pchud.smb=hôte:port/partage -Pchud.smbUser=… -Pchud.smbPassword=…`
(un vrai serveur SMB).

Depuis l'archive des sources, sans la clé : `./gradlew assembleRelease` produit
`app-release-unsigned.apk`, à signer avec votre propre clé (`apksigner sign`). Il ne pourra pas
s'installer par-dessus la version officielle, signée par une autre clé.

## La clé de signature

Android n'installe une mise à jour **que si elle est signée par la même clé** que la version déjà
installée. Cette clé est donc précieuse.

- Fichier : `keystore/chud-release.jks` (PKCS12, RSA 4096, alias `chudmusic`, valable 30 ans).
- Mots de passe : `keystore/release.properties` (clés `storeFile`, `storePassword`, `keyAlias`,
  `keyPassword`), lu automatiquement par `app/build.gradle.kts`.
- Empreinte SHA-256 du certificat :
  `bc:c8:26:50:be:ad:89:93:51:25:90:ac:65:f8:17:25:30:f3:7f:3f:e8:58:88:00:08:ce:39:07:54:8b:2a:5c`

**À faire une fois pour toutes :** copier le dossier `keystore/` sur deux supports hors du PC
(clé USB, coffre-fort de mots de passe, cloud chiffré), et noter le mot de passe dans un
gestionnaire de mots de passe.

**Ne jamais :** régénérer la clé, la publier, l'envoyer sur GitHub (le dossier `keystore/` est
exclu par `.gitignore` et par le script d'archive des sources).

Si la clé est perdue, une nouvelle version ne pourra plus s'installer par-dessus : il faudrait
désinstaller l'application (après avoir exporté ses playlists) puis installer la nouvelle.

## Publier une nouvelle version

1. **Numéro de version** : dans `app/build.gradle.kts`, remplacer le numéro dans la ligne
   `versionName = … ?: "0.2.0"` par le nouveau (`0.3.0`, `1.0.0`…). Le `versionCode` en
   est déduit automatiquement (0.3.0 → 300), il augmente donc toujours.

2. **Compiler la version signée :**

   ```bash
   export JAVA_HOME="$PWD/.tools/jdk-21.0.13+9"
   ./gradlew clean assembleRelease
   ```

3. **Vérifier la signature** (doit afficher l'empreinte ci-dessus) :

   ```bash
   "$LOCALAPPDATA/Android/Sdk/build-tools/36.1.0/apksigner.bat" verify --print-certs app/build/outputs/apk/release/app-release.apk
   ```

4. **Préparer les fichiers à publier :**

   ```bash
   python tools/package-release.py
   ```

   Le dossier `release/` contient alors :
   - `ChudMusic-X.Y.Z.apk` : l'application signée ;
   - `ChudMusic-X.Y.Z-sources.zip` : le code source complet (obligation de la GPL), **sans** la clé,
     les sorties de compilation ni les outils locaux ; le script s'arrête s'il y trouve une clé ;
   - `SHA256SUMS.txt` : les sommes de contrôle.

5. **Tester** l'APK sur un téléphone ou l'émulateur, par-dessus la version précédente
   (les playlists et réglages doivent être conservés).

6. **Créer la version sur GitHub** (dépôt `BigFatDeuce/Chud-Music-More`, page *Releases ›
   Draft a new release*) :
   - tag : `vX.Y.Z` (exactement le numéro de version, précédé de `v`) ;
   - titre : `Chud Music X.Y.Z` ;
   - description : les nouveautés, en anglais (la page GitHub est en anglais). **Elle est
     affichée dans l'application** quand la mise à jour est proposée ; pour que les utilisateurs
     français la lisent en français, ajoutez la traduction dans un commentaire invisible sur
     GitHub : `<!-- lang:fr` sur une ligne, le texte français, puis `-->` ;
   - fichiers joints : l'APK, l'archive des sources et `SHA256SUMS.txt` ;
   - ni *draft* ni *pre-release* : l'application ne regarde que la dernière version publiée.

Le dépôt GitHub ne contient pas le code lui-même : chaque version porte son archive de sources,
ce qui satisfait la GPL.

7. **Mettre à jour la page GitHub** (README, documentation, captures, licences — pas le code) :

   ```bash
   python tools/publish-pages.py
   ```

   Le script montre ce qui changerait ; avec `--push`, il l'envoie sur la branche `main`.

## Comment l'application trouve les mises à jour

Elle interroge `https://api.github.com/repos/BigFatDeuce/Chud-Music-More/releases/latest`
(au plus une fois par jour, si l'option est active, ou sur demande), compare le tag au numéro de
version installé, et prend le premier fichier `.apk` joint dont le nom ne contient pas `debug`.
Si GitHub fournit l'empreinte SHA-256 du fichier, le téléchargement est vérifié avant
l'installation. Rien n'est installé sans que l'utilisateur touche « Mettre à jour » puis confirme
dans la fenêtre d'Android.

Pour tester ce mécanisme sans publier, deux propriétés de compilation existent :
`-PchudVersionName=0.0.9` (faire croire à une version ancienne) et
`-PchudUpdateApi=http://10.0.2.2:8000/latest.json` (un faux serveur local, vu depuis l'émulateur).

## Traductions

Les textes de l'interface sont dans `app/src/main/res/values/strings.xml` (français, langue par
défaut) et `values-en/strings.xml` (anglais). Ces deux fichiers sont la référence : on les modifie
directement, en gardant les mêmes clés dans les deux. Pour une nouvelle langue : copier le fichier dans
`values-<code>/`, le traduire, et ajouter le code à `localeFilters` dans `app/build.gradle.kts`.
