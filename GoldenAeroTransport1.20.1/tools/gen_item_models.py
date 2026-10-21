#!/usr/bin/env python3
"""Generate Immersive Vehicles (MTS) 1.20.1 item models for this pack.

MTS on 1.20.1 registers pack items as  mts:<packID>.<systemName><subName>  but no longer
auto-generates their item models, so inventory icons render as missing textures.
This writes one model per item to assets/mts/models/item/, pointing at the pack's
existing icon PNGs (LAYERED structure: textures/items/<type>/<subfolders>/<name>.png),
plus an atlas source so the 'textures/items/' folder gets stitched into the item atlas.

Re-run after adding/removing vehicles, parts or liveries:  python3 tools/gen_item_models.py
"""
import json, os, sys

RES = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src', 'main', 'resources')
PACK = 'ivairlinerpack'
pack_root = os.path.join(RES, 'assets', PACK)
out_dir = os.path.join(RES, 'assets', 'mts', 'models', 'item')
os.makedirs(out_dir, exist_ok=True)

# Remove previously generated models for this pack so stale entries don't linger.
for f in os.listdir(out_dir):
    if f.startswith(PACK + '.') and f.endswith('.json'):
        os.remove(os.path.join(out_dir, f))

count, missing = 0, []
for dp, _, files in os.walk(os.path.join(pack_root, 'jsondefs')):
    for f in sorted(files):
        if not f.endswith('.json'):
            continue
        rel = os.path.relpath(dp, os.path.join(pack_root, 'jsondefs')).split(os.sep)
        cls, prefix = rel[0], ''.join(p + '/' for p in rel[1:])
        with open(os.path.join(dp, f), encoding='utf-8') as fh:
            d = json.load(fh)
        system_name = f[:-5]
        subs = [x.get('subName', '') for x in d['definitions']] if 'definitions' in d else ['']
        for sub in subs:
            name = system_name + sub
            tex = f'items/{cls}/{prefix}{name}'
            if not os.path.exists(os.path.join(pack_root, 'textures', tex + '.png')):
                missing.append(tex)
            model = {"parent": "mts:item/basic", "textures": {"layer0": f"{PACK}:{tex}"}}
            with open(os.path.join(out_dir, f'{PACK}.{name}.json'), 'w', encoding='utf-8') as fh:
                json.dump(model, fh)
            count += 1

# Vanilla 1.20.1 only stitches textures/block and textures/item into the item atlas;
# MTS packs use textures/items, so add that folder as an atlas source.
atlas_dir = os.path.join(RES, 'assets', 'minecraft', 'atlases')
os.makedirs(atlas_dir, exist_ok=True)
with open(os.path.join(atlas_dir, 'blocks.json'), 'w', encoding='utf-8') as fh:
    json.dump({"sources": [{"type": "directory", "source": "items", "prefix": "items/"}]}, fh, indent=2)

print(f'Wrote {count} item models.')
if missing:
    print(f'WARNING: {len(missing)} icons missing:', *missing[:20], sep='\n  ')
    sys.exit(1)
