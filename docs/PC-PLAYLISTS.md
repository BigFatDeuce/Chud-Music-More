# Exchanging playlists with Chud Music for PC

*[Version française](fr/ECHANGE-PC.md)*

Both apps read the same files, both ways, **with nothing to change on the PC side**.

| Format | Holds | Best for |
|---|---|---|
| **JSON** (`chud-music-playlists`, version 1) | every playlist; local, network **and YouTube Music** tracks, with title, artist, album and duration | PC ⇄ phone |
| **M3U8** / M3U | one playlist: file paths and YouTube Music links | other players (VLC, foobar2000…) |

## From the PC to the phone

1. In Chud Music for PC, export the playlists (JSON).
2. Copy the file to the phone (USB cable, cloud, network share, e-mail…).
3. On the phone: **Playlists › Import** (the ⬇ icon), choose the file. An `.m3u` / `.m3u8` file can
   also be opened straight from a file manager.

The message tells how many playlists were imported, how many tracks were found and how many are
missing.

## From the phone to the PC

- Every playlist: **Playlists › Export all** (the ⬆ icon) → `chud-music-playlists.json`.
- One playlist: in the playlist, ⋮ › **Export (JSON, for Chud Music PC)** or **Export (M3U8)**.

Then import the file in Chud Music for PC as usual.

## How tracks are found again

A file path written on one device rarely exists as such on the other. For each imported "local"
track, Chud Music looks, in this order, for:

1. **the same path** on the phone;
2. **a network path** `\\server\share\…`: if that share was added on the phone (Settings › Network
   shares, same server and same share name), the file is played from it;
3. **the same end of path** (`Artist\Album\file.mp3`) anywhere in the library — useful when the
   same folder is seen under two names (`\\NAS-LIVINGROOM\…` on the PC, an IP address on the phone);
4. **the same title, artist and duration**.

A track found nowhere **stays in the playlist**, greyed, with its original path: it becomes
playable if it shows up later, and it is exported back to the PC unchanged.

YouTube Music tracks (a YouTube id) are always found.

## Tips for a lossless exchange

- Keep the shared music on a **network share** (a NAS or a shared folder of the PC) and add that
  same share on the phone: `\\server\share\…` paths then match exactly on both sides.
- When exporting from the phone, a file played from an SMB share is written with its Windows
  network path (`\\server\share\folder\file`), which the PC can open. A WebDAV file is written
  with its `https://…` address.
- Files that only exist on the phone (`/storage/emulated/0/Music/…`) do not exist on the PC.
- The phone's JSON adds a few fields (`albumArtist`, `trackNo`, `discNo`, `year`) that the PC
  ignores; they make the way back to the phone more precise.
- Radio stations are not part of playlists exchanged with the PC.

## The M3U8 file written by the phone

```
#EXTM3U
#PLAYLIST:Playlist name
#EXTINF:215,Artist - Title
\\NAS-LIVINGROOM\Music\Artist\Album\01 - Title.flac
#EXTINF:267,Justice, Tame Impala - Neverender
https://music.youtube.com/watch?v=47YNsf-7Y7c
```

UTF-8, Windows line ends (CRLF), like the PC's. `#EXTINF:-1` when the duration is unknown.
