#!/usr/bin/env python3
"""Read-only VLEAPP export snapshot and streaming LAVA/TSV normalization.

Wire formats grounded in abrignoni/VLEAPP scripts/lavafuncs.py and ilapfuncs.py.
No VLEAPP code, HTML, JavaScript or export-supplied SQL is executed.
"""
import base64
import csv
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import stat
import sys

# Large cells are accepted without silent truncation. Import remains row-streamed.
csv.field_size_limit(64 * 1024 * 1024)


def identity(path):
    s = path.lstat()
    return s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns


def is_link(path):
    return path.is_symlink() or bool(getattr(path.lstat(), 'st_file_attributes', 0) & getattr(stat, 'FILE_ATTRIBUTE_REPARSE_POINT', 0))


def inventory(root):
    result = {}
    for parent, dirs, files in os.walk(root, followlinks=False):
        for name in dirs + files:
            p = Path(parent) / name
            mode = p.lstat().st_mode
            if not (stat.S_ISDIR(mode) or stat.S_ISREG(mode)) or is_link(p):
                raise ValueError('Only regular files/directories allowed: ' + str(p))
            result[p.relative_to(root).as_posix()] = identity(p)
    return result


def q(name):
    return '"' + name.replace('"', '""') + '"'


def cell(value):
    if isinstance(value, bytes):
        return {'encoding': 'base64', 'value': base64.b64encode(value).decode('ascii')}
    return value


def icon(category, name):
    text = (category + ' ' + name).casefold()
    for key, words in [('CONTACT', ('contact', 'phonebook', 'address book')),
                       ('CALL', ('call',)), ('MESSAGE', ('message', 'sms', 'chat')),
                       ('GEO', ('location', 'navigation', 'gps', 'route', 'destination', 'position')),
                       ('BLUETOOTH', ('bluetooth', 'paired')),
                       ('WIFI', ('wifi', 'wi-fi', 'wireless')),
                       ('MEDIA', ('media', 'music', 'photo', 'video'))]:
        if any(w in text for w in words):
            return key
    return 'RECORD'


def write_rows(output, key, headers, rows):
    filename = key + '.jsonl'
    count = 0
    with (output / filename).open('w', encoding='utf-8', newline='\n') as f:
        for row in rows:
            values = [cell(v) for v in row]
            if len(values) != len(headers):
                raise ValueError('Column count mismatch in ' + key)
            f.write(json.dumps(values, ensure_ascii=False, allow_nan=False) + '\n')
            count += 1
    return filename, count


def descriptor(category, name, module, table, source, headers):
    signature = json.dumps([category, name, module, table, headers], ensure_ascii=False)
    key = hashlib.sha256(signature.encode('utf-8')).hexdigest()[:24]
    return {'key': key, 'type_name': 'VLEAPP_' + icon(category, name) + '_' + key,
            'category': category, 'name': name, 'module': module,
            'table': table, 'source': source, 'headers': headers}


def lava_tables(root, output):
    meta = root / '_lava_data.lava'
    db = root / '_lava_artifacts.db'
    with meta.open(encoding='utf-8-sig') as f:
        metadata = json.load(f)
    categories = metadata.get('artifacts')
    if not isinstance(categories, dict):
        raise ValueError('Invalid LAVA artifacts metadata')
    result, seen = [], set()
    # SQLite may update WAL shared-memory read marks even on a read-only connection.
    # Parse a disposable copy so the report snapshot and its hashes stay stable.
    working = output / 'sqlite-read'
    working.mkdir()
    for suffix in ('', '-wal', '-shm'):
        original = Path(str(db) + suffix)
        if original.is_file():
            shutil.copy2(original, working / original.name)
    con = sqlite3.connect((working / db.name).resolve().as_uri() + '?mode=ro', uri=True)
    try:
        con.execute('PRAGMA query_only=ON')
        con.execute('PRAGMA trusted_schema=OFF')
        tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        for category, artifacts in categories.items():
            if not isinstance(artifacts, list):
                raise ValueError('LAVA category must be an array')
            for a in artifacts:
                table = a.get('tablename')
                if not isinstance(table, str) or table not in tables or table in {'_artifact_search_patterns', '_file_path_list', '_artifact_pattern_to_file', '_lava_media_items', '_lava_media_references'}:
                    raise ValueError('Missing/unsupported LAVA artifact table: ' + str(table))
                if table in seen:
                    continue  # aliases in metadata must not duplicate physical records
                seen.add(table)
                columns = [r[1] for r in con.execute('PRAGMA table_info(' + q(table) + ')')]
                mapping = a.get('column_map') or {}
                headers = [str(mapping.get(c, c)) for c in columns]
                d = descriptor(str(category), str(a.get('name') or table),
                               str(a.get('module') or ''), table, '_lava_artifacts.db', headers)
                d['original_source'] = a.get('source_path') or ''
                cursor = con.execute('SELECT ' + ','.join(q(c) for c in columns) + ' FROM ' + q(table))
                d['rows_file'], d['rows'] = write_rows(output, d['key'], headers, cursor)
                result.append(d)
    finally:
        con.close()
        shutil.rmtree(working)
    return result


