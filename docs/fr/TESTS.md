# Tests effectués, non effectués, limites connues

*[English version](../TESTING.md)*

État au 12 novembre 2026, version 0.2.0. Tout ce qui suit a été fait sur l'**émulateur Android 16
(API 36)**, en version debug puis en version release signée et optimisée. Les serveurs de test
étaient Samba et rclone (WebDAV) dans WSL.

## Vérifié automatiquement

`./gradlew testDebugUnitTest` : 42 tests unitaires, dont 9 ne tournent que sur demande (5 contre
les services Internet, 4 contre un serveur SMB). Tous passent.

- Échange de playlists avec le PC (JSON et M3U8, dans les deux sens, correspondance des chemins).
- Calculs de l'égaliseur, lecture des paroles synchronisées, ordre des versions.
- Les machines à états listées dans [STATE-MACHINES.md](../STATE-MACHINES.md) : phase du lecteur,
  erreurs de lecture, analyse, pages de la recherche YouTube, recherche réseau, autorisation,
  tirage au hasard, notes de version.
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
- Aucun fichier musical n'est modifié ni supprimé : les serveurs de test étaient en lecture seule.

**YouTube Music**
- Recherche, suggestions, les quatre filtres, pages suivantes en défilant.
- Lecture sans publicité (Opus, par défaut ; le choix AAC n'a pas été essayé à part).
- Pages album et artiste, « Démarrer le mix », lien `music.youtube.com` partagé vers l'application.

**Playlists et application PC**
- Import d'un JSON au format du PC : chemin Windows → fichier du téléphone ; `\\10.0.2.2\Musique\…`
  → partage SMB ; `\\NAS-SALON\…` → retrouvé par la fin du chemin ; tags seuls → retrouvé par
  titre, artiste et durée ; fichier absent → gardé et grisé ; morceaux YouTube Music importés.
- Import M3U8 ; ouverture d'un `.m3u8` depuis un gestionnaire de fichiers.
- Exports JSON et M3U8 : comparés au format et au code d'import du PC.

**Accueil, radio, lecteur, statistiques**
- Section « Au hasard » : même tirage jusqu'à ce qu'on le relance, lecture depuis le morceau touché.
- Radio : stations MP3 et AAC, favoris, récentes ; s'ouvre sur Favoris quand il y en a.
- Égaliseur, paroles synchronisées, aléatoire, répétition, file d'attente, file restaurée après
  l'arrêt de l'application.
- Statistiques : toutes les sections ; les copies d'un même morceau sont regroupées.

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

- Un **vrai téléphone** (casque ou voiture Bluetooth compris) et **Android Auto**.
- Un vrai **NAS**, un partage Windows avec compte, un **Nextcloud** en HTTPS.
- La recherche réseau sur un vrai Wi-Fi : les **noms des serveurs** (NetBIOS) et les serveurs qui
  **s'annoncent** (mDNS, c'est ainsi que les serveurs WebDAV sont trouvés) ne sont pas visibles
  depuis l'émulateur. Seul le balayage des adresses a été exercé.
- La reprise de la lecture après un **redémarrage** du téléphone.
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
