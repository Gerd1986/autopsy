#!/usr/bin/env python3
"""Exact timestamp roundtrips, typed schema exports and real native Jython writes."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'Tools/artifact_factory'))
import factory
from template_core import EMBEDDED_PARSER_BLOCK


def field(name,kind,label=None):return {'name':name,'value_type':kind,'label':label or name}


def check(work,jython=None,native_cp=None):
    source=work/'time.csv'
    source.write_text('time,name,lat,lon,count,blob,payload\n2026-10-21T10:53:16.123+02:00,Müller,51.8,6.8,9223372036854775807,00ff,"{""ok"":true}"\n',encoding='utf-8')
    spec={'type_name':'VLEAPP_GEO_0123456789abcdef01234567','display_name':'Fahrzeugposition','icon':'GEO',
          'attributes':[field('TSK_DATETIME','DATETIME'),field('TSK_NAME','STRING'),field('TSK_GEO_LATITUDE','DOUBLE'),
                        field('TSK_GEO_LONGITUDE','DOUBLE'),field('WIZARD_TEST_COUNT','LONG'),field('WIZARD_TEST_BLOB','BYTE'),field('WIZARD_TEST_JSON','JSON')]}
    mapping=dict(zip([f['name'] for f in spec['attributes']],['time','name','lat','lon','count','blob','payload']))
    req={'action':'test','plugin_type':'Generic','meta':{'path':str(source),'source_type':'csv','artifact_schema':spec},'mapping':mapping}
    record=factory.dispatch(req)['rows'][0]
    stamp=record['fields']['TSK_DATETIME']
    assert stamp['timestamp_str']=='2026-10-21 08:53:16.123 UTC',stamp
    assert stamp['timestamp_epoch']==1792572796 and stamp['timestamp_nanosecond']==123000000
    assert record['fields']['WIZARD_TEST_COUNT']==9223372036854775807
    exported=work/'normalized.csv';factory.dispatch({**req,'action':'export_csv','output':str(exported)})
    assert '16.123 UTC' in exported.read_text(encoding='utf-8-sig')
    ns={'unicode':str,'unichr':chr,'long':int};exec(EMBEDDED_PARSER_BLOCK,ns)
    cases=[
        ('1792572796.12','seconds','2026-10-21 08:53:16.12 UTC',120000000),
        ('1792572796123','milliseconds','2026-10-21 08:53:16.123 UTC',123000000),
        ('1792572796123456','microseconds','2026-10-21 08:53:16.123456 UTC',123456000),
        ('1792572796123456789','nanoseconds','2026-10-21 08:53:16.123456789 UTC',123456789),
        ('21.10.2026 08:53:16,12','auto','2026-10-21 08:53:16.12 UTC',120000000),
        ('-0.5','seconds','1969-12-31 23:59:59.5 UTC',500000000),
        ('0','seconds','1970-01-01 00:00:00 UTC',0),
    ]
    for raw,unit,display,nanos in cases:
        result=ns['timestamp_details'](raw,unit)
        assert (result['timestamp_str'],result['timestamp_nanosecond'])==(display,nanos),result
    assert ns['timestamp_details']('1792572796','seconds',precision='3')['timestamp_str'].endswith('16.000 UTC')
    assert ns['timestamp_details']('21.10.2026 08:53:16.12',pattern='%d.%m.%Y %H:%M:%S.%f')['timestamp_str'].endswith('16.12 UTC')
    assert ns['timestamp_details']('invalid')['timestamp_epoch'] is None
    template=factory.dispatch({**req,'action':'template','filename':'time.csv','module_name':'Eigene Artefakte'})['code']
    assert str(source) not in template
    compile(template,'generated.py','exec')
    target=work/'generated.py';target.write_text(template,encoding='utf-8')
    if jython:
        subprocess.run(['java','-cp',str(jython),'org.python.util.jython','-m','py_compile',str(target)],check=True)
        suffix='\ncases='+repr(cases)+'\nfor raw,unit,display,nanos in cases:\n    parsed=timestamp_details(raw,unit)\n    assert parsed["timestamp_str"]==display,parsed\n    assert parsed["timestamp_nanosecond"]==nanos,parsed\nprint("PASS: exact timestamp arithmetic in actual Jython")\n'
        runner=work/'precision_jython.py';runner.write_text('# coding=utf-8\n'+EMBEDDED_PARSER_BLOCK+suffix,encoding='utf-8')
        subprocess.run(['java','-cp',str(jython),'org.python.util.jython',str(runner)],check=True)
    if native_cp:
        # Emit precisely the helper code used by generated ingest plugins into a native case.
        suffix='''
from org.sleuthkit.datamodel import SleuthkitCase, BlackboardArtifact, BlackboardAttribute
from org.sleuthkit.autopsy.geolocation.datamodel import WaypointBuilder
from org.sleuthkit.autopsy.artifactfactory import ArtifactWizardCatalog
from java.util import Collections
import json
request=json.loads(%r)
db=%r
sk=SleuthkitCase.newCase(db)
try:
    tx=sk.beginTransaction()
    ds=sk.addLocalFilesDataSource("wizard-schema-test","Wizard schema test","UTC",tx)
    tx.commit()
    file=sk.addVirtualDirectory(ds.getId(),"source.csv")
    row=parse_rows(request['meta'],request['mapping'],'Generic')[0]
    artifact=_write_schema_artifact(file,sk,request['meta'],row,"Wizard schema test")
    original_id=artifact.getArtifactID()
    from java.util import ArrayList
    attrs=ArrayList()
    attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_DATETIME,'Legacy wizard',long(1792572796)))
    legacy=file.newDataArtifact(BlackboardArtifact.Type(BlackboardArtifact.ARTIFACT_TYPE.TSK_RECENT_OBJECT),attrs)
    legacy_row=timestamp_details('1792572796.12','seconds')
    legacy_row['original_row']={'time':'1792572796.12'}
    _post_wizard_artifact(legacy,sk,{},legacy_row,'Legacy wizard')
    legacy_id=legacy.getArtifactID()
    assert ArtifactWizardCatalog.types(sk).size()>7
    assert ArtifactWizardCatalog.fields(sk,request['meta']['artifact_schema']['type_name']).size()>=7
finally:
    sk.close()
sk=SleuthkitCase.openCase(db)
try:
    artifact=sk.getBlackboardArtifact(original_id)
    attrs=dict((a.getAttributeType().getTypeName(),a) for a in artifact.getAttributes())
    assert attrs['TSK_DATETIME'].getValueLong()==1792572796
    assert attrs['TSK_NAME'].getValueString()==u'M\u00fcller'
    assert attrs['WIZARD_UTC_TSK_DATETIME'].getValueString().endswith('16.123 UTC')
    assert attrs['WIZARD_RAW_TSK_DATETIME'].getValueString()=='2026-10-21T10:53:16.123+02:00'
    assert attrs['WIZARD_EXACT_TSK_DATETIME'].getValueString()=='1792572796.123'
    assert attrs['WIZARD_TEST_COUNT'].getValueLong()==9223372036854775807
    assert list(attrs['WIZARD_TEST_BLOB'].getValueBytes())==[0,-1]
    assert json.loads(attrs['WIZARD_TEST_JSON'].getValueString())=={'ok':True}
    assert len(WaypointBuilder.getAllWaypoints(sk))==1
    point=sk.getBlackboardArtifacts(BlackboardArtifact.ARTIFACT_TYPE.TSK_GPS_TRACKPOINT)[0]
    assert point.getAttribute(BlackboardAttribute.Type(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_ASSOCIATED_ARTIFACT)).getValueLong()==original_id
    legacy=sk.getBlackboardArtifact(legacy_id)
    attrs=dict((a.getAttributeType().getTypeName(),a) for a in legacy.getAttributes())
    assert attrs['WIZARD_UTC_TSK_DATETIME'].getValueString().endswith('16.12 UTC')
    assert attrs['WIZARD_EXACT_TSK_DATETIME'].getValueString()=='1792572796.12'
finally:
    sk.close()
print("PASS: native Jython typed attributes, precision, catalog, map and case reopen")
'''%(json.dumps(req,ensure_ascii=True),str(work/'native.db'))
        runner=work/'native_jython.py';runner.write_text('# coding=utf-8\n'+EMBEDDED_PARSER_BLOCK+suffix,encoding='utf-8')
        env=os.environ.copy();user=work/'user';(user/'var/log').mkdir(parents=True)
        subprocess.run(['java','-Dnetbeans.user='+str(user),'-cp',native_cp,'org.python.util.jython',str(runner)],check=True,env=env)
    print('PASS: extensible schemas, exact centi/milli/micro/nanoseconds, typed values and normalized CSV')


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--jython',type=Path);parser.add_argument('--native-cp');args=parser.parse_args()
    with tempfile.TemporaryDirectory(prefix='wizard-precision-') as td:check(Path(td),args.jython,args.native_cp)
