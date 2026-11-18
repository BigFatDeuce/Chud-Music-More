# Guide d'utilisation

*[English version](../GUIDE.md)*

## Premier lancement

1. Chud Music demande l'accès à la **musique du téléphone** et l'autorisation d'afficher une
   **notification** (pour les contrôles de lecture). Les deux sont facultatifs : sans le premier,
   seuls le réseau, YouTube Music et la radio sont disponibles.
2. La bibliothèque se remplit en quelques secondes. Les pochettes sont celles intégrées aux
   fichiers ; sur les partages réseau, l'image du dossier de l'album sert aussi.

Les cinq onglets du bas : **Accueil**, **Bibliothèque**, **YouTube**, **Playlists**, **Radio**.
Le mini-lecteur au-dessus des onglets ouvre le lecteur complet.

## Accueil

- **Écoutés récemment**, puis **Au hasard** : 18 morceaux de votre bibliothèque tirés au sort au
  lancement. Le tirage reste le même tant que l'application tourne ; le bouton ⟳ en tire d'autres.
- Vos playlists, les albums ajoutés récemment, vos radios favorites.
- Un point sur l'engrenage signale qu'une nouvelle version attend dans Paramètres › Mises à jour.
- **Paramètres › Page d'accueil** choisit lesquelles de ces sections l'Accueil affiche. Quand il n'y
  a rien à afficher, l'Accueil montre le logo de Chud Music.
- Un appui long sur un morceau des **Écoutés récemment** › *Retirer des écoutés récemment*, ou
  sur un album des **Ajoutés récemment** › *Retirer*, le retire de l'Accueil (avec Annuler). Ses
  écoutes restent dans les statistiques et l'album reste dans la bibliothèque.

## Musique du téléphone

- **Paramètres › Musique du téléphone › Dossiers** : décochez un dossier (sonneries, notes
  vocales…) pour le laisser hors de la bibliothèque. Rien n'est supprimé.
- **Réanalyser** relit tout de suite le téléphone et les partages réseau.
- « Retirer de la bibliothèque » masque un morceau ; il revient à la prochaine analyse.
- **Trouver les pochettes manquantes** cherche la pochette des albums dont les fichiers n'en ont
  pas, sur Deezer puis iTunes : seuls l'artiste et le titre de l'album sont envoyés, et seulement
  quand vous appuyez. Les pochettes sont gardées dans le dossier de l'application, jamais écrites
  dans vos fichiers. Sur la page d'un album, ⋮ › *Chercher la pochette en ligne* la cherche à
  nouveau, et *Retirer la pochette trouvée en ligne* en retire une fausse.
- L'analyse continue en arrière-plan, avec une autre application ouverte ou l'écran éteint : une
  notification montre où elle en est. Si Android l'arrête (application fermée, téléphone éteint),
  elle reprend d'elle-même là où elle s'était arrêtée, sans tout recommencer : ce qui était déjà
  lu est gardé. De même pour les partages réseau.
- Si l'accès à la musique a été refusé deux fois, Android ne repose plus la question : l'Accueil
  propose alors **Ouvrir les réglages**, où vous autorisez « Musique et audio ».

## Partages réseau (SMB et WebDAV)

**Paramètres › Partages réseau › Ajouter un partage réseau.**

Le plus simple : **Chercher sur le réseau**. Chud Music cherche les PC et NAS allumés sur votre
Wi-Fi (quelques secondes) et les liste. Touchez un serveur : ses dossiers partagés s'affichent
(s'il demande un compte, remplissez « Utilisateur » et « Mot de passe », puis touchez « Lister les
partages »). Touchez un partage : la connexion est testée et vous pouvez choisir un sous-dossier.
Il ne reste qu'à **Enregistrer**.

La recherche ouvre seulement une connexion vers chaque adresse du réseau local, puis la referme :
rien n'est lu ni écrit sur les serveurs. Vous pouvez aussi tout saisir à la main :

| Champ | SMB (PC Windows, NAS, Samba) | WebDAV (Nextcloud…) |
|---|---|---|
| Adresse | nom ou IP du PC/NAS, par ex. `192.168.1.20` ou `NAS-SALON` | adresse complète, par ex. `https://cloud.exemple.fr/remote.php/dav/files/moi/` |
| Partage | nom du dossier partagé, par ex. `Musique` | — |
| Dossier | facultatif : un sous-dossier | facultatif |
| Identifiant / mot de passe | compte qui a accès au partage (vide = invité) | compte du serveur |

- **Tester** vérifie la connexion avant d'enregistrer.
- Les mots de passe sont chiffrés sur le téléphone (Android Keystore).
- Les fichiers restent sur le serveur : Chud Music les lit à distance, sans jamais rien modifier
  ni supprimer. Seules les étiquettes (tags) et les pochettes sont lues pendant l'analyse.
- **Analyse automatique** : relit les partages toutes les 6 heures, en Wi-Fi uniquement si
  l'option est cochée.
- **Mettre l'analyse en pause** (ouvrez le partage dans les Paramètres) : quand vous savez que rien
  n'y a changé, les analyses automatiques et **Réanalyser** le sautent. Ses morceaux restent dans
  la bibliothèque ; enregistrer le partage le relit quand même.
- Le téléphone doit être sur le même réseau que le serveur (ou via un VPN).

## YouTube Music

