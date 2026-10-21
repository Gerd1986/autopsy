#!/usr/bin/env python3
"""Exercise packaged VLEAPP Python and Java importer on a real native TSK case."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from test_vleapp import fixture
import semantic_fixture
ROOT=Path(__file__).resolve().parents[2]

def main():
    dist=Path(sys.argv[1]).resolve()
    java=dist/'jre/bin/java.exe';java=str(java if java.is_file() else 'java')
    python=dist/'autopsy/nand/python/python.exe'
    script=dist/'autopsy/artifact_factory/vleapp.py'
    modules=[dist/'autopsy/modules/ext',dist/'autopsy/modules',dist/'platform/lib',dist/'platform/modules',dist/'platform/core']
    cp=os.pathsep.join(str(p/'*') for p in modules)
    env=os.environ.copy();env['PATH']=os.pathsep.join(sorted({str(p.parent) for p in dist.rglob('*.dll')})+[env.get('PATH','')])
    with tempfile.TemporaryDirectory(prefix='VLEAPP packaged test ü ') as td:
        base=Path(td);source=fixture(base/'source',1201);out=base/'out';out.mkdir();classes=base/'classes';classes.mkdir()
        result=subprocess.check_output([str(python),str(script)],input=json.dumps({'input':str(source),'output':str(out)}).encode(),env=env)
        assert json.loads(result)['rows']==1201
        subprocess.run(['javac','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(ROOT/'tests/vleapp/VleappNativeTest.java')],check=True,env=env)
        for mode in ('normal','cancel','partial'):
            subprocess.run([java,'-cp',cp+os.pathsep+str(classes),'VleappNativeTest',str(out),mode],check=True,env=env)
        # Compile an isolated event-publisher boundary, but use the real packaged FileManager.
        stub=ROOT/'tests/vleapp/stubs/org/sleuthkit/autopsy/ingest/IngestServices.java'
        harness=ROOT/'tests/vleapp/VleappFileManagerTest.java'
        subprocess.run(['javac','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(stub),str(harness)],check=True,env=env)
        subprocess.run([java,'-cp',str(classes)+os.pathsep+cp,'VleappFileManagerTest',str(out)],check=True,env=env)
        legacy=base/'legacy';(legacy/'_TSV Exports'/'a').mkdir(parents=True);(legacy/'_TSV Exports'/'b').mkdir()
        for folder in ('a','b'):
            (legacy/'_TSV Exports'/folder/'Positions.tsv').write_text('Name\tLatitude\n'+folder+'\t51.8\n',encoding='utf-8')
        legacy_out=base/'legacy-out';legacy_out.mkdir()
        request=json.dumps({'input':str(legacy),'output':str(legacy_out)}).encode()
        assert json.loads(subprocess.check_output([str(python),str(script)],input=request,env=env))['rows']==2
        subprocess.run([java,'-cp',str(classes)+os.pathsep+cp,'VleappFileManagerTest',str(legacy_out)],check=True,env=env)
        subprocess.run(['javac','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(ROOT/'tests/vleapp/VleappSemanticsTest.java')],check=True,env=env)
        def prepare(request):
            result=json.loads(subprocess.check_output([str(python),str(script)],input=json.dumps(request).encode(),env=env))
            assert result['ok'],result
        semantic=base/'semantic-out';semantic_fixture.make(base/'semantic-source',semantic,prepare)
        subprocess.run([java,'-cp',str(classes)+os.pathsep+cp,'VleappSemanticsTest',str(semantic)],check=True,env=env)
        semantic_legacy=base/'semantic-legacy-out';semantic_fixture.legacy(base/'semantic-legacy-source',semantic_legacy,prepare)
        subprocess.run([java,'-cp',str(classes)+os.pathsep+cp,'VleappSemanticsTest',str(semantic_legacy)],check=True,env=env)
    print('PASS: packaged VLEAPP native import, cancellation, Unicode, blob/null values, reopened case icons')

if __name__=='__main__':main()
