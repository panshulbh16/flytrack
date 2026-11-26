#!/usr/bin/env python3
"""Checks that every translatable Android string has a translation in each locale.

For each module (android/app, android/wear) it compares the names in res/values/strings*.xml
with res/values-<locale>/strings*.xml, and checks that every translation uses the same
format placeholders (%1$s, %d, {code}, ...) as its English source.

Usage: scripts/check-android-translations.py [locale ...]   (default: hi)
Exits 1 when a string is missing or a placeholder does not match.
"""
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "android")
MODULES = ["app", "wear"]
TAGS = ("string", "plurals", "string-array")
PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z%]|\{[a-z_]+\}")


def texts(element):
    if element.tag == "string":
        return ["".join(element.itertext())]
    return ["".join(item.itertext()) for item in element.findall("item")]


def placeholders(element):
    found = set()
    for text in texts(element):
        found.update(p for p in PLACEHOLDER.findall(text) if p != "%%")
    return found


def load(res, folder):
    out = {}
    for path in sorted(glob.glob(os.path.join(res, folder, "strings*.xml"))):
        for element in ET.parse(path).getroot():
            if element.tag in TAGS and element.get("translatable") != "false":
                out[element.get("name")] = (os.path.basename(path), element)
    return out


def main(locales):
    problems = 0
    for module in MODULES:
        res = os.path.join(ROOT, module, "src", "main", "res")
        source = load(res, "values")
        for locale in locales:
            target = load(res, "values-" + locale)
            missing = [n for n in source if n not in target]
            extra = [n for n in target if n not in source]
            mismatched = []
            for name, (_, element) in source.items():
                if name in target:
                    want = placeholders(element)
                    # A plural's "one" item may spell the number out, so only require a subset there.
                    got = placeholders(target[name][1])
                    if (got != want) if element.tag == "string" else not got <= want:
                        mismatched.append((name, sorted(want), sorted(got)))
            print(f"{module} [{locale}]: {len(source)} source, {len(target)} translated, "
                  f"{len(missing)} missing, {len(extra)} extra, {len(mismatched)} placeholder mismatches")
            for name in missing:
                print(f"  missing  {source[name][0]}: {name}")
            for name in extra:
                print(f"  extra    {target[name][0]}: {name}")
            for name, want, got in mismatched:
                print(f"  mismatch {name}: source {want}, translation {got}")
            problems += len(missing) + len(mismatched)
    print(f"TOTAL MISSING OR MISMATCHED: {problems}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:] or ["hi"]))
