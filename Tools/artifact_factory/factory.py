#!/usr/bin/env python3
"""JSON bridge for the native Autopsy Artifact Wizard; Python stdlib only."""
import csv
from contextlib import closing
import hashlib
import itertools
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import sqlite3
import subprocess
import sys
import tempfile
sys.path.insert(0, str(Path(__file__).resolve().parent))
from template_core import EMBEDDED_PARSER_BLOCK, build_autopsy_template

FIELDS = {
 'Geo-Track': ['Timestamp','Longitude','Latitude','Geschwindigkeit'],
 'Last-Position': ['Kommentar','Timestamp','Longitude','Latitude'],
 'Geo-Bookmark': ['Kommentar','Longitude','Latitude'],
 'Event': ['Timestamp','Eventname','Kommentar'],
 'Mobile': ['Nachname','Vorname','Telefonnummer','BluetoothAdresse'],
 'Bluetooth': ['Geraetename','BluetoothAdresse'],
 'Call': ['Timestamp','Anrufername','MACAdresse','Angerufener','Nummer','Dauer']}


def readonly(path):
    return sqlite3.connect(Path(path).resolve().as_uri()+'?mode=ro', uri=True, timeout=10)


def snapshot(path, directory):
    # Never connect SQLite to evidence: it can create/update a WAL index even
    # in read-only mode. Copy the main database and matching sidecars first.
    copied = Path(directory)/'input-snapshot'
    copied.mkdir()
    local = copied/'source.sqlite'
    shutil.copyfile(path, local)
    for suffix in ('-wal','-shm'):
        sidecar = Path(str(path)+suffix)
        if sidecar.is_file(): shutil.copyfile(sidecar, Path(str(local)+suffix))
    dest = Path(directory)/'preview.sqlite'
    with closing(readonly(local)) as source, closing(sqlite3.connect(dest)) as target:
        source.backup(target)
    return dest


def pipeline(meta, directory):
    current = Path(meta['path']).resolve()
    for index, step in enumerate(meta.get('preprocess_steps', [])):
        if not isinstance(step, dict) or not isinstance(step.get('command'), str) or not step['command'].strip():
            raise ValueError('Each preprocessing step needs a command')
        suffix = step.get('output_suffix', '.txt')
        if not isinstance(suffix, str) or not re.fullmatch(r'\.[A-Za-z0-9_.-]{1,32}', suffix):
            raise ValueError('Invalid preprocessing output suffix')
        output = Path(directory)/('step-%d'%index+suffix)
        command = step['command'].replace('{input}', str(current)).replace('{output}', str(output)).replace('{script_dir}', str(Path(meta['path']).resolve().parent))
        shell = step.get('shell', False)
        if type(shell) is not bool:
            raise ValueError('shell must be true or false')
        if not shell and os.name == 'nt':
            # CommandLineToArgvW semantics via the normal Windows process API.
            args = command
        else:
            args = command if shell else shlex.split(command)
        captured = Path(directory)/('stdout-%d'%index)
        with captured.open('wb') as stdout:
            result = subprocess.run(args, shell=shell, stdout=stdout, stderr=subprocess.PIPE, timeout=120)
        if result.returncode:
            raise ValueError('Preprocessing step %d failed: %s'%(index+1, result.stderr.decode('utf-8','replace')[-4000:]))
        if not output.exists():
            captured.replace(output)
        current = output
    return current


def rows(meta, directory, limit=None, schema=None):
    path = pipeline(meta, directory)
    kind = meta.get('source_type')
    if kind == 'csv':
        sep = meta.get('sep', ',').replace('\\t','\t')
        if len(sep) != 1:
            raise ValueError('CSV separator must be one character')
        with path.open('r', encoding='utf-8-sig', errors='replace', newline='') as f:
            reader = csv.DictReader(f, delimiter=sep)
            if not reader.fieldnames or len(set(reader.fieldnames)) != len(reader.fieldnames):
                raise ValueError('CSV needs unique column names')
            if schema is not None: schema.extend(reader.fieldnames)
            yield from itertools.islice(reader, limit)
    elif kind == 'sqlite':
        copied = snapshot(path, directory)
        with closing(readonly(copied)) as connection:
            connection.execute('PRAGMA query_only=ON')
            query = meta.get('query', '').strip()
            if not query:
                table = meta.get('table', '')
                if not table:
                    table = connection.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name LIMIT 1").fetchone()[0]
                query = 'SELECT * FROM "'+table.replace('"','""')+'"'
            cursor = connection.execute(query)
            if cursor.description is None:
                raise ValueError('Query must return rows')
            columns = [c[0] for c in cursor.description]
            if schema is not None: schema.extend(columns)
            if len(set(columns)) != len(columns):
                raise ValueError('Use unique SQL column aliases')
            for row in itertools.islice(cursor, limit):
                yield dict(zip(columns, ["0x"+v.hex() if isinstance(v, bytes) else v for v in row]))
    elif kind == 'regex':
        pattern = re.compile(meta.get('regex', ''))
        if not pattern.groupindex:
            raise ValueError('Regex requires named groups, for example (?P<latitude>...)')
        if schema is not None: schema.extend(pattern.groupindex)
        text = path.read_text(encoding='utf-8-sig', errors='replace')
        for match in itertools.islice(pattern.finditer(text), limit):
            yield match.groupdict()
    else:
        raise ValueError('Select CSV, SQLite or Regex')


