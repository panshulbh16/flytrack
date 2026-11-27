# Terminal maps

Simplified OpenStreetMap extracts for the terminal map screen (P4 contract,
section 4). One GeoJSON `FeatureCollection` per airport, named by IATA code:
DEL, BOM, BLR, HYD, MAA, CCU, COK, AMD.

## Format

Top-level members: `type`, `airport` (IATA), `icao`, `attribution`
(`"© OpenStreetMap contributors (ODbL)"`), `license` (`"ODbL-1.0"`),
`generated_at` (OSM data timestamp), `features`.

Each feature has an `id` (`"way/123"`, `"node/45"`, `"relation/6"`) and exactly
these properties:

| Property | Type | Notes |
|---|---|---|
| `kind` | string | see below |
| `ref` | string or null | gate number, taxiway/runway designator; for gates derived from a `Gate 12` name when `ref` is missing |
| `name` | string or null | `name:en`, else `name` |
| `level` | string, optional | only when tagged (`level` / `level:ref`) |

| `kind` | Geometry | OSM source |
|---|---|---|
| `terminal` | Polygon / MultiPolygon | `aeroway=terminal`, `building=terminal` (nodes and unclosed ways are dropped) |
| `apron` | Polygon | `aeroway=apron` (nodes and unclosed ways are dropped) |
| `runway`, `taxiway` | LineString (Polygon if mapped as an area) | `aeroway=runway`, `aeroway=taxiway` |
| `jet_bridge` | LineString / Polygon | `aeroway=jet_bridge` |
| `baggage_claim` | Polygon or Point | `aeroway=baggage_claim`, `amenity=baggage_claim` (arrival belt area, when mapped) |
| `gate`, `parking_position` | Point | `aeroway=gate`, `aeroway=parking_position` (areas collapse to their centroid) |
| `toilets`, `food`, `atm`, `lounge`, `prayer_room`, `pharmacy`, `charging`, `shop` | Point | `amenity=*` / `shop=*` inside a terminal polygon |

Features are ordered for drawing: apron, runway, taxiway, terminal,
baggage_claim, jet_bridge, parking_position, gate, then POIs. Coordinates are
`[lon, lat]`, 6 decimals; exterior rings are counter-clockwise (RFC 7946).
Each file is kept under 400 KB: the script doubles the simplification
tolerance as needed and, as a last resort, drops `parking_position`,
`jet_bridge`, `shop`, then `taxiway`.

An airport with no file means "Terminal map not available yet" in the apps.

## Regenerate

Requires Node 22+, no packages.

```sh
node scripts/build-terminal-maps.mjs            # all 8 airports
node scripts/build-terminal-maps.mjs DEL BOM    # a subset
```

* Queries `https://overpass-api.de/api/interpreter` (override with
  `OVERPASS_URL`), one airport at a time with a 10 s gap, retrying network
  errors, 429, 5xx and HTTP 200 responses whose `remark` reports an Overpass
  runtime error (timeout, out of memory) with exponential backoff.
* Behind an HTTPS proxy: `NODE_USE_ENV_PROXY=1` (and `NODE_EXTRA_CA_CERTS=...`).
* Writes `data/terminal-maps/{IATA}.geojson` and copies it into
  `android/app/src/main/assets/terminal-maps/` and
  `ios/FlyTrack/Resources/TerminalMaps/` (`--no-copy` to skip).
* A file is only written when it validates (has terminal polygons and gates,
  only the known kinds and properties, valid geometry, under the size cap) and
  the aerodrome itself was found by its ICAO code. Airports that fail keep
  their previous file. If Overpass is unreachable the script says so and exits
  with status 2.
* `--save-raw DIR` keeps the raw Overpass responses; `--from-dir DIR`
  rebuilds from saved responses (`DIR/{IATA}.json`) without the network.
  Use `--save-raw` once, then `--from-dir`, when tuning simplification.

Tests: `node --test scripts/build-terminal-maps.test.mjs`.

## Licence

© OpenStreetMap contributors, ODbL 1.0. See `LICENSE.md`.
