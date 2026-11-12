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

## Musique du téléphone

- **Paramètres › Musique du téléphone › Dossiers** : décochez un dossier (sonneries, notes
  vocales…) pour le laisser hors de la bibliothèque. Rien n'est supprimé.
- **Réanalyser** relit tout de suite le téléphone et les partages réseau.
- « Retirer de la bibliothèque » masque un morceau ; il revient à la prochaine analyse.
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
- Le téléphone doit être sur le même réseau que le serveur (ou via un VPN).

## YouTube Music

- Recherche avec suggestions ; résultats par **Titres**, **Albums**, **Artistes**, **Vidéos**.
- Pages album et artiste (titres populaires, albums, singles, artistes similaires).
- **Démarrer le mix** lance une radio sans fin à partir d'un morceau.
- Depuis l'application YouTube ou un navigateur, **Partager › Chud Music** un lien YouTube ou
  YouTube Music le lit directement.
- Sans compte Google et sans publicité. Les morceaux s'ajoutent aux playlists comme les autres.
- **Paramètres › YouTube Music › Préférer le format Opus** : meilleure qualité ; à désactiver si
  un vieil appareil Bluetooth lit mal (AAC à la place).

## Playlists

- **+ Nouvelle playlist**, ou ⋮ › « Ajouter à une playlist » depuis un morceau, un album…
- Dans une playlist : la poignée ≡ réordonne, ⋮ › « Retirer de la playlist » enlève un morceau ;
  la playlist se renomme et se supprime depuis son menu (vos fichiers ne sont pas touchés).
- « Remplacer les titres YouTube par mes fichiers » remplace, quand c'est possible, les morceaux
  YouTube Music par le même morceau de votre bibliothèque.
- Les morceaux introuvables (fichier absent, partage éteint) restent dans la playlist, grisés.
- Import et export : voir [Échanger les playlists avec le PC](ECHANGE-PC.md).

## Radio

Populaires, genres, pays, recherche dans le monde entier. L'étoile ajoute une radio aux
**Favoris**. Le titre en cours s'affiche quand la radio l'indique ; les paroles sont alors
cherchées aussi.

## Lecteur

- Aléatoire, répétition (tout / un), file d'attente modifiable.
- **Égaliseur** : 10 bandes, préamplification, préréglages, et vos propres réglages ; appliqué
  à tout ce qui est lu.
- **Paroles** : synchronisées quand LRCLIB les a, sinon simples. « Rechercher autrement » permet
  de corriger le titre ou l'artiste.
- Les commandes sont aussi dans la notification, sur l'écran verrouillé, sur un casque ou une
  voiture Bluetooth.

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