def normalize(input_rows, mapping, kind):
    ns = {'unicode': str, 'unichr': chr, 'long': int}
    exec(EMBEDDED_PARSER_BLOCK, ns)
    ns['_run_preprocess_if_needed'] = lambda m:m
    ns['_iter_rows_from_csv_file'] = lambda *unused: iter(input_rows)
    return ns['parse_rows']({'source_type':'csv','path':'unused'}, mapping, kind)


def validate(request):
    meta = dict(request.get('meta', {}))
    source = Path(meta.get('path', ''))
    if not source.is_file():
        raise ValueError('Select a readable input file')
    meta['path'] = str(source.resolve())
    kind = request.get('plugin_type', 'Geo-Track')
    if kind not in FIELDS:
        raise ValueError('Unknown artifact type')
    mapping = request.get('mapping', {})
    if not isinstance(mapping, dict) or set(mapping)-set(FIELDS[kind]):
        raise ValueError('Invalid mapping fields')
    return meta, kind, mapping


def dispatch(request):
    action = request.get('action')
    meta, kind, mapping = validate(request)
    with tempfile.TemporaryDirectory(prefix='artifact-wizard-') as td:
        if action == 'tables':
            with closing(readonly(snapshot(pipeline(meta, td), td))) as connection:
                return {'tables':[r[0] for r in connection.execute("SELECT name FROM sqlite_master WHERE type IN ('table','view') ORDER BY name")]}
        if action == 'template':
            if not any(mapping.values()): raise ValueError('Map at least one source column')
            filename = request.get('filename','').strip()
            module = request.get('module_name','').strip()
            if not filename or '/' in filename or '\\' in filename:
                raise ValueError('Input filename/pattern must be a filename, without a path')
            if not module: raise ValueError('Enter an Autopsy module name')
            # Local evidence/preview paths are never embedded in exported ingest modules.
            meta['path'] = ''; meta['module_display_name'] = module
            meta.pop('script_dir', None)
            if meta.get('source_type') == 'sqlite' and not meta.get('query'):
                table = meta.get('table','')
                if not table: raise ValueError('Select a table or enter a SQL query')
                meta['query'] = 'SELECT * FROM "'+table.replace('"','""')+'"'
            code = build_autopsy_template(kind, filename, meta, mapping, True)
            compile(code, '<generated-ingest-module>', 'exec')
            return {'code':code}
        limit = 50 if action == 'preview' else None
        schema = []
        records = list(rows(meta, td, limit, schema))
        if action == 'preview':
            return {'columns':schema, 'rows':records, 'count':len(records)}
        if action not in ('test', 'export_csv'):
            raise ValueError('Unknown action')
        if not any(mapping.values()): raise ValueError('Map at least one source column')
        normalized = normalize(records, mapping, kind)
        result = {'count':len(normalized), 'rows':normalized[:50]}
        if action == 'export_csv':
            output = Path(request['output'])
            if output.resolve() == Path(meta['path']).resolve():
                raise ValueError('Output must differ from evidence input')
            with output.open('x',encoding='utf-8-sig',newline='') as f:
                columns = list(normalized[0]) if normalized else []
                writer = csv.DictWriter(f,fieldnames=columns); writer.writeheader(); writer.writerows(normalized)
            result['output'] = str(output)
        return result


if __name__ == '__main__':
    try:
        result = dispatch(json.load(sys.stdin))
        print(json.dumps({'ok':True, **result},ensure_ascii=True))
    except Exception as exc:
        print(json.dumps({'ok':False,'error':str(exc)},ensure_ascii=True))
        sys.exit(2)
