# Files exchanged with Chud Music for PC

Two JSON files, UTF-8. Unknown fields are ignored on both sides; a missing field leaves things as
they are. For how tracks are matched, see [Exchanging playlists](PC-PLAYLISTS.md).

## Playlists: `chud-music-playlists`, version 1

```json
{ "format": "chud-music-playlists", "version": 1, "exportedAt": "…",
  "playlists": [ { "name": "…", "tracks": [], "cover": "data:image/jpeg;base64,…", "smart": {} } ] }
```

| Field | Written by | Read by |
|---|---|---|
| `name`, `tracks` | both | both |
| `smart` | both | both |
| `cover` | phone | phone (the PC ignores it for now) |

`smart` is the PC app's description of a smart playlist (`src/shared/smart.ts`); `tracks` are then
those of the moment, for an app that does not know smart playlists.

```json
{ "match": "all", "rules": [ { "field": "played", "op": "before", "value": 182 } ],
  "exclude": [], "excludeIds": [], "order": "played", "desc": false, "limit": null }
```

- `match`: `all` or `any`.
- `op`: `contains`, `notContains`, `is`, `isNot`, `empty`, `eq`, `gte`, `lte`, `unknown`, `within`
  and `before` (days), `never`, `ever`.
- `excludeIds` are track ids of one device: the phone writes none in this file and ignores those it
  reads.
- The phone does not create a smart playlist twice under the same name with the same rules.

## Settings: `chud-music-settings`, version 1

Written and read by the phone. **The PC app does not read it yet**: this is what it would need.

| Field | Value | On import |
|---|---|---|
| `equalizer` | `{ enabled, preampDb, gains[10] }` | replaced |
| `eqPresets` | the user's presets | added |
| `crossfadeSec` | 0 (off) to 12 | replaced |
| `replayGain` | `off`, `track`, `album` | replaced |
| `hiddenArtists` | hidden YouTube Music artists | added |
| `radioFavorites` | stations | added |

It never holds a password, a server's address, a path, or anything about listening.
