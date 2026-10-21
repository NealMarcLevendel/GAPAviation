#!/usr/bin/env python3
"""Generate walkable cabin maps for a pack's aircraft, straight from their OBJ models.

For every plane JSON under assets/<pack>/jsondefs/vehicles/plane/ this:
  1. reads the seat positions from the JSON (to locate the cabin),
  2. densely samples every triangle of the plane's OBJ model,
  3. finds the cabin floor height (biggest flat surface just below the seats),
  4. marks a grid cell walkable when it has floor and nothing (seats, walls, galleys,
     lavatories, closed doors...) occupies the space a standing player needs,
  5. keeps only the area connected to the seats (drops wing tops, cargo holds, etc.),
and writes assets/gapcabinwalk/cabins/<pack>/<model>.json in THIS addon, which it loads at runtime.

Usage:  python3 tools/gen_cabin_maps.py [--pack-resources DIR] [--pack ID] [--preview DIR]
        (defaults: the sibling GoldenAeroTransport1.20.1 project, pack ivairlinerpack)
Requires numpy (and Pillow for --preview).
"""
import argparse, base64, glob, json, os, sys
from collections import deque
import numpy as np

HERE = os.path.dirname(os.path.abspath(__file__))
ADDON_RES = os.path.normpath(os.path.join(HERE, '..', 'src', 'main', 'resources'))
DEFAULT_PACK_RES = os.path.normpath(os.path.join(HERE, '..', '..', 'GoldenAeroTransport1.20.1', 'src', 'main', 'resources'))

CELL = 0.1          # grid resolution in blocks/metres
CLEAR_LO = 0.35     # obstacles lower than this above the floor can be stepped over
CLEAR_HI = 1.75     # a standing player needs this much headroom
FLOOR_TOL = 0.35    # how far a column's floor may deviate from the cabin floor level
Y_STEP = 0.01       # floor height quantisation in the output

# Model objects that should never block walking: MTS light/beam objects (&...),
# translucent light panels, and cockpit doors (so you can walk to the flight deck).
def ignored(name):
    n = name.lower()
    return (n.startswith('&') or 'light' in n or 'translucent' in n or 'beam' in n
            or 'cockdoor' in n or ('cockpit' in n and 'door' in n))


def load_obj(path):
    verts, tris, tri_obj = [], [], []
    cur = '_'
    with open(path, encoding='utf-8', errors='ignore') as fh:
        for line in fh:
            if line.startswith('v '):
                verts.append([float(t) for t in line.split()[1:4]])
            elif line.startswith('o ') or line.startswith('g '):
                cur = line[2:].strip()
            elif line.startswith('f '):
                ids = [int(t.split('/')[0]) for t in line.split()[1:]]
                ids = [i - 1 if i > 0 else len(verts) + i for i in ids]
                for k in range(1, len(ids) - 1):
                    tris.append((ids[0], ids[k], ids[k + 1]))
                    tri_obj.append(cur)
    V = np.asarray(verts, dtype=np.float64)
    keep = np.array([not ignored(o) for o in tri_obj], dtype=bool)
    return V[np.asarray(tris, dtype=np.int64)[keep]]


def sample_triangles(T, spacing, chunk=20000):
    """Sample points over every triangle (density ~1 per spacing^2, plus along long edges).
    Yields (points, horizontal-flag) batches to keep memory bounded."""
    rng = np.random.default_rng(1234)
    for s in range(0, len(T), chunk):
        C = T[s:s + chunk]
        n = np.cross(C[:, 1] - C[:, 0], C[:, 2] - C[:, 0])
        ln = np.linalg.norm(n, axis=1)
        ok = ln > 1e-12
        C, n, ln = C[ok], n[ok], ln[ok]
        horiz = np.abs(n[:, 1] / ln) > 0.9
        area = ln / 2
        edge = np.max(np.stack([np.linalg.norm(C[:, 1] - C[:, 0], axis=1),
                                np.linalg.norm(C[:, 2] - C[:, 1], axis=1),
                                np.linalg.norm(C[:, 0] - C[:, 2], axis=1)]), axis=0)
        cnt = np.maximum(np.ceil(area / spacing ** 2), np.ceil(3 * edge / spacing)).astype(np.int64)
        cnt = np.minimum(cnt, 400000)
        idx = np.repeat(np.arange(len(C)), cnt)
        u, v = rng.random(len(idx)), rng.random(len(idx))
        flip = u + v > 1
        u[flip], v[flip] = 1 - u[flip], 1 - v[flip]
        A = C[idx, 0]
        P = A + u[:, None] * (C[idx, 1] - A) + v[:, None] * (C[idx, 2] - A)
        yield np.concatenate([P, C.reshape(-1, 3)]), np.concatenate([horiz[idx], np.repeat(horiz, 3)])


