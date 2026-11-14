# Chud Music pour Android

*[English version](../../README.md)*

Le lecteur de musique Chud Music, version téléphone. Il lit votre musique là où elle est :

- **sur le téléphone** : MP3, FLAC, M4A/AAC, OGG/Opus, WAV ;
- **sur le réseau local** : partages **SMB** (PC Windows, NAS, Samba) et **WebDAV** (Nextcloud…), lus à distance, sans copie ;
  un bouton **Chercher sur le réseau** trouve les serveurs et liste leurs partages ;
- **sur YouTube Music**, sans publicité et sans compte Google ;
- **à la radio** : des milliers de radios en ligne (annuaire libre Radio Browser).

Les playlists s'échangent dans les deux sens avec **[Chud Music pour PC](https://github.com/BigFatDeuce/Chud-Music)**
(Windows et Linux ; JSON, M3U, M3U8).

> Chud Music ne modifie ni ne supprime jamais vos fichiers musicaux, sur le téléphone comme sur le réseau.

## Fonctions

- Accueil : écoutés récemment, **Au hasard** (un tirage que l'on peut relancer), playlists, ajouts récents.
- Bibliothèque : titres, albums, artistes, recherche.
- Lecteur : file d'attente, aléatoire, répétition, égaliseur 10 bandes avec préréglages, spectre animé, paroles synchronisées (LRCLIB).
- YouTube Music : recherche avec suggestions, pages album et artiste, « Démarrer le mix », partage d'un lien YouTube vers l'application.
- Radio : populaires, genres, pays, favoris, écoutées récemment.
- Statistiques d'écoute : temps, séries, heures et jours, morceaux, artistes, albums et radios préférés.
- Deux skins : **Chud Nocturne** (sombre) et **Platine** (clair), ou selon le thème du téléphone.
- Interface en français (anglais disponible ; prête pour d'autres langues).
- Mises à jour depuis l'application, avec votre accord.
- Contrôles dans la notification, sur l'écran verrouillé, au casque Bluetooth ; navigation pour Android Auto.

## Installation

Android 8.0 ou plus récent. Téléchargez `ChudMusic-<version>.apk` sur la
[page des versions](https://github.com/BigFatDeuce/Chud-Music-More/releases), ouvrez-le et
autorisez l'installation quand Android le demande. Les versions suivantes s'installent depuis
**Paramètres › Mises à jour**.

## Documentation

- [Guide d'utilisation](GUIDE.md)
- [Échanger les playlists avec le PC](ECHANGE-PC.md)
- [Compiler, signer et publier une version](COMPILER-ET-PUBLIER.md)
- [Tests effectués et limites connues](TESTS.md)

## Licence

Chud Music est un logiciel libre sous licence **GNU GPL version 3** (voir [LICENSE](../../LICENSE)).
Le code source de chaque version est joint à sa page de version, sous la forme
`ChudMusic-<version>-sources.zip`.

Les bibliothèques et polices utilisées gardent leur propre licence ; leurs textes se trouvent
dans [licenses/](../../licenses/) et dans l'application (**Paramètres › Licences**).

Chud Music n'est affilié ni à YouTube ni à Google. Les morceaux de YouTube Music sont lus en
direct, jamais téléchargés.
