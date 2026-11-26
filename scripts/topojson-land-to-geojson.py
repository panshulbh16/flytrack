#!/usr/bin/env python3
"""Convert the world-atlas land TopoJSON into the GeoJSON the Android map bundles.

Input:  web/share/vendor/world-atlas@2.0.2-land-110m.json (Natural Earth 1:110m land, public domain)
Output: android/app/src/main/assets/map/land-110m.geojson

The Android map (feature/map) builds its MapLibre style in code and reads this file through an
asset:// URL, so the map works with no tile server and in airplane mode. Run it again only if the
input changes; the output is committed.

    python3 scripts/topojson-land-to-geojson.py [input] [output]

Only the TopoJSON features world-atlas uses are supported: a quantized topology ("transform"
with delta-encoded arcs) holding Polygon / MultiPolygon geometries.
"""

import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_INPUT = os.path.join(ROOT, "web", "share", "vendor", "world-atlas@2.0.2-land-110m.json")
DEFAULT_OUTPUT = os.path.join(ROOT, "android", "app", "src", "main", "assets", "map", "land-110m.geojson")

# 3 decimals is about 110 m at the equator: far below what a 1:110m outline can show.
DECIMALS = 3


def decode_arcs(topology):
    """Absolute lon/lat for every arc (undoes the quantization and the delta encoding)."""
    transform = topology.get("transform")
    arcs = []
    for arc in topology["arcs"]:
        points = []
        x = y = 0
        for position in arc:
            if transform:
                x += position[0]
                y += position[1]
                lon = x * transform["scale"][0] + transform["translate"][0]
                lat = y * transform["scale"][1] + transform["translate"][1]
            else:
                lon, lat = position[0], position[1]
            points.append([round(lon, DECIMALS), round(lat, DECIMALS)])
        arcs.append(points)
    return arcs


def ring(arc_indexes, arcs):
    """Stitches a ring from arc references; a negative index (~i) means arc i reversed."""
    points = []
    for index in arc_indexes:
        arc = arcs[~index][::-1] if index < 0 else arcs[index]
        # Consecutive arcs share their joining point; keep it once.
        points.extend(arc if not points else arc[1:])
    cleaned = [p for i, p in enumerate(points) if i == 0 or p != points[i - 1]]
    if cleaned and cleaned[0] != cleaned[-1]:
        cleaned.append(cleaned[0])
    return cleaned


def polygon(rings, arcs):
    out = [ring(r, arcs) for r in rings]
    return [r for r in out if len(r) >= 4]


def geometry(geom, arcs):
    kind = geom["type"]
    if kind == "Polygon":
        return {"type": "Polygon", "coordinates": polygon(geom["arcs"], arcs)}
    if kind == "MultiPolygon":
        polygons = [p for p in (polygon(rings, arcs) for rings in geom["arcs"]) if p]
        return {"type": "MultiPolygon", "coordinates": polygons}
    raise ValueError("unsupported geometry type: " + kind)


def convert(topology, object_name="land"):
    arcs = decode_arcs(topology)
    obj = topology["objects"][object_name]
    geometries = obj["geometries"] if obj["type"] == "GeometryCollection" else [obj]
    features = [
        {"type": "Feature", "properties": {}, "geometry": geometry(g, arcs)}
        for g in geometries
    ]
    return {"type": "FeatureCollection", "features": features}


def main(argv):
    source = argv[1] if len(argv) > 1 else DEFAULT_INPUT
    target = argv[2] if len(argv) > 2 else DEFAULT_OUTPUT
    with open(source, encoding="utf-8") as f:
        topology = json.load(f)
    collection = convert(topology)
    os.makedirs(os.path.dirname(target), exist_ok=True)
    with open(target, "w", encoding="utf-8") as f:
        json.dump(collection, f, separators=(",", ":"))
        f.write("\n")
    polygons = sum(
        len(feat["geometry"]["coordinates"]) if feat["geometry"]["type"] == "MultiPolygon" else 1
        for feat in collection["features"]
    )
    print(f"wrote {target}: {len(collection['features'])} feature(s), {polygons} polygon(s), {os.path.getsize(target)} bytes")


if __name__ == "__main__":
    main(sys.argv)