def build_map(obj_path, seats):
    seats = np.asarray(seats, dtype=np.float64)
    T = load_obj(obj_path)
    xr = np.abs(seats[:, 0]).max() + 0.9
    z0, z1 = seats[:, 2].min() - 8.0, seats[:, 2].max() + 3.0
    seat_min_y = seats[:, 1].min()
    # Only triangles near the cabin matter.
    c = T.mean(axis=1)
    lo, hi = T.min(axis=1), T.max(axis=1)
    near = (hi[:, 0] > -xr) & (lo[:, 0] < xr) & (hi[:, 2] > z0) & (lo[:, 2] < z1) \
         & (hi[:, 1] > seat_min_y - 2.0) & (lo[:, 1] < seat_min_y + 3.0)
    x0 = -xr
    nx = int(np.ceil(2 * xr / CELL)); nz = int(np.ceil((z1 - z0) / CELL))
    Ps, Hs = [], []
    for P, H in sample_triangles(T[near], CELL * 0.4):
        keep = (P[:, 0] >= x0) & (P[:, 0] < x0 + nx * CELL) & (P[:, 2] >= z0) & (P[:, 2] < z0 + nz * CELL) \
             & (P[:, 1] > seat_min_y - 1.5) & (P[:, 1] < seat_min_y + 2.5)
        Ps.append(P[keep].astype(np.float32)); Hs.append(H[keep])
    P, H = np.concatenate(Ps).astype(np.float64), np.concatenate(Hs)
    ix = np.floor((P[:, 0] - x0) / CELL).astype(np.int64)
    iz = np.floor((P[:, 2] - z0) / CELL).astype(np.int64)
    ix, iz = np.clip(ix, 0, nx - 1), np.clip(iz, 0, nz - 1)
    y = P[:, 1]

    # Cabin floor level: most common flat-surface height just below the seats, measured
    # in the strip between the seat blocks and the seat rows' z-range.
    band = H & (y > seat_min_y - 1.0) & (y < seat_min_y - 0.1) \
         & (P[:, 2] > seats[:, 2].min()) & (P[:, 2] < seats[:, 2].max())
    if not band.any():
        raise RuntimeError('no floor surface found under the seats')
    hist, edges = np.histogram(y[band], bins=np.arange(seat_min_y - 1.0, seat_min_y - 0.05, 0.05))
    floor_level = edges[np.argmax(hist)] + 0.025

    # Per-column floor = highest flat surface within FLOOR_TOL of the cabin floor level.
    floor = np.full((nz, nx), -np.inf)
    m = H & (np.abs(y - floor_level) <= FLOOR_TOL)
    np.maximum.at(floor, (iz[m], ix[m]), y[m])

    # Obstacles: anything occupying the standing space above the column's floor.
    has_floor = np.isfinite(floor)
    f = floor[iz, ix]
    m = np.isfinite(f) & (y > f + CLEAR_LO) & (y < f + CLEAR_HI)
    blocked = np.zeros((nz, nx), dtype=bool)
    blocked[iz[m], ix[m]] = True
    walk = has_floor & ~blocked

    # Keep only the largest 4-connected walkable region: the cabin. This drops wing tops,
    # isolated gaps between seats, cargo holds and other unreachable pockets.
    label = np.zeros(walk.shape, dtype=np.int32)
    best, best_size, cur = 0, 0, 0
    for a0, b0 in zip(*np.nonzero(walk)):
        if label[a0, b0]:
            continue
        cur += 1; label[a0, b0] = cur; size = 0
        q = deque([(a0, b0)])
        while q:
            a, b = q.popleft(); size += 1
            for da, db in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                a2, b2 = a + da, b + db
                if 0 <= a2 < nz and 0 <= b2 < nx and walk[a2, b2] and not label[a2, b2]:
                    label[a2, b2] = cur; q.append((a2, b2))
        if size > best_size:
            best, best_size = cur, size
    keep = label == best

    # Window/portal geometry (used by the portal-based cabin): outer fuselage half-width at
    # window height over the seat rows, the walkable cabin's z-extent, and where the windshield ends.
    band_lo, band_hi = floor_level + 1.0, floor_level + 1.9
    seat_xmax = np.abs(seats[:, 0]).max()
    in_band = (y > band_lo) & (y < band_hi)
    over_seats = in_band & (P[:, 2] > seats[:, 2].min()) & (P[:, 2] < seats[:, 2].max()) & (np.abs(P[:, 0]) < seat_xmax + 1.2)
    half_width = float(np.percentile(np.abs(P[over_seats, 0]), 99.5)) if over_seats.any() else float(seat_xmax + 0.6)
    walk_rows = np.nonzero(keep.any(axis=1))[0]
    cabin_z0 = z0 + walk_rows.min() * CELL
    cabin_z1 = z0 + (walk_rows.max() + 1) * CELL
    nose = (y > floor_level + 0.9) & (y < floor_level + 2.6) & (np.abs(P[:, 0]) < half_width)
    nose_z = float(P[nose, 2].max()) if nose.any() else cabin_z1 + 2.0

    y_min = float(np.floor((floor_level - FLOOR_TOL) / Y_STEP) * Y_STEP)
    cells = np.zeros((nz, nx), dtype=np.uint8)
    q_y = np.clip(np.round((floor - y_min) / Y_STEP) + 1, 1, 255)
    cells[keep] = q_y[keep].astype(np.uint8)
    return {
        'version': 1, 'cell': CELL, 'x0': round(x0, 4), 'z0': round(z0, 4), 'nx': nx, 'nz': nz,
        'yMin': round(y_min, 4), 'yStep': Y_STEP, 'floorLevel': round(float(floor_level), 3),
        'halfWidth': round(half_width, 3), 'cabinZ0': round(float(cabin_z0), 3), 'cabinZ1': round(float(cabin_z1), 3),
        'noseZ': round(nose_z, 3),
        'cells': base64.b64encode(cells.tobytes()).decode('ascii'),
    }, cells


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--pack-resources', default=DEFAULT_PACK_RES, help='the content pack\'s src/main/resources folder')
    ap.add_argument('--pack', default='ivairlinerpack', help='the content pack\'s pack ID')
    ap.add_argument('--preview', help='also write PNG previews of each map to this folder')
    args = ap.parse_args()
    pack_root = os.path.join(args.pack_resources, 'assets', args.pack)
    if not os.path.isdir(pack_root):
        sys.exit(f'Pack not found at {pack_root} (use --pack-resources)')
    out_dir = os.path.join(ADDON_RES, 'assets', 'gapcabinwalk', 'cabins', args.pack)
    os.makedirs(out_dir, exist_ok=True)
    for js in sorted(glob.glob(os.path.join(pack_root, 'jsondefs', 'vehicles', 'plane', '**', '*.json'), recursive=True)):
        d = json.load(open(js, encoding='utf-8'))
        rel = os.path.relpath(os.path.dirname(js), os.path.join(pack_root, 'jsondefs', 'vehicles'))
        seats = [p['pos'] for p in d.get('parts', []) if 'seat' in p.get('types', [])]
        models = {os.path.splitext(os.path.basename(js))[0]}
        models |= {s['modelName'] for s in d.get('definitions', []) if s.get('modelName')}
        for model in sorted(models):
            obj = os.path.join(pack_root, 'objmodels', 'vehicles', rel, model + '.obj')
            if not seats or not os.path.exists(obj):
                print(f'skip {model}: no seats or no model'); continue
            try:
                data, cells = build_map(obj, seats)
            except Exception as e:
                print(f'skip {model}: {e}'); continue
            with open(os.path.join(out_dir, model + '.json'), 'w', encoding='utf-8') as fh:
                json.dump(data, fh)
            print(f'{model:12s} floor y={data["floorLevel"]:6.2f}  grid {data["nx"]}x{data["nz"]}  walkable cells {(cells > 0).sum()}')
            if args.preview:
                from PIL import Image
                os.makedirs(args.preview, exist_ok=True)
                img = np.zeros(cells.shape + (3,), np.uint8)
                img[cells > 0] = (80, 220, 120)
                for sx, sy, sz in seats:
                    a, b = int((sz - data['z0']) / CELL), int((sx - data['x0']) / CELL)
                    if 0 <= a < cells.shape[0] and 0 <= b < cells.shape[1]:
                        img[a, b] = (230, 60, 60)
                Image.fromarray(img[::-1]).resize((cells.shape[1] * 4, cells.shape[0] * 4), Image.NEAREST) \
                    .save(os.path.join(args.preview, model + '.png'))


if __name__ == '__main__':
    sys.exit(main())
