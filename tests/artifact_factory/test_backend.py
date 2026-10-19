#!/usr/bin/env python3
"""Functional coverage of parser previews, templates and transactional deletion."""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import sqlite3
from contextlib import closing
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'Tools/artifact_factory'))
import factory
import cleaner
from template_core import EMBEDDED_PARSER_BLOCK


def fails(fn, text=None):
    try: fn()
    except Exception as exc:
        if text: assert text in str(exc), str(exc)
    else: raise AssertionError('Expected failure')


def small_case(path):
    with closing(sqlite3.connect(path)) as c:
        c.executescript('''PRAGMA foreign_keys=ON;
        CREATE TABLE tsk_objects(obj_id INTEGER PRIMARY KEY, par_obj_id INTEGER REFERENCES tsk_objects(obj_id));
        CREATE TABLE blackboard_artifact_types(artifact_type_id INTEGER PRIMARY KEY,category_type INTEGER,display_name TEXT);
        CREATE TABLE blackboard_artifacts(artifact_id INTEGER PRIMARY KEY,artifact_obj_id INTEGER UNIQUE REFERENCES tsk_objects(obj_id),artifact_type_id INTEGER REFERENCES blackboard_artifact_types(artifact_type_id));
        CREATE TABLE blackboard_attributes(artifact_id INTEGER REFERENCES blackboard_artifacts(artifact_id),value_text TEXT);
        CREATE TABLE tsk_analysis_results(artifact_obj_id INTEGER REFERENCES tsk_objects(obj_id));
        CREATE TABLE tsk_data_artifacts(artifact_obj_id INTEGER REFERENCES tsk_objects(obj_id));
        CREATE TABLE blackboard_artifact_tags(artifact_id INTEGER REFERENCES blackboard_artifacts(artifact_id));
        CREATE TABLE tsk_event_descriptions(event_description_id INTEGER PRIMARY KEY,artifact_id INTEGER REFERENCES blackboard_artifacts(artifact_id));
        CREATE TABLE tsk_events(event_description_id INTEGER REFERENCES tsk_event_descriptions(event_description_id));
        INSERT INTO tsk_objects VALUES(1,NULL),(2,1),(3,1);
        INSERT INTO blackboard_artifact_types VALUES(1,0,'Data'),(2,1,'Analysis');
        INSERT INTO blackboard_artifacts VALUES(100,2,1),(200,3,2);
        INSERT INTO blackboard_attributes VALUES(100,'Data'),(200,'Preserve');
        INSERT INTO tsk_data_artifacts VALUES(2);
        INSERT INTO tsk_analysis_results VALUES(3);
        INSERT INTO blackboard_artifact_tags VALUES(100);
        INSERT INTO tsk_event_descriptions VALUES(10,100),(20,200);
        INSERT INTO tsk_events VALUES(10),(20);
        ''')


def test_cleaner(work):
    path=work/'case.db';small_case(path)
    preview={'action':'preview','database':str(path)}
    before=hashlib.sha256(path.read_bytes()).hexdigest()
    r=cleaner.dispatch(preview)
    assert r['data_artifacts']==1 and r['analysis_results_preserved']==1
    assert hashlib.sha256(path.read_bytes()).hexdigest()==before
    request={**preview,'action':'apply','confirmation':'LOESCHEN','case_closed':True,'expected_count':1}
    fails(lambda:cleaner.dispatch({**request,'case_closed':False}),'Close the case')
    fails(lambda:cleaner.dispatch({**request,'expected_count':2}),'count changed')
    assert not list(work.glob('case.db.backup-*'))
    with closing(sqlite3.connect(path)) as c:
        c.execute("CREATE TRIGGER refuse_artifact_delete BEFORE DELETE ON blackboard_artifacts BEGIN SELECT RAISE(ABORT,'rollback test'); END")
    fails(lambda:cleaner.dispatch(request),'rollback test')
    assert cleaner.dispatch(preview)['data_artifacts']==1
    with closing(sqlite3.connect(path)) as c:c.execute('DROP TRIGGER refuse_artifact_delete')
    r=cleaner.dispatch(request);assert r['deleted']==1
    with closing(sqlite3.connect(r['backup'])) as c:assert c.execute('SELECT count(*) FROM blackboard_artifacts').fetchone()[0]==2
    with closing(sqlite3.connect(path)) as c:
        assert c.execute('SELECT * FROM blackboard_artifacts').fetchall()==[(200,3,2)]
        assert c.execute('SELECT * FROM blackboard_attributes').fetchall()==[(200,'Preserve')]
        assert c.execute('SELECT * FROM tsk_analysis_results').fetchall()==[(3,)]
        assert c.execute('SELECT * FROM tsk_objects').fetchall()==[(1,None),(3,1)]
        assert c.execute('SELECT * FROM tsk_event_descriptions').fetchall()==[(20,200)]
        assert c.execute('SELECT * FROM tsk_events').fetchall()==[(20,)]
        assert not c.execute('PRAGMA foreign_key_check').fetchall()
    print('PASS: Cleaner dry run, confirmation, stale preview, backup, rollback, timeline/tags removal and Analysis Result preservation')


