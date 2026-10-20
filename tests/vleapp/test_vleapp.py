#!/usr/bin/env python3
"""VLEAPP writer-layout fixtures, preservation, malformed exports and streaming."""
import csv
import hashlib
import importlib.util
import json
from pathlib import Path
import sqlite3
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('vleapp_import',ROOT/'Tools/artifact_factory/vleapp.py')
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)


def fixture(root, rows=3):
    root.mkdir(parents=True,exist_ok=True)
    db=sqlite3.connect(root/'_lava_artifacts.db')
    db.execute('CREATE TABLE "from" ("from" TEXT, "Latitude" REAL, "raw" BLOB, "null" TEXT)')
    db.executemany('INSERT INTO "from" VALUES(?,?,?,?)',(('Müller\tZeile\n'+str(i),51.842,b'\x00\xff',None) for i in range(rows)))
    db.commit();db.close()
    # Matches lava_process_artifact(): category -> list of report descriptors.
    a={'name':'Navigation Positions','module':'synthetic_test','tablename':'from',
       'column_map':{'from':'Name','Latitude':'Latitude','raw':'Raw','null':'Optional'},
       'source_path':'/original/nav.db','record_count':rows}
    (root/'_lava_data.lava').write_text(json.dumps({'artifacts':{'Navigation':[a,a]},'lava_db_name':'_lava_artifacts.db'}),encoding='utf-8')
    (root/'_TSV Exports').mkdir(exist_ok=True)
    (root/'_TSV Exports'/'Duplicate.tsv').write_text('Name\nDO NOT IMPORT\n')
    return root


class ImportTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.base=Path(self.temp.name)
    def tearDown(self):self.temp.cleanup()
    def prepare(self,root):
        out=self.base/'out';out.mkdir()
        result=v.prepare({'input':str(root),'output':str(out)})
        return result,json.loads((out/'manifest.json').read_text(encoding='utf-8')),out
    def test_lava(self):
        root=fixture(self.base/'VLEAPP Export ü #')
        before={p.relative_to(root):p.read_bytes() for p in root.rglob('*') if p.is_file()}
        result,m,out=self.prepare(root)
        self.assertEqual((result['mode'],result['tables'],result['rows']),('lava',1,3))
        t=m['tables'][0];self.assertTrue(t['type_name'].startswith('VLEAPP_GEO_'))
        values=[json.loads(l) for l in (out/t['rows_file']).read_text(encoding='utf-8').splitlines()]
        self.assertEqual(values[0],['Müller\tZeile\n0',51.842,{'encoding':'base64','value':'AP8='},None])
        self.assertEqual(t['headers'],['Name','Latitude','Raw','Optional'])
        for rel,data in before.items():
            self.assertEqual((root/rel).read_bytes(),data)
            self.assertEqual((out/'report'/rel).read_bytes(),data)
        self.assertEqual(m['files'][0]['sha256'],hashlib.sha256((root/m['files'][0]['path']).read_bytes()).hexdigest())
    def test_tsv_and_repeated_bom_header(self):
        root=self.base/'_TSV Exports';root.mkdir()
        with (root/'Phonebook.tsv').open('w',encoding='utf-8-sig',newline='') as f:
            w=csv.writer(f,delimiter='\t');w.writerow(['Name','Name','Note']);w.writerow(['Müller','X','A\tB\nC']);w.writerow(['\ufeffName','Name','Note']);w.writerow(['','',''])
        result,m,out=self.prepare(root);self.assertEqual(result['rows'],2)
        t=m['tables'][0];self.assertEqual(t['headers'],['Name','Name','Note'])
        self.assertTrue(t['type_name'].startswith('VLEAPP_CONTACT_'))
        self.assertEqual(json.loads((out/t['rows_file']).read_text(encoding='utf-8').splitlines()[0]),['Müller','X','A\tB\nC'])
    def test_wal_and_snapshot_hashes(self):
        root=fixture(self.base/'report',1)
        con=sqlite3.connect(root/'_lava_artifacts.db')
        try:
            con.execute('PRAGMA journal_mode=WAL')
            con.execute('INSERT INTO "from" VALUES(?,?,?,?)',('WAL row',52.0,b'1',None));con.commit()
            result,m,out=self.prepare(root)
            self.assertEqual(result['rows'],2)
            for record in m['files']:
                self.assertEqual(hashlib.sha256((out/'report'/record['path']).read_bytes()).hexdigest(),record['sha256'])
        finally:con.close()

    def test_incomplete_lava_fails_without_tsv_fallback(self):
        root=fixture(self.base/'report');(root/'_lava_data.lava').unlink()
        with self.assertRaisesRegex(ValueError,'Incomplete'):self.prepare(root)
        self.assertEqual(list((self.base/'out').iterdir()),[])
    def test_missing_table(self):
        root=fixture(self.base/'report');p=root/'_lava_data.lava'
        d=json.loads(p.read_text(encoding='utf-8'));d['artifacts']['Navigation'][0]['tablename']='nope';p.write_text(json.dumps(d))
        with self.assertRaisesRegex(ValueError,'table'):self.prepare(root)
    def test_html_only(self):
        root=self.base/'report';root.mkdir();(root/'index.html').write_text('<script>bad()</script>')
        with self.assertRaisesRegex(ValueError,'HTML-only'):self.prepare(root)
    def test_malformed_tsv(self):
        root=self.base/'report';root.mkdir();(root/'x.tsv').write_text('a\tb\n1\t2\t3\n')
        with self.assertRaisesRegex(ValueError,'Column count'):self.prepare(root)
    def test_symlink_rejected(self):
        root=self.base/'report';root.mkdir();target=self.base/'outside';target.write_text('outside')
        try:(root/'secret.tsv').symlink_to(target)
        except OSError:self.skipTest('symlink unavailable')
        with self.assertRaisesRegex(ValueError,'regular'):self.prepare(root)
        self.assertEqual(target.read_text(encoding='utf-8'),'outside')
    def test_output_inside_input(self):
        root=self.base/'report';root.mkdir();out=root/'out';out.mkdir()
        with self.assertRaisesRegex(ValueError,'separate'):v.prepare({'input':str(root),'output':str(out)})
    def test_many_rows(self):
        result,m,out=self.prepare(fixture(self.base/'report',1201));self.assertEqual(result['rows'],1201)
        with (out/m['tables'][0]['rows_file']).open() as f:
            self.assertEqual(sum(1 for _ in f),1201)

if __name__=='__main__':unittest.main()
