#!/usr/bin/env python3
"""End-to-end synthetic DLT, binary geo/time, resources and Jython compatibility."""
import argparse
import hashlib
import json
import struct
import subprocess
import sys
import tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'Tools/artifact_factory'))
import dlt
import factory
import geotime
from template_core import EMBEDDED_PARSER_BLOCK


def message(endian='<',text='Müller',counter=7,verbose=True,serial=False):
    value=text.encode('utf8')+b'\0'
    payload=(struct.pack(endian+'IH',0x8200,len(value))+value+struct.pack(endian+'Ii',0x23,-42)) if verbose else struct.pack(endian+'I',0x12345678)+b'abc'
    extra=b'ECU1'+struct.pack('>II',23,987654)+bytes([0x41 if verbose else 0x40,2 if verbose else 0])+b'NAV1'+b'GPS1'
    htyp=0x3d|(2 if endian=='>' else 0)
    standard=struct.pack('>BBH',htyp,counter,4+len(extra)+len(payload))+extra+payload
    storage=b'DLS\x01' if serial else b'DLT\x01'+struct.pack('<II',1792404000,123456)+b'STOR'
    return storage+standard


def main():
    ap=argparse.ArgumentParser();ap.add_argument('--jython',type=Path);args=ap.parse_args()
    with tempfile.TemporaryDirectory(prefix='dlt geotime ') as td:
        work=Path(td);path=work/'trace.dlt';path.write_bytes(b'bad'+message()+message('>',counter=8)+message(verbose=False)+message(serial=True)+b'DLT\x01short')
        before=hashlib.sha256(path.read_bytes()).hexdigest();stats={};rows=list(dlt.iter_dlt(str(path),stats=stats))
        assert len(rows)==4 and rows[0]['payload']=='Müller -42' and rows[1]['payload']==rows[0]['payload']
        assert rows[0]['offset']==3 and rows[0]['ecu']=='ECU1' and rows[0]['apid']=='NAV1' and rows[0]['ctid']=='GPS1'
        assert rows[0]['session_id']==23 and rows[0]['uptime_seconds']==98.7654
        assert rows[0]['timestamp_iso_utc']=='2026-10-19T10:00:00.123456Z'
        assert rows[2]['message_id']==0x12345678 and rows[3]['unix_seconds']=='' and stats['truncated']
        assert hashlib.sha256(path.read_bytes()).hexdigest()==before
        page=list(dlt.iter_dlt(str(path),limit=1,stats=stats));assert len(page)==1
        assert list(dlt.iter_dlt(str(path),start=stats['next_offset'],limit=1))[0]['counter']==8
        broken=work/'broken.dlt';broken.write_bytes(b'DLT\x01'+struct.pack('<II',0,0)+b'ECU1'+b'\x20\x00\x00\x01'+message())
        assert len(list(dlt.iter_dlt(str(broken))))==1
        v2=work/'v2.dlt';v2.write_bytes(b'DLT\x02'+b'\0'*30)
        try:list(dlt.iter_dlt(str(v2)));raise AssertionError('v2 must be explicit')
        except ValueError:pass
        req={'action':'preview','meta':{'path':str(path),'source_type':'dlt'},'plugin_type':'Event','mapping':{'Timestamp':'timestamp_iso_utc','Eventname':'apid','Kommentar':'payload'}}
        assert factory.dispatch(req)['rows'][0]['payload']=='Müller -42'
        assert factory.dispatch({**req,'action':'test'})['rows'][0]['timestamp_epoch']==1792404000
        assert factory.dispatch({**req,'action':'test','mapping':{'Timestamp':'unix_seconds','Eventname':'apid'}})['rows'][0]['timestamp_epoch']==1792404000
        template=factory.dispatch({**req,'action':'template','filename':'%.dlt','module_name':'DLT Event'})['code'];compile(template,'DLT.py','exec')
        if args.jython:
            module=work/'DLT.py';module.write_text(template,encoding='utf8')
            subprocess.run(['java','-cp',str(args.jython),'org.python.util.jython','-m','py_compile',str(module)],check=True)
            runner=work/'jython_dlt.py';runner.write_text('# coding=utf-8\n'+EMBEDDED_PARSER_BLOCK+'\n'+(ROOT/'Tools/artifact_factory/dlt.py').read_text()+"\nrows=list(iter_dlt("+'u'+ascii(str(path))+"))\nassert rows[0]['payload']==u'Müller -42'\nassert parse_rows({'path':"+'u'+ascii(str(path))+",'source_type':'dlt'}, {'Timestamp':'timestamp_iso_utc','Eventname':'apid'}, 'Event')[0]['timestamp_epoch']==1792404000\n",encoding='utf8')
            subprocess.run(['java','-cp',str(args.jython),'org.python.util.jython',str(runner)],check=True)
        # All selected preprocessing resources have independent placeholders, paths with spaces work.
        script=work/'transform input.py';script.write_text("import sys\nsys.stdout.reconfigure(encoding='utf-8')\nfrom pathlib import Path\nprint('name,phone')\nprint(Path(sys.argv[1]).read_text(encoding='utf-8')+',+49123')\n",encoding='utf8')
        config=work/'config with spaces.txt';config.write_text('Müller',encoding='utf8')
        meta={'path':str(path),'source_type':'csv','preprocess_inputs':[str(script),str(config)],'preprocess_steps':[{'command':'"'+sys.executable+'" {inputs}','shell':False,'output_suffix':'.csv'}]}
        assert factory.dispatch({'action':'preview','meta':meta})['rows'][0]['name']=='Müller'
        export=factory.dispatch({'action':'template','meta':meta,'plugin_type':'Mobile','mapping':{'Vorname':'name'},'filename':'%.dlt','module_name':'Resources','resource_directory':'parser_inputs'})['code']
        assert str(script) not in export and 'parser_inputs' in export and 'config with spaces.txt' in export
        # Explicit and auto geofenced coordinates, all data exports, swapped/endian/WKB.
        geo=work/'geo.bin';geo.write_bytes(struct.pack('<dddd',7.2,51.8,100.,10.))
        req={'path':str(geo),'mode':'geo','format':'raw_float64_le','step':16,'geofence':{'mode':'radius','longitude':7.2,'latitude':51.8,'radius_km':5},'output_directory':str(work/'geo-output')}
        result=geotime.dispatch(req);assert result['count']==1 and result['rows'][0]['offset']==0
        assert len(result['exports'])==3 and '7.2' in Path(result['exports'][0]).read_text()
        import xml.etree.ElementTree as ET
        assert float(next(n for n in ET.parse(result['exports'][1]).getroot().iter() if n.tag.endswith('wpt')).attrib['lat'])==51.8
        auto=geotime.dispatch({**req,'format':'auto','output_directory':str(work/'geo-auto')});assert auto['count']>=1
        for r in auto['rows']:assert geotime.core.haversine_km(7.2,51.8,r['lon'],r['lat'])<=5
        wkb=work/'wkb.bin';wkb.write_bytes(b'xx'+struct.pack('>BIdd',0,1,7.2,51.8))
        assert geotime.dispatch({'path':str(wkb),'mode':'geo','format':'wkb_point_scan','step':1})['rows'][0]['offset']==2
        # User time window is applied during auto selection and full scan.
        times=work/'times.bin';times.write_bytes(struct.pack('<qq',1792404000000,946684800000))
        result=geotime.dispatch({'path':str(times),'mode':'time','format':'unix_i64_ms_le','step':8,'start':'2026-01-01','end':'2027-01-01','output_directory':str(work/'time-output')})
        assert result['count']==1 and result['rows'][0]['unix_seconds']==1792404000
        result=geotime.dispatch({'path':str(times),'mode':'time','format':'auto','start':'2026-01-01','end':'2027-01-01'})
        assert result['count']>=1 and all(1767225600<=r['unix_seconds']<=1798761600 for r in result['rows'])
    print('PASS: DLT v1 LE/BE/serial, Unicode verbose arguments, IDs/raw nonverbose, corrupt/truncated recovery, paging, wizard normalization/template, preprocessing resources, geofence-aware auto, timestamp window, CSV/GPX exports'+(' and real Jython execution' if args.jython else ''))

if __name__=='__main__':main()