def test_parser(work,jython=None):
    csv=work/'Straße.csv';csv.write_text('ts;lat;lon;name;phone;mac;duration\n2026-10-19T10:00:00;51.85;6.85;Müller;+49 (123);001122AABBCC;01:02\n',encoding='utf-8-sig')
    base={'meta':{'path':str(csv),'source_type':'csv','sep':';'},'plugin_type':'Geo-Track',
          'mapping':{'Timestamp':'ts','Latitude':'lat','Longitude':'lon'}}
    original=csv.read_bytes()
    r=factory.dispatch({**base,'action':'preview'});assert r['columns'][0]=='ts' and r['rows'][0]['name']=='Müller'
    r=factory.dispatch({**base,'action':'test'});assert r['rows'][0]['latitude']==51.85 and r['rows'][0]['timestamp_epoch']==1792404000
    for kind,fields in factory.FIELDS.items():
        mapping={field: {'Timestamp':'ts','Longitude':'lon','Latitude':'lat','Telefonnummer':'phone','Nummer':'phone','BluetoothAdresse':'mac','MACAdresse':'mac','Dauer':'duration'}.get(field,'name') for field in fields}
        req={**base,'plugin_type':kind,'mapping':mapping,'filename':'other-ü.csv','module_name':'Mon nom "ü"\nParser','action':'template'}
        code=factory.dispatch(req)['code'];compile(code,'generated.py','exec')
        assert str(csv) not in code and 'getModuleDisplayName' in code
        target=work/(kind+'.py');target.write_text(code,encoding='utf-8')
        if jython:
            subprocess.run(['java','-cp',str(jython),'org.python.util.jython','-m','py_compile',str(target)],check=True)
        normalized=factory.dispatch({**req,'action':'test'})
        assert normalized['count']==1
        if kind=='Call':assert normalized['rows'][0]['duration_seconds']==62
        if kind=='Mobile':assert normalized['rows'][0]['phone']=='+49123'
    output=work/'parsed.csv';factory.dispatch({**base,'action':'export_csv','output':str(output)})
    assert 'timestamp_epoch' in output.read_text(encoding='utf-8-sig')
    fails(lambda:factory.dispatch({**base,'action':'export_csv','output':str(csv)}),'differ')
    assert csv.read_bytes()==original
    # Multiline named groups and Unicode survive the native JSON request path.
    text=work/'multi.txt';text.write_text('name=Müller\nlat=51.5\nname=Other\nlat=52.5\n')
    req={'action':'preview','meta':{'path':str(text),'source_type':'regex','regex':r'(?m)name=(?P<name>[^\n]+)\nlat=(?P<lat>[\d.]+)'},'plugin_type':'Geo-Track','mapping':{}}
    assert len(factory.dispatch(req)['rows'])==2
    # Committed WAL rows are included, SQL preview is read-only.
    db=work/'wal.sqlite';c=sqlite3.connect(db);c.execute('PRAGMA journal_mode=WAL')
    c.execute('CREATE TABLE "odd table"(value TEXT)');c.execute("INSERT INTO 'odd table' VALUES('WAL value')");c.commit()
    req={'action':'preview','meta':{'path':str(db),'source_type':'sqlite','table':'odd table'},'mapping':{}}
    assert factory.dispatch(req)['rows']==[{'value':'WAL value'}]
    assert factory.dispatch({**req,'action':'tables'})['tables']==['odd table']
    bad=copy.deepcopy(req);bad['meta']['query']='DELETE FROM "odd table"'
    fails(lambda:factory.dispatch(bad),'readonly')
    assert c.execute('SELECT count(*) FROM "odd table"').fetchone()[0]==1;c.close()
    # Multi-step preprocessing, one step writing a file and one using binary stdout.
    writer=work/'preprocess.py';writer.write_text('import pathlib,sys\npathlib.Path(sys.argv[2]).write_bytes(pathlib.Path(sys.argv[1]).read_bytes())\n')
    steps=[{'name':'copy','command':'"'+sys.executable+'" "'+str(writer)+'" "{input}" "{output}"','output_suffix':'.csv','shell':False},
           {'name':'stdout','command':'"'+sys.executable+'" -c "import pathlib,sys;sys.stdout.buffer.write(pathlib.Path(sys.argv[1]).read_bytes())" "{input}"','output_suffix':'.csv','shell':False}]
    req=copy.deepcopy(base);req['action']='preview';req['meta']['preprocess_steps']=steps
    assert factory.dispatch(req)['rows'][0]['name']=='Müller';assert csv.read_bytes()==original
    # Actual Jython parser, not merely CPython compilation, for generated CSV/regex.
    if jython:
        test=work/'jython_runtime.py'
        suffix='''\nrows=parse_rows({"path":%r,"source_type":"csv","sep":";"},{"Timestamp":"ts","Latitude":"lat","Longitude":"lon"},"Geo-Track")
assert rows[0]["latitude"]==51.85
rows=parse_rows({"path":%r,"source_type":"csv","sep":";"},{"Vorname":"name"},"Mobile")
assert rows[0]["firstname"]==u"M\\u00fcller"
rows=parse_rows({"path":%r,"source_type":"regex","regex":%r},{"Kommentar":"name","Latitude":"lat"},"Geo-Bookmark")
assert len(rows)==2 and rows[0]["remark"]==u"M\\u00fcller"
print("PASS: actual Jython CSV Unicode and multiline Regex parsing")
'''%(str(csv),str(csv),str(text),r'(?m)name=(?P<name>[^\n]+)\nlat=(?P<lat>[\d.]+)')
        test.write_text('# coding=utf-8\n'+EMBEDDED_PARSER_BLOCK+suffix,encoding='utf-8')
        subprocess.run(['java','-cp',str(jython),'org.python.util.jython',str(test)],check=True)
    print('PASS: Seven artifact types, Unicode module/filenames, CSV, SQL/WAL, multiline Regex, mapping and multi-step preprocessing')


if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--jython',type=Path);args=ap.parse_args()
    with tempfile.TemporaryDirectory(prefix='artifact factory tests ') as td:
        work=Path(td);test_parser(work,args.jython);test_cleaner(work)
