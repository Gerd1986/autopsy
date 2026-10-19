# coding=utf-8
"""DLT v1 storage/serial reader, stdlib and Jython 2.7 compatible.
Wire layout: COVESA dlt-daemon include/dlt/dlt_common.h.
Non-verbose messages retain their message ID and raw payload (no guessed FIBEX).
"""
import struct
import binascii
from datetime import datetime as _DltDatetime
DLT_COLUMNS = ['offset','timestamp_iso_utc','unix_seconds','ecu','apid','ctid','session_id','uptime_seconds','counter','message_type','level','verbose','message_id','payload','payload_hex','decode_status']


def _hex(value):
    return binascii.hexlify(value).decode('ascii')


def _id(value):
    return value.rstrip(b'\0').decode('ascii','replace')


def _typed(payload, endian, count):
    pos = [0]
    def take(n):
        if n < 0 or pos[0]+n > len(payload): raise ValueError('truncated argument')
        value=payload[pos[0]:pos[0]+n];pos[0]+=n;return value
    def number(fmt): return struct.unpack(endian+fmt,take(struct.calcsize(fmt)))[0]
    values=[]
    for unused in range(count):
        info=number('I');length=info&15
        if info & (0x100|0x1000|0x2000|0x4000): raise ValueError('array/fixed-point/struct/trace argument retained as hex')
        variable=bool(info&0x800)
        name=''
        if info&0x200: # string length precedes optional variable name
            if info&0x38000 not in (0,0x8000): raise ValueError('unsupported string encoding')
            size=number('H')
            if variable:
                name=take(number('H')).rstrip(b'\0').decode('utf-8','replace')
            value=take(size).rstrip(b'\0').decode('utf-8' if info&0x8000 else 'ascii','replace')
        elif info&0x400: # raw bytes
            size=number('H')
            if variable: name=take(number('H')).rstrip(b'\0').decode('utf-8','replace')
            value='0x'+_hex(take(size))
        else:
            if variable:
                namesize=number('H');unitsize=0 if info&0x10 else number('H')
                name=take(namesize).rstrip(b'\0').decode('utf-8','replace');take(unitsize)
            if info&0x10: value=bool(number('B'))
            elif info&0x80:
                if length not in (3,4): raise ValueError('unsupported float width')
                value=number({3:'f',4:'d'}[length])
            elif info&(0x20|0x40):
                formats={1:'b',2:'h',3:'i',4:'q'} if info&0x20 else {1:'B',2:'H',3:'I',4:'Q'}
                if length not in formats: raise ValueError('unsupported integer width')
                value=number(formats[length])
            else: raise ValueError('unknown argument type')
            value=str(value)
        values.append((name+'=' if name else '')+value)
    return ' '.join(values),pos[0]


def _seek_magic(f, start):
    f.seek(start)
    overlap=b''
    while True:
        chunk=f.read(65536)
        if not chunk: return None
        data=overlap+chunk;base=f.tell()-len(data)
        matches=[data.find(m) for m in (b'DLT\x01',b'DLT\x02',b'DLS\x01')]
        matches=[i for i in matches if i>=0]
        if matches: return base+min(matches)
        overlap=data[-3:]


def iter_dlt(path, start=0, limit=None, stats=None):
    stats = stats if stats is not None else {}
    stats.update({'next_offset':start,'skipped_bytes':0,'truncated':False})
    count=0
    with open(path,'rb') as f:
        offset=start
        while limit is None or count<limit:
            found=_seek_magic(f,offset)
            if found is None:
                f.seek(0,2);stats['next_offset']=f.tell();break
            stats['skipped_bytes']+=found-offset
            f.seek(found);magic=f.read(4)
            if magic==b'DLT\x02': raise ValueError('DLT v2 is not supported; convert to DLT v1 before decoding')
            stored=magic==b'DLT\x01';storage=f.read(12) if stored else b''
            standard=f.read(4)
            if len(storage)!=(12 if stored else 0) or len(standard)!=4:
                stats['truncated']=True;break
            htyp,counter,size=struct.unpack('>BBH',standard)
            minimum=4+(4 if htyp&4 else 0)+(4 if htyp&8 else 0)+(4 if htyp&16 else 0)+(10 if htyp&1 else 0)
            if htyp>>5 != 1 or size<minimum:
                offset=found+1;continue
            body=f.read(size-4)
            if len(body)!=size-4:
                stats['truncated']=True;offset=found+1;continue
            next_offset=f.tell();p=0;endian='>' if htyp&2 else '<'
            row=dict((key,'') for key in DLT_COLUMNS);row.update(offset=found,counter=counter)
            if stored:
                seconds,micro=struct.unpack('<II',storage[:8])
                if micro>=1000000: offset=found+1;continue
                row['unix_seconds']=seconds+micro/1000000.0
                row['timestamp_iso_utc']=_DltDatetime.utcfromtimestamp(seconds).strftime('%Y-%m-%dT%H:%M:%S')+'.%06dZ'%micro
                row['ecu']=_id(storage[8:12])
            if htyp&4: row['ecu']=_id(body[p:p+4]);p+=4
            if htyp&8: row['session_id']=struct.unpack('>I',body[p:p+4])[0];p+=4
            if htyp&16: row['uptime_seconds']=struct.unpack('>I',body[p:p+4])[0]/10000.0;p+=4
            verbose=False;nargs=0
            if htyp&1:
                msin,nargs=struct.unpack('BB',body[p:p+2]);row['apid']=_id(body[p+2:p+6]);row['ctid']=_id(body[p+6:p+10]);p+=10
                verbose=bool(msin&1);mstp=(msin>>1)&7;mtin=msin>>4
                row['message_type']={0:'log',1:'app_trace',2:'network_trace',3:'control'}.get(mstp,str(mstp))
                row['level']=({1:'fatal',2:'error',3:'warn',4:'info',5:'debug',6:'verbose'}.get(mtin,str(mtin)) if mstp==0 else str(mtin))
            payload=body[p:];row['verbose']=verbose;row['payload_hex']=_hex(payload)
            if verbose:
                try:
                    row['payload'],used=_typed(payload,endian,nargs)
                    row['decode_status']='decoded' if used==len(payload) else 'decoded; trailing bytes retained in hex'
                except (ValueError,struct.error) as exc:
                    row['payload']='0x'+_hex(payload);row['decode_status']=str(exc)
            else:
                if len(payload)>=4: row['message_id']=struct.unpack(endian+'I',payload[:4])[0]
                row['payload']='0x'+_hex(payload);row['decode_status']='non-verbose: dictionary required'
            count+=1;offset=next_offset;stats['next_offset']=offset
            yield row