def tsv_tables(root, output):
    folder = root / '_TSV Exports' if (root / '_TSV Exports').is_dir() else root
    paths = sorted(folder.rglob('*.tsv')) if folder != root else sorted(folder.glob('*.tsv'))
    result = []
    for path in paths:
        with path.open(encoding='utf-8-sig', newline='') as f:
            reader = csv.reader(f, delimiter='\t', strict=True)
            try:
                headers = next(reader)
            except StopIteration:
                raise ValueError('TSV has no header: ' + path.name)
            headers = [v.lstrip('\ufeff') for v in headers]
            if not headers or not any(headers):
                raise ValueError('Empty TSV header: ' + path.name)
            rel = path.relative_to(root).as_posix()
            d = descriptor('TSV', path.stem, '', rel, rel, headers)
            def rows():
                for row in reader:
                    if not row:
                        continue
                    # Legacy VLEAPP appends header lines when the same report is written again.
                    if [v.lstrip('\ufeff') for v in row] == headers:
                        continue
                    yield row
            d['rows_file'], d['rows'] = write_rows(output, d['key'], headers, rows())
            d['original_source'] = ''
            result.append(d)
    return result


def prepare(request):
    source = Path(request['input']).resolve(strict=True)
    output = Path(request['output']).resolve(strict=True)
    if not source.is_dir() or not output.is_dir() or any(output.iterdir()):
        raise ValueError('Select a report folder and an empty output directory')
    if source == output or source in output.parents or output in source.parents:
        raise ValueError('Snapshot and source must be separate directories')
    if is_link(Path(request['input'])):
        raise ValueError('Report root may not be a symlink')
    before = inventory(source)
    report = output / 'report'
    hashes = []
    try:
        shutil.copytree(source, report, symlinks=False)
        if inventory(source) != before:
            raise ValueError('Report changed while being copied; use a completed export')
        for rel in sorted(before):
            p = report / rel
            if p.is_file():
                h = hashlib.sha256()
                with p.open('rb') as f:
                    for chunk in iter(lambda: f.read(1024 * 1024), b''):
                        h.update(chunk)
                hashes.append({'path': rel, 'size': p.stat().st_size, 'sha256': h.hexdigest()})
        has_meta = (report / '_lava_data.lava').is_file()
        has_db = (report / '_lava_artifacts.db').is_file()
        if has_meta != has_db:
            raise ValueError('Incomplete LAVA export: metadata and database must both be present')
        mode = 'lava' if has_meta else 'tsv'
        tables = lava_tables(report, output) if has_meta else tsv_tables(report, output)
        if not tables:
            raise ValueError('No artifact tables: select a LAVA export or _TSV Exports folder; HTML-only reports are unsupported')
        manifest = {'schema': 1, 'mode': mode, 'source': str(source), 'report': str(report),
                    'tables': tables, 'files': hashes, 'rows': sum(t['rows'] for t in tables)}
        with (output / 'manifest.json').open('w', encoding='utf-8') as f:
            json.dump(manifest, f, ensure_ascii=False, indent=2)
        return {'ok': True, 'manifest': str(output / 'manifest.json'), 'rows': manifest['rows'],
                'tables': len(tables), 'mode': mode}
    except Exception:
        # No data source has been registered yet. Remove incomplete derived bytes.
        for p in output.iterdir():
            if p.is_dir():
                shutil.rmtree(p)
            else:
                p.unlink()
        raise


if __name__ == '__main__':
    try:
        print(json.dumps(prepare(json.load(sys.stdin)), ensure_ascii=True))
    except Exception as ex:
        print(json.dumps({'ok': False, 'error': str(ex)}, ensure_ascii=True))
        sys.exit(1)
