#!/usr/bin/env python3
"""Run the production Java bridge with bundled NAND tooling and native FAT32 reads."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--sleuthkit', required=True, type=Path)
    ap.add_argument('--script', type=Path, default=ROOT/'Tools/nand/preprocess_ubi.py')
    ap.add_argument('--profile', type=Path, default=ROOT/'Tools/nand/profiles/synthetic-lba-bch-fat32.json')
    ap.add_argument('--python', default=sys.executable)
    ap.add_argument('--fixture', type=Path)
    ap.add_argument('--native', action='store_true')
    args = ap.parse_args()
    sys.path.insert(0, str(args.sleuthkit/'tests'))
    from nand_lba_fixture import create
    with tempfile.TemporaryDirectory(prefix='autopsy nand lba ') as td:
        temp = Path(td)
        fixture = args.fixture or temp/'synthetic fixture'
        if not (fixture/'expected.json').is_file():
            create(fixture)
        expected = json.loads((fixture/'expected.json').read_text())
        classes = temp/'classes'; classes.mkdir()
        compiler = ['javac'] if shutil.which('javac') else ['java', 'com.sun.tools.javac.Main']
        subprocess.run(compiler+['-d', str(classes),
            str(ROOT/'Core/src/org/sleuthkit/autopsy/casemodule/NandPreprocessor.java'),
            str(ROOT/'tests/nand/BridgeHarness.java')], check=True)
        source = fixture/'synthetic-nand-ftl-ecc-fat32.bin'
        result = subprocess.run(['java', '-cp', str(classes),
            'org.sleuthkit.autopsy.casemodule.BridgeHarness', str(source), str(args.profile),
            str(temp/'case exports'), str(args.script), args.python, 'normal'],
            check=True, capture_output=True, text=True, timeout=120)
        output = Path(result.stdout.strip())
        assert output.suffix == '.img'
        assert output.parent.parent == temp/'case exports/nand-preprocessed'
        assert hashlib.sha256(output.read_bytes()).hexdigest() == expected['reference_sha256']
        assert output.read_bytes() == (fixture/'expected-fat32.img').read_bytes()
        assert (output.parent/'device-profile.json').read_bytes() == args.profile.read_bytes()
        report = json.loads(Path(str(output)+'.report.json').read_text())
        assert report['output'] == str(output.absolute())
        assert report['status'] == 'completed' and report['corrected_bits'] == 6
        assert report['duplicate_pages'] == 2 and report['erased_pages'] == 2
        assert report['lba_mapping']['selected_pages'] == expected['selected_pages']
        assert not report['lba_mapping']['missing_ranges']
        if args.native:
            win32 = args.sleuthkit/'win32'
            env = os.environ.copy()
            dll_dirs = sorted({str(p.parent) for p in win32.rglob('*.dll')})
            env['PATH'] = os.pathsep.join(dll_dirs+[env.get('PATH', '')])
            def run(name, *params):
                binaries = sorted(win32.rglob(name+'.exe'))
                if not binaries:
                    raise AssertionError('missing native tool '+name)
                return subprocess.check_output([str(binaries[0]), *map(str, params)], env=env)
            assert b'FAT32' in run('fsstat', output)
            listing = run('fls', '-p', output).decode()
            for name, info in expected['files'].items():
                inode = re.search(r'(\d+):\t'+re.escape(name)+r'\r?$', listing, re.M)
                assert inode, listing
                payload = run('icat', output, inode[1])
                assert len(payload) == info['bytes']
                assert hashlib.sha256(payload).hexdigest() == info['sha256']
            print('PASS: Native FAT32 auto-detection and file hashes after Java NAND bridge')
        print('PASS: Bundled NAND script via production Java bridge: six BCH bits corrected, LBA ordering, stale duplicates, exact FAT32 image and export report')


if __name__ == '__main__':
    main()
