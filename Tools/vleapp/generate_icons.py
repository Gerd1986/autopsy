#!/usr/bin/env python3
"""Reproduce the original VLEAPP category glyphs. Development-only Pillow dependency."""
from pathlib import Path
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2] / 'Core/src/org/sleuthkit/autopsy/images'


def generate():
    for name in ('vehicle', 'power', 'battery', 'settings', 'diagnostic', 'activity',
                 'gear', 'door', 'temperature', 'radio', 'chip', 'time', 'navigation', 'database', 'weather'):
        im = Image.new('RGBA', (96, 96))
        d = ImageDraw.Draw(im)
        color = '#355e83'
        def line(points, fill=color, width=1.7):
            d.line([(round(x*4), round(y*4)) for x, y in points], fill=fill, width=round(width*4), joint='curve')
        def oval(box, fill=None):
            d.ellipse(tuple(round(v*4) for v in box), outline=color, fill=fill, width=6)
        def rect(box):
            d.rounded_rectangle(tuple(round(v*4) for v in box), radius=5, outline=color, width=6)
        if name == 'vehicle':
            rect((3, 10, 21, 19));line([(5, 10), (8, 5), (17, 5), (20, 10)])
            oval((5, 17, 9, 21), color);oval((16, 17, 20, 21), color)
            line([(5, 13), (8, 13)]);line([(16, 13), (19, 13)])
        elif name == 'gear':
            # Automatic selector: a knob, lever and slotted gate, not a cogwheel.
            rect((8, 2, 16, 7));line([(12, 7), (12, 16)], width=2.5)
            rect((5, 15, 19, 22));line([(8, 19), (16, 19)], '#21805a', 2)
            line([(20, 4), (22, 4)]);line([(20, 8), (22, 8)]);line([(20, 12), (22, 12)])
        elif name == 'door':
            # Side-view vehicle door with window and handle.
            line([(3, 21), (3, 11), (7, 3), (19, 3), (21, 11), (21, 21), (3, 21)])
            line([(5, 11), (8, 5), (17, 5), (19, 11), (5, 11)])
            line([(16, 14), (19, 14)], '#21805a', 2)
        elif name == 'power':
            d.arc((12, 12, 84, 84), 305, 235, fill=color, width=8)
            line([(12, 2), (12, 11)], '#21805a', 2.4)
        elif name == 'battery':
            rect((2, 6, 20, 18));line([(22, 10), (22, 14)])
            line([(11, 7), (8, 12), (13, 12), (10, 17)], '#21805a')
        elif name == 'settings':
            for y, x in ((5, 9), (12, 16), (19, 7)):
                line([(3, y), (21, y)]);rect((x-2, y-2, x+2, y+2))
        elif name == 'diagnostic':
            line([(13, 2), (10, 6), (11, 10), (3, 18), (3, 21), (6, 21),
                  (15, 12), (19, 12), (22, 8), (18, 9), (15, 6), (16, 2), (13, 2)])
        elif name == 'activity':
            line([(2, 13), (6, 13), (9, 4), (14, 21), (17, 11), (22, 11)], '#21805a', 2)
        elif name == 'temperature':
            rect((9, 2, 15, 17));oval((7, 13, 17, 23));line([(12, 7), (12, 18)], '#c65b35', 2.3)
        elif name == 'radio':
            rect((3, 9, 21, 21));line([(6, 9), (18, 2)]);oval((6, 12, 13, 19))
            line([(16, 13), (19, 13)]);line([(16, 17), (19, 17)])
        elif name == 'chip':
            rect((6, 6, 18, 18));rect((9, 9, 15, 15))
            for a in (8, 12, 16):
                line([(a, 2), (a, 6)]);line([(a, 18), (a, 22)])
                line([(2, a), (6, a)]);line([(18, a), (22, a)])
        elif name == 'time':
            oval((2, 2, 22, 22));line([(12, 5), (12, 12), (17, 15)])
        elif name == 'navigation':
            line([(3, 11), (21, 3), (13, 21), (11, 13), (3, 11)], '#21805a', 2)
        elif name == 'database':
            oval((3, 3, 21, 10));line([(3, 6), (3, 19)]);line([(21, 6), (21, 19)])
            d.arc((12, 36, 84, 64), 0, 180, fill=color, width=6)
            d.arc((12, 60, 84, 88), 0, 180, fill=color, width=6)
        else:
            line([(7, 20), (4, 19), (2, 16), (3, 12), (6, 10), (8, 10),
                  (9, 6), (12, 4), (16, 5), (19, 9), (19, 11), (22, 13),
                  (22, 17), (20, 20), (7, 20)])
        im.resize((16, 16), Image.Resampling.LANCZOS).save(ROOT / ('vleapp-' + name + '.png'))


if __name__ == '__main__':
    generate()

