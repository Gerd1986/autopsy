#!/usr/bin/env python3
"""Verify the packaged Java/Python bridge and offline cleanup on a real TSK case."""
import argparse
import hashlib
import struct
import zipfile
import json
import os
from pathlib import Path
import sqlite3
from contextlib import closing
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[2]


def main():
    ap=argparse.ArgumentParser();ap.add_argument('distribution',type=Path);args=ap.parse_args()
    dist=args.distribution.resolve();ext=dist/'autopsy/modules/ext'
    factory=dist/'autopsy/artifact_factory';python=dist/'autopsy/nand/python/python.exe'
    java=dist/'jre/bin/java.exe';java=str(java if java.is_file() else 'java')
    for name in ('factory.py','template_core.py','cleaner.py','dlt.py','geotime.py','geotime_core.py','preprocess.py'):
        assert (factory/name).read_bytes()==(ROOT/'Tools/artifact_factory'/name).read_bytes()
    modules=[ext,dist/'autopsy/modules',dist/'platform/lib',dist/'platform/modules',dist/'platform/core']
    cp=os.pathsep.join(str(p/'*') for p in modules)
    env=os.environ.copy();env['PATH']=os.pathsep.join(sorted({str(p.parent) for p in dist.rglob('*.dll')})+[env.get('PATH','')])
    with tempfile.TemporaryDirectory(prefix='artifact packaged test ') as td:
        work=Path(td);classes=work/'classes';classes.mkdir()
        sources=[ROOT/'tests/artifact_factory'/n for n in ('BackendHarness.java','NativeCaseFixture.java','CleanerLifecycleTest.java','CaseInputStagingTest.java')]
        subprocess.run(['javac','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(classes),*map(str,sources)],check=True,env=env)
        runtime_cp=cp+os.pathsep+str(classes)
        subprocess.run([java,'-cp',runtime_cp,'org.sleuthkit.autopsy.artifactfactory.CleanerLifecycleTest'],check=True,env=env)
        def run(tool,request):
            request_path=work/'request.json';request_path.write_text(json.dumps(request),encoding='utf-8')
            result=subprocess.check_output([java,'-cp',runtime_cp,'org.sleuthkit.autopsy.artifactfactory.BackendHarness',str(python),str(factory/tool),str(request_path)],env=env)
            return json.loads(result)
        subprocess.run([java,'-cp',runtime_cp,'org.sleuthkit.autopsy.artifactfactory.CaseInputStagingTest'],check=True,env=env)
        sample=work/'preview.csv';sample.write_text('name,phone\nMüller,+49123\n',encoding='utf-8')
        req={'action':'test','meta':{'path':str(sample),'source_type':'csv','sep':','},'mapping':{'Vorname':'name','Telefonnummer':'phone'},'plugin_type':'Mobile'}
        assert run('factory.py',req)['rows'][0]['firstname']=='Müller'
        from test_dlt_geotime import message
        trace=work/'trace.dlt';trace.write_bytes(message())
        decoded=run('factory.py',{'action':'preview','meta':{'path':str(trace),'source_type':'dlt'}})
        assert decoded['rows'][0]['payload']=='Müller -42'
        binary=work/'geo.bin';binary.write_bytes(struct.pack('<dd',7.2,51.8))
        carved=run('geotime.py',{'path':str(binary),'mode':'geo','format':'raw_float64_le','step':16,'output_directory':str(work/'exports')})
        assert carved['count']==1 and len(carved['exports'])==3
        core=dist/'autopsy/modules/org-sleuthkit-autopsy-core.jar'
        with zipfile.ZipFile(core) as jar:
            assert 'org/sleuthkit/autopsy/contentviewers/DltContentViewer.class' in jar.namelist()
            assert 'org/sleuthkit/autopsy/geolocation/GeoTimeCarvingAction.class' in jar.namelist()
            service=jar.read('META-INF/services/org.sleuthkit.autopsy.corecomponentinterfaces.DataContentViewer').decode('utf8')
            assert 'org.sleuthkit.autopsy.contentviewers.DltContentViewer' in service
            import xml.etree.ElementTree as ET
            layer=ET.fromstring(jar.read('META-INF/generated-layer.xml'))
            toolbar=layer.find("./folder[@name='Toolbars']/folder[@name='Case']")
            positions={}
            for entry in toolbar.findall('file'):
                attr=entry.find("attr[@name='position']")
                if attr is not None: positions[entry.attrib['name']]=int(attr.attrib['intvalue'])
            def position(name): return next(v for k,v in positions.items() if name in k)
            assert position('OpenGeolocationAction')<position('GeoTimeCarvingAction')<position('timeline-Timeline')<position('OpenDiscoveryAction')<position('ArtifactWizardAction')<position('ReportWizardAction')
        db=work/'autopsy.db'
        subprocess.run([java,'-cp',runtime_cp,'NativeCaseFixture','create',str(db)],check=True,env=env)
        with closing(sqlite3.connect(db)) as c:
            analysis=c.execute('SELECT * FROM tsk_analysis_results').fetchall()
            files=c.execute('SELECT * FROM tsk_files').fetchall()
            scores=c.execute('SELECT * FROM tsk_aggregate_score').fetchall()
        preview=run('cleaner.py',{'action':'preview','database':str(db)})
        assert preview['data_artifacts']==1 and preview['analysis_results_preserved']==1
        result=run('cleaner.py',{'action':'apply','database':str(db),'case_closed':True,'confirmation':'LOESCHEN','expected_count':1})
        assert result['deleted']==1 and Path(result['backup']).is_file()
        with closing(sqlite3.connect(db)) as c:
            assert c.execute('SELECT * FROM tsk_analysis_results').fetchall()==analysis
            assert c.execute('SELECT * FROM tsk_files').fetchall()==files
            assert c.execute('SELECT * FROM tsk_aggregate_score').fetchall()==scores
            assert not c.execute('PRAGMA foreign_key_check').fetchall()
        subprocess.run([java,'-cp',runtime_cp,'NativeCaseFixture','verify',str(db)],check=True,env=env)
    print('PASS: packaged stdlib Python via production Java bridge; real SQLite TSK case backup/deletion/reopen; Analysis Results, aggregate score and source files preserved')

if __name__=='__main__':main()