- Recherche avec suggestions ; résultats par **Titres**, **Autres versions** (live, reprises, mises en ligne ; toujours lues en son seul), **Albums**, **Artistes**.
- Pages album et artiste (titres populaires, albums, singles, artistes similaires).
- **Démarrer le mix** lance une radio sans fin à partir d'un morceau.
- **Collez un lien** dans la recherche, comme dans l'application PC : un morceau, une playlist,
  un album ou un artiste YouTube ou YouTube Music, ou un morceau, un album, une playlist ou un
  artiste Spotify. Un lien Spotify ne nomme que des chansons : chacune est cherchée sur YouTube
  Music (titre, artistes, durée), et celles sans version assez proche sont listées à part, à un
  toucher d'une recherche. **Enregistrer en playlist** garde le résultat.
- Depuis l'application YouTube ou Spotify ou un navigateur, **Partager › Chud Music** : un
  morceau se lit tout de suite, une playlist ou un album s'ouvre comme ci-dessus.
- Sans compte Google et sans publicité. Les morceaux s'ajoutent aux playlists comme les autres.
- **Masquer un artiste** : ⋮ sur l'un de ses morceaux › *Masquer « … »*, ou l'œil barré sur sa
  page. Il n'apparaît plus dans les recherches, les mix, les pages d'artiste ni les playlists
  YouTube partagées. Vos playlists et votre bibliothèque ne sont pas touchées. **Paramètres ›
  YouTube Music › Artistes masqués** ouvre leur liste, pour les rétablir.
- **Paramètres › YouTube Music › Préférer le format Opus** : meilleure qualité ; à désactiver si
  un vieil appareil Bluetooth lit mal (AAC à la place).

## Playlists

- **+ Nouvelle playlist**, ou ⋮ › « Ajouter à une playlist » depuis un morceau, un album…
- Une playlist vide propose **Importer depuis un lien** : collez le lien d'une playlist (ou d'un
  album) YouTube, YouTube Music ou Spotify ; ses morceaux sont retrouvés sur YouTube Music et
  ajoutés.
- Dans une playlist : la poignée ≡ réordonne, ⋮ › « Retirer de la playlist » enlève un morceau ;
  la playlist se renomme et se supprime depuis son menu (vos fichiers ne sont pas touchés).
- **Supprimer toutes les playlists** (la corbeille rouge en haut de Playlists) demande deux
  confirmations avant de tout supprimer ; c'est irréversible, alors utilisez d'abord
  **Tout exporter** pour en garder une copie.
- « Remplacer les titres YouTube par mes fichiers » remplace, quand c'est possible, les morceaux
  YouTube Music par le même morceau de votre bibliothèque.
- 🔍 cherche dans la playlist (titre, artiste, album).
- ⋮ › **Trier par…** titre, artiste, album ou durée : le nouvel ordre est enregistré dans la
  playlist, et Annuler sur le message rétablit l'ancien.
- ⋮ › **Trouver les doublons…** repère les morceaux présents plusieurs fois (le même fichier, ou
  la même chanson du même artiste et de même durée) et, après confirmation, garde la meilleure
  copie de chacun : FLAC plutôt que MP3, le meilleur débit, un fichier plutôt que sa version
  YouTube Music. Les versions live et les remix sont d'autres chansons. Seule la playlist change,
  jamais vos fichiers.
- Les morceaux introuvables (fichier absent, partage éteint) restent dans la playlist, grisés.
- Import et export : voir [Échanger les playlists avec le PC](ECHANGE-PC.md).

## Radio

Populaires, genres, pays, recherche dans le monde entier. L'étoile ajoute une radio aux
**Favoris**. La liste d'où une radio est lancée devient la file : « suivant » et « précédent »
(écran verrouillé, casque) changent de station. Le titre en cours s'affiche quand la radio l'indique ; les paroles sont alors
cherchées aussi.

## Lecteur

- Aléatoire, répétition (tout / un), file d'attente modifiable.
- **Égaliseur** : 10 bandes, préamplification, préréglages, et vos propres réglages ; appliqué
  à tout ce qui est lu.
- **Paroles** : d'abord celles du fichier, quand ses tags en contiennent (MP3, FLAC, Ogg, Opus,
  M4A ; synchronisées si elles portent des temps LRC) ; sinon celles de LRCLIB, synchronisées
  quand il les a. « Rechercher autrement » permet de corriger le titre ou l'artiste (et interroge
  LRCLIB).
- **Fiche du morceau** (⋮ sur n'importe quel morceau) : album, année, format et débit,
  emplacement du fichier, nombre d'écoutes, première et dernière fois, et des liens pour en
  savoir plus sur l'artiste : sa page YouTube Music, Wikipedia, Grokipedia, Last.fm et Discogs.
- Les commandes sont aussi dans la notification, sur l'écran verrouillé, sur un casque ou une
  voiture Bluetooth. **Paramètres › Lecture** indique si les notifications de l'application sont
  autorisées ; si les commandes manquent sur l'écran verrouillé, vérifiez dans les réglages du
  téléphone que l'écran de verrouillage affiche les notifications et le contenu multimédia.

## Statistiques

Icône en haut de l'Accueil : temps d'écoute, écoutes, découvertes, séries de jours, répartition
par heure et par jour, morceaux, artistes, albums et radios préférés, sur 7 jours, 30 jours,
1 an ou depuis le début. Tout reste sur le téléphone.

## Skins et langue

**Paramètres › Skin** : *Selon le système*, *Chud Nocturne* (sombre) ou *Platine* (clair).
**Paramètres › Langue** : français ou anglais (par défaut, celle du téléphone).

## Mises à jour

**Paramètres › Mises à jour** : Chud Music vérifie au plus une fois par jour la page GitHub des
versions. Une nouvelle version est téléchargée puis installée **seulement si vous touchez
« Mettre à jour »** ; Android demande la première fois d'autoriser Chud Music à installer des
applications. Vos playlists, réglages et statistiques sont conservés.
