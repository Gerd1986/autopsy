"""LAVA's object_columns/artifact_icon wire format and real map expectations."""
from datetime import datetime, timezone
import json
from pathlib import Path
import sqlite3


def make(source, out, prepare):
    source.mkdir()
    stamp = datetime(2026, 10, 21, 8, 53, 16, 125000, tzinfo=timezone.utc).timestamp()
    con = sqlite3.connect(source / '_lava_artifacts.db')
    con.execute('CREATE TABLE positions (name TEXT, latitude REAL, longitude REAL, stamp REAL, raw_lat INTEGER, day TEXT, boot INTEGER, clock TEXT)')
    rows = [
        ('Müller', 51.842, 6.858, stamp, 518420000, '2026-10-21', 99, '2026-10-21 10:53:16'),
        ('Zero', 0, 0, 0, 0, '1970-01-01', 0, ''),
        ('Before epoch', -45, -120, -0.5, -450000000, '1969-12-31', 2, ''),
        ('No time', 52, 7, None, 520000000, None, 3, ''),
        ('Invalid lat', 91, 7, stamp, 0, 'bad-date', 4, ''),
        ('Invalid lon', 51, 181, stamp, 0, '', 5, ''),
        ('Missing coordinate', 51, None, stamp, 0, '', 6, ''),
        ('NaN', 'NaN', 7, stamp, 0, '', 7, ''),
    ]
    con.executemany('INSERT INTO positions VALUES (?,?,?,?,?,?,?,?)', rows)
    con.execute('CREATE TABLE destinations (name TEXT, latitude REAL, longitude REAL, stamp TEXT)')
    con.execute('INSERT INTO destinations VALUES (?,?,?,?)', ('Home', 51.8, 6.8, '2026-10-21T10:53:16+02:00'))
    con.execute('CREATE TABLE settings (name TEXT, value INTEGER)')
    con.execute('INSERT INTO settings VALUES (?,?)', ('Epoch-sized identifier', 1792572796))
    con.commit();con.close()
    metadata = {'artifacts': {'Ford Vehicles': [
        {'name': 'Ford - Positions In Log', 'module': 'test', 'tablename': 'positions', 'artifact_icon': 'map-pin',
         'column_map': {'name': 'Name', 'latitude': 'Latitude', 'longitude': 'Longitude', 'stamp': 'Timestamp UTC',
                        'raw_lat': 'Latitude (as stored)', 'day': 'Date', 'boot': 'Boot Count', 'clock': 'Local Time (as stored)'},
         'object_columns': [{'name': 'stamp', 'type': 'datetime'}, {'name': 'day', 'type': 'date'}]},
        {'name': 'Ford - Navigation Recent Destinations', 'module': 'test', 'tablename': 'destinations', 'artifact_icon': 'search',
         'column_map': {'name': 'Name', 'latitude': 'Latitude', 'longitude': 'Longitude', 'stamp': 'Timestamp'},
         'object_columns': [{'name': 'stamp', 'type': 'datetime'}]},
        {'name': 'Ford - Navigation Settings', 'module': 'test', 'tablename': 'settings', 'artifact_icon': 'settings'}
    ]}}
    (source / '_lava_data.lava').write_text(json.dumps(metadata), encoding='utf-8')
    out.mkdir();prepare({'input': str(source), 'output': str(out)})
    expected = {'rows': 10, 'geo': 5, 'trackpoints': 4, 'searches': 1,
                'types': ['GEO', 'SEARCH', 'SETTINGS'],
                'display': ['21.10.2026 08:53:16.125 UTC', '01.01.1970 00:00:00 UTC',
                            '31.12.1969 23:59:59.5 UTC', '', '21.10.2026 08:53:16.125 UTC'],
                'utc_seconds': int(stamp)}
    (out / 'semantics-expectations.json').write_text(json.dumps(expected), encoding='utf-8')


def legacy(source, out, prepare):
    source.mkdir()
    (source / 'Favorite Places.tsv').write_text(
        'Name\tLatitude\tLongitude\tTimestamp UTC (ms)\tLocal Time (as stored)\n'
        'Home\t51.8\t6.8\t1792572796125\t2026-10-21 10:53:16\n'
        'Invalid\t999\t6.8\tbad\tunchanged\n', encoding='utf-8')
    out.mkdir();prepare({'input': str(source), 'output': str(out)})
    (out / 'semantics-expectations.json').write_text(json.dumps({
        'rows': 2, 'geo': 1, 'trackpoints': 0, 'searches': 0, 'bookmarks': 1, 'types': ['BOOKMARK'],
        'display': ['21.10.2026 08:53:16.125 UTC', 'bad'], 'utc_seconds': 1792572796}), encoding='utf-8')
