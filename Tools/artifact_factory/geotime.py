#!/usr/bin/env python3
"""Headless JSON bridge for Gerd1986/binary_timestamp_geo_parser, stdlib only."""
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
import json
import mmap
import math
from datetime import datetime
import geotime_core as core


def geo_formats():
    values=['wkb_point_scan']
    for base in ('raw_float64','raw_float32','raw_semicircles_i32',*[f'raw_int32_scaled_1e{n}' for n in (7,6,5,4,3)],'raw_int16_scaled_180'):
        for endian in ('le','be'):
            values.extend([base+'_'+endian,base+'_'+endian+'_swapped(lat,lon)'])
    return values


def parse_geo(data,fmt,step,maximum):
    swapped='swapped' in fmt;endian='<' if '_le' in fmt else '>'
    if fmt=='wkb_point_scan':return core.parse_wkb_points(data,maximum,step)
    if fmt.startswith('raw_float64'):return core.parse_float_pairs(data,endian,'d',maximum,step,swapped)
    if fmt.startswith('raw_float32'):return core.parse_float_pairs(data,endian,'f',maximum,step,swapped)
    if fmt.startswith('raw_semicircles'):return core.parse_semicircles_i32(data,endian,maximum,step,swapped)
    if fmt.startswith('raw_int32_scaled'):return core.parse_int32_scaled(data,endian,float(fmt.split('_')[3]),maximum,step,swapped)
    if fmt.startswith('raw_int16'):return core.parse_int16_scaled_180(data,endian,maximum,step,swapped)
    raise ValueError('Unknown geo format')


def fence(spec):
    mode=spec.get('mode','off')
    if mode=='off':return None
    if mode=='radius':
        lon,lat,radius=[float(spec[k]) for k in ('longitude','latitude','radius_km')]
        if not all(map(math.isfinite,(lon,lat,radius))) or not -180<=lon<=180 or not -90<=lat<=90 or radius<=0:raise ValueError('Invalid radius geofence')
        return lambda x,y:core.haversine_km(lon,lat,x,y)<=radius
    if mode=='rectangle':
        a,b,c,d=[float(spec[k]) for k in ('min_lon','max_lon','min_lat','max_lat')]
        if not all(map(math.isfinite,(a,b,c,d))) or not -180<=a<=b<=180 or not -90<=c<=d<=90:raise ValueError('Invalid rectangular geofence')
        return lambda x,y:a<=x<=b and c<=y<=d
    raise ValueError('Unknown geofence mode')


def dispatch(req):
    if req.get('action')=='formats':return {'geo':['auto']+geo_formats(),'time':['auto']+[s.fmt for s in core.build_time_specs()]}
    path=Path(req['path']);mode=req.get('mode','geo');fmt=req.get('format','auto')
    if mode not in ('geo','time'):raise ValueError('Unknown carving mode')
    step=int(req.get('step',1));maximum=int(req.get('maximum',50000))
    if not 1<=step<=65536 or not 1<=maximum<=2000000:raise ValueError('Invalid stride or maximum hits')
    if not path.is_file() or path.stat().st_size==0:raise ValueError('Select a non-empty binary file')
    candidates=[]
    with path.open('rb') as f,mmap.mmap(f.fileno(),0,access=mmap.ACCESS_READ) as data:
        if mode=='geo':
            core.GEO_FILTER=fence(req.get('geofence',{}))
            if fmt=='auto':
                sample=data[:core.AUTO_MAX_SCAN_BYTES]
                for stride in core.AUTO_STEPS:
                    for candidate in geo_formats():
                        points,bad,attempts=parse_geo(sample,candidate,stride,maximum)
                        candidates.append({'format':candidate,'step':stride,'count':len(points),'score':core.score_candidate(len(points),bad,attempts)})
                candidates.sort(key=lambda c:c['score'],reverse=True)
                fmt=candidates[0]['format'];step=candidates[0]['step']
            points,bad,attempts=parse_geo(data,fmt,step,maximum)
            records=points
        else:
            start=datetime.fromisoformat(req.get('start','2010-01-01').replace('Z','+00:00'))
            end=datetime.fromisoformat(req.get('end','2035-01-01').replace('Z','+00:00'))
            start=start.replace(tzinfo=core.UTC) if start.tzinfo is None else start.astimezone(core.UTC)
            end=end.replace(tzinfo=core.UTC) if end.tzinfo is None else end.astimezone(core.UTC)
            if end<start:raise ValueError('End date must be after start date')
            window=core.TimeWindow(start,end)
            if fmt=='auto':
                result,scan=core.autodetect_timestamps(data,window,maximum)
                candidates=[{'format':r.fmt,'step':r.step,'count':len(r.items),'score':r.score} for r in scan[:10]]
                if result is None:return {'rows':[],'count':0,'candidates':candidates,'format':'auto','step':step,'notice':'No plausible timestamp in the selected UTC window'}
            else:result=core.parse_timestamps_by_format(data,fmt,step,window,maximum)
            fmt=result.fmt;step=result.step;bad=result.bad;attempts=result.attempts
            records=[{'offset':i.offset,'timestamp_iso_utc':core.dt_to_iso_millis(i.dt),'unix_seconds':i.unix_seconds} for i in result.items]
    exports=[]
    if req.get('output_directory'):
        # A new result directory prevents overwriting evidence or older exports.
        out=Path(req['output_directory']);out.mkdir(parents=True,exist_ok=False)
        csv=out/(mode+'.csv')
        if mode=='geo':
            core.write_geo_csv(str(csv),records)
            for track,name in ((False,'waypoints.gpx'),(True,'track.gpx')):
                core.write_gpx(str(out/name),records,path.name,track);exports.append(str(out/name))
        else:core.write_timestamps_csv(str(csv),result.items)
        exports.insert(0,str(csv))
        (out/'settings.json').write_text(json.dumps({'source':str(path),'request':req,'format':fmt,'step':step,'count':len(records),'candidates':candidates[:10]},indent=2),encoding='utf-8')
    return {'rows':records[:20000],'count':len(records),'displayed':min(len(records),20000),'limit_reached':len(records)>=maximum,'format':fmt,'step':step,'bad':bad,'attempts':attempts,'candidates':candidates[:10],'exports':exports,'notice':'Heuristic carving: verify offsets and formats against source bytes. Geo and timestamps are independent detections; GPS has no leap-second correction.'}

if __name__=='__main__':
    try:print(json.dumps({'ok':True,**dispatch(json.load(sys.stdin))},ensure_ascii=True,allow_nan=False))
    except Exception as exc:
        print(json.dumps({'ok':False,'error':str(exc)},ensure_ascii=True));sys.exit(2)
