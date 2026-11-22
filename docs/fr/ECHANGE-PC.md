# Échanger les playlists avec Chud Music pour PC

*[English version](../PC-PLAYLISTS.md)*

Les deux applications lisent les mêmes fichiers, dans les deux sens, **sans rien changer côté PC**.

| Format | Contenu | Usage conseillé |
|---|---|---|
| **JSON** (`chud-music-playlists`, version 1) | toutes les playlists, morceaux locaux, réseau **et YouTube Music**, avec titre, artiste, album et durée | échange PC ⇄ téléphone |
| **M3U8** / M3U | une playlist : chemins des fichiers et liens YouTube Music | autres lecteurs (VLC, foobar…) |

## Du PC vers le téléphone

1. Dans Chud Music PC, exportez les playlists (JSON).
2. Copiez le fichier sur le téléphone (câble USB, cloud, partage réseau, e-mail…).
3. Sur le téléphone : onglet **Playlists › Importer** (icône ⬇), choisissez le fichier.
   Un fichier `.m3u` / `.m3u8` peut aussi être ouvert directement depuis un gestionnaire de fichiers.

Le message indique le nombre de playlists importées, de morceaux trouvés et de morceaux absents.

## Du téléphone vers le PC

- Toutes les playlists : onglet **Playlists › Tout exporter** (icône ⬆) → `chud-music-playlists.json`.
- Une seule playlist : dans la playlist, menu ⋮ › **Exporter (JSON, pour Chud Music PC)** ou
  **Exporter (M3U8)**.

Puis importez le fichier dans Chud Music PC comme d'habitude.

## Comment les morceaux sont retrouvés

Un chemin de fichier écrit sur un appareil existe rarement tel quel sur l'autre. Pour chaque
morceau « local » importé, Chud Music cherche dans cet ordre :

1. **le même chemin** sur le téléphone ;
2. **un chemin réseau** `\\serveur\partage\…` : si ce partage est ajouté dans le téléphone
   (Paramètres › Partages réseau, même serveur et même nom de partage), le fichier y est lu ;
3. **la même fin de chemin** (`Artiste\Album\fichier.mp3`) n'importe où dans la bibliothèque —
   utile quand le même dossier est vu sous deux noms (`\\NAS-SALON\…` sur le PC, IP sur le téléphone) ;
4. **les mêmes titre, artiste et durée**.

Un morceau introuvable **reste dans la playlist**, grisé, avec son chemin d'origine : il
redevient lisible s'il apparaît plus tard, et il est réexporté tel quel vers le PC.

Les morceaux YouTube Music (identifiant YouTube) sont toujours retrouvés.

## Conseils pour un échange sans perte

- Mettez la musique commune sur un **partage réseau** (NAS ou dossier partagé du PC) et ajoutez
  ce même partage dans le téléphone : les chemins `\\serveur\partage\…` correspondent alors
  exactement des deux côtés.
- À l'export depuis le téléphone, un fichier lu depuis un partage SMB est écrit avec son chemin
  réseau Windows (`\\serveur\partage\dossier\fichier`), que le PC sait ouvrir. Un fichier lu en
  WebDAV est écrit avec son adresse `https://…`.
- Les fichiers propres au téléphone (`/storage/emulated/0/Music/…`) n'existent pas sur le PC : le
  PC les retrouvera seulement s'il a les mêmes morceaux (par titre, artiste et durée, selon ce
  que sait faire la version PC).
- Le JSON du téléphone ajoute quelques champs (`albumArtist`, `trackNo`, `discNo`, `year`) que le
  PC ignore ; ils rendent le retour vers le téléphone plus précis.
- Les playlists intelligentes voyagent avec leurs règles (`smart`) et les morceaux du moment ; côté
  téléphone, l'image choisie pour une playlist aussi (`cover`). Détails : [EXCHANGE.md](../EXCHANGE.md).

## Détails du format M3U8 écrit par le téléphone

```
#EXTM3U
#PLAYLIST:Nom de la playlist
#EXTINF:215,Artiste - Titre
\\NAS-SALON\Musique\Artiste\Album\01 - Titre.flac
#EXTINF:267,Justice, Tame Impala - Neverender
https://music.youtube.com/watch?v=47YNsf-7Y7c
```

Encodage UTF-8, fins de ligne Windows (CRLF), comme celui du PC.
