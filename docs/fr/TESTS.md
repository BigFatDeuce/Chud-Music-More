# Tests effectués, non effectués, limites connues

*[English version](../TESTING.md)*

État au 18 novembre 2026, version 0.3.3. Tout ce qui suit a été fait sur l'**émulateur Android 16
(API 36)**, en version debug puis en version release signée et optimisée. Les serveurs de test
étaient Samba et rclone (WebDAV) dans WSL.

## Vérifié automatiquement

`./gradlew testDebugUnitTest` : 69 tests unitaires, dont 10 ne tournent que sur demande (6 contre
les services Internet, 4 contre un serveur SMB). Tous passent.

- Échange de playlists avec le PC (JSON et M3U8, dans les deux sens, correspondance des chemins).
- Calculs de l'égaliseur, lecture des paroles synchronisées, ordre des versions.
- Les machines à états listées dans [STATE-MACHINES.md](../STATE-MACHINES.md) : phase du lecteur,
  erreurs de lecture, analyse, pages de la recherche YouTube, recherche réseau, autorisation,
  tirage au hasard, notes de version, liens collés, analyse reprise après un arrêt.
- Liens (`LinksTest`) : lecture des liens YouTube et Spotify (mêmes cas que l'application PC), le
  rapprochement partagé avec le PC, la page d'intégration Spotify ; en ligne, une playlist YouTube
  de 140 morceaux lue au-delà de sa première page et un album Spotify retrouvé sur YouTube Music.
- SMB contre un vrai serveur : liste des partages sans les partages cachés, mauvais mot de passe,
  lecture après 40 s de silence, connexion coupée par le serveur.

## Vérifié à la main sur l'émulateur

**Téléphone et réseau**
- Analyse de la musique du téléphone (tags, pochettes intégrées ; FLAC, MP3, M4A).
- **Chercher sur le réseau** : le balayage trouve le serveur SMB ; un serveur qui refuse les
  invités demande un compte ; avec un compte, ses partages sont listés ; en choisir un le teste,
  ouvre le navigateur de dossiers, l'enregistre, l'analyse et le lit. Idem en version release.
- SMB (utilisateur et mot de passe) et WebDAV (HTTP, lecture seule) : test, analyse, lecture
  (dont FLAC).
- Un morceau SMB démarre en quelques secondes après 50 s de connexion inactive.
- Une analyse de partage de 600 nouveaux fichiers arrêtée vers 240 (application tuée) : au
  lancement suivant, seuls les 325 restants ont été lus, et tous les fichiers sont arrivés.
- Une première analyse du téléphone de 1 200 morceaux (100 albums) arrêtée après 400 (application
  tuée) : au lancement suivant, elle a continué jusqu'aux 1 200, en deux fois moins de temps qu'une
  analyse complète (15 novembre).
- Analyse en arrière-plan (16 novembre) : application passée en arrière-plan et mode Doze forcé,
  l'analyse de 1 200 morceaux est allée au bout en tâche de premier plan avec sa notification ;
  écran éteint et processus tué à 200, Android l'a relancé tout seul et l'analyse a continué
  jusqu'à 1 200 sans ouvrir l'application. Version release : l'analyse démarre en tâche de premier plan.
- Aucun fichier musical n'est modifié ni supprimé : les serveurs de test étaient en lecture seule.

**YouTube Music**
- Recherche, suggestions, les quatre filtres, pages suivantes en défilant.
- Lecture sans publicité (Opus, par défaut ; le choix AAC n'a pas été essayé à part).
- Pages album et artiste, « Démarrer le mix », lien `music.youtube.com` partagé vers l'application.
- Liens : un album Spotify tapé dans la recherche (14 sur 14 retrouvés, dans l'ordre, enregistré
  en playlist) ; une playlist Spotify importée dans une playlist vide (50 sur 50 retrouvés et
  ajoutés) ; une playlist YouTube partagée depuis une autre application (140 morceaux, lue).
- Liens **collés** dans la recherche (15 novembre) : un lien seul, une phrase de partage autour
  d'un lien (« Écoute … : https://… ») et un lien collé après un mot tapé ouvrent tous le morceau
  aussitôt (les deux derniers depuis la 0.3.1).
- Artistes masqués (16 novembre, français et anglais) : « Masquer … » dans le menu ⋮ d'un morceau
  retire de la recherche les titres, albums et la fiche de l'artiste, y compris les morceaux qui ne
  le citent qu'en invité dans le titre ; l'œil de la page artiste masque et rétablit ; Paramètres ›
  YouTube Music liste l'artiste et le rétablit. Mix et playlists partagées : tests unitaires seulement.

**Playlists et application PC**
- Import d'un JSON au format du PC : chemin Windows → fichier du téléphone ; `\\10.0.2.2\Musique\…`
  → partage SMB ; `\\NAS-SALON\…` → retrouvé par la fin du chemin ; tags seuls → retrouvé par
  titre, artiste et durée ; fichier absent → gardé et grisé ; morceaux YouTube Music importés.
- Import M3U8 ; ouverture d'un `.m3u8` depuis un gestionnaire de fichiers.
- Supprimer toutes les playlists : les deux confirmations s'affichent (anglais et français), annuler
  à la seconde les garde, confirmer les supprime sans toucher à la bibliothèque.
- Exports JSON et M3U8 : comparés au format et au code d'import du PC.

**Accueil, radio, lecteur, statistiques**
- Section « Au hasard » : même tirage jusqu'à ce qu'on le relance, lecture depuis le morceau touché.
- Paramètres › Page d'accueil : masquer « Au hasard » et « Ajoutés récemment » les retire de
  l'Accueil, les réafficher les ramène (16 novembre).
- Radio : stations MP3 et AAC, favoris, récentes ; s'ouvre sur Favoris quand il y en a.
- Égaliseur, paroles synchronisées, aléatoire, répétition, file d'attente, file restaurée après
  l'arrêt de l'application.
- Statistiques : toutes les sections ; les copies d'un même morceau sont regroupées.

**Ajouté en 0.3.3** (18 novembre, français et anglais, version debug ; paroles, fiche du morceau
et pochettes aussi en version release)
- Playlist : 🔍 ne garde que les morceaux qui correspondent ; *Trier par… › Titre* réordonne et
  enregistre, avec Annuler ; *Trouver les doublons…* sur un MP3 et un FLAC de la même chanson
  ajoutés trois fois a retiré deux entrées et gardé le FLAC (vu sur sa fiche).
- Accueil : *Retirer des écoutés récemment* (appui long) et *Retirer* sur un album des ajoutés
  récemment les retirent. Annuler sur ce message n'a pas pu être touché à temps sur l'émulateur.
- *Trouver les pochettes manquantes* : *Discovery* de Daft Punk a reçu sa pochette, les 11 albums
  de test inventés aucune ; ⋮ › *Retirer la pochette trouvée en ligne*, puis *Chercher la
  pochette en ligne* sur la page de l'album ; la pochette est restée après modification des
  fichiers et nouvelle analyse.
- Paroles du fichier : MP3 avec USLT (UTF-16), MP3 écrit par ffmpeg (TXXX), FLAC avec paroles LRC
  synchronisées ; sur le téléphone et sur un partage WebDAV.
- Fiche du morceau : album, format et débit, source, chemin, écoutes ; le lien Wikipedia ouvre le
  navigateur.
- Analyse en pause sur un partage WebDAV : un fichier ajouté sur le serveur n'a pas été lu par
  *Réanalyser* pendant la pause, et l'a été une fois la pause retirée.

**Autorisations et mises à jour**
- Autorisation « musique » : refusée une fois (redemandée), refusée deux fois (la carte propose
  les réglages d'Android), accordée dans les réglages (vue au retour, l'analyse démarre).
- Mises à jour, avec la vraie version 0.1.0 de GitHub : trouvée depuis une version de test plus
  ancienne, téléchargée, SHA-256 vérifié, installée par-dessus, données conservées. Une version
  trouvée reste proposée après un redémarrage ; pastille sur l'engrenage de l'Accueil ; notes de
  version avec gras et puces.
- 0.1.0 → 0.2.0, toutes deux telles que publiées sur GitHub : l'application a trouvé la nouvelle
  version seule au démarrage, l'a téléchargée et installée ; la playlist a été conservée.

## Non vérifié

- **Android Auto**.
- Un vrai **NAS**, un partage Windows avec compte, un **Nextcloud** en HTTPS.
- La recherche réseau sur un vrai Wi-Fi : les **noms des serveurs** (NetBIOS) et les serveurs qui
  **s'annoncent** (mDNS, c'est ainsi que les serveurs WebDAV sont trouvés) ne sont pas visibles
  depuis l'émulateur. Seul le balayage des adresses a été exercé.
- La reprise de la lecture après un **redémarrage** du téléphone.
- Les paroles dans les fichiers **Ogg/Opus** et **M4A** (tests unitaires seulement), la recherche
  de pochettes sur une vraie grande bibliothèque, les liens Grokipedia, Last.fm et Discogs (seul
  Wikipedia a été ouvert).
- L'import, dans l'**application PC** elle-même, des fichiers exportés par le téléphone.

## Limites connues

- Le **WMA** n'est pas lu par Android : le morceau est sauté avec un message.
- La recherche réseau regarde les 254 adresses autour de celle du téléphone ; un serveur ailleurs
  sur un grand réseau se saisit à la main. Un VPN sur le téléphone masque le réseau local.
- Lister les partages d'un serveur demande un compte sur la plupart des PC Windows (les invités
  sont refusés).
- Après une mise à jour, l'application se ferme (Android la remplace) : il faut la rouvrir.
- Importer deux fois le même fichier crée deux playlists, comme sur le PC.
- YouTube change régulièrement son fonctionnement : si la lecture YouTube Music cesse de marcher,
  une nouvelle version de NewPipe Extractor (et donc de Chud Music) est nécessaire.

## Nettoyage de l'environnement de test

Les serveurs de test tournaient dans la distribution WSL `FedoraLinux-45`. Pour tout retirer, une
fois les serveurs arrêtés :

```bash
wsl -d FedoraLinux-45 -u root -- dnf remove -y samba rclone
```

```bash
wsl -d FedoraLinux-45 -u root -- userdel chudtest
```

```bash
wsl -d FedoraLinux-45 -u root -- rm -rf /srv/chudtest
```

Le dossier `/srv/chudtest` ne contient que des fichiers de test générés, pas votre musique.
