# Terminal map data licence

The files `*.geojson` in this folder (and their copies in
`android/app/src/main/assets/terminal-maps/` and
`ios/FlyTrack/Resources/TerminalMaps/`) are derived from
[OpenStreetMap](https://www.openstreetmap.org/) data.

**© OpenStreetMap contributors.** The data is available under the
[Open Database License (ODbL) 1.0](https://opendatacommons.org/licenses/odbl/1-0/).
See <https://www.openstreetmap.org/copyright>.

## What this means for FlyTrack

* Each file is a **Derivative Database** of OpenStreetMap: a filtered,
  simplified extract (selected `aeroway`, `amenity` and `shop` features inside
  each airport, with only the `kind`, `ref`, `name` and `level` properties,
  coordinates rounded to 6 decimals). These extracts are themselves offered
  under the ODbL 1.0; anyone may obtain them from this repository or regenerate
  them with `scripts/build-terminal-maps.mjs` (see `README.md`).
* The maps the apps draw from these files are a **Produced Work**. Every
  terminal map screen must show the attribution
  **"© OpenStreetMap contributors (ODbL)"** visibly on or next to the map.
  The same string is stored in each file's top-level `attribution` field.
* Do not mix proprietary data into these files. If other sources are ever
  combined with them, the combined database must also be offered under the ODbL.
