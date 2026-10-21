# -*- coding: utf-8 -*-
"""Typed custom/standard Artifact Wizard schema, also embedded into Jython exports."""
import json
import math
import re
from decimal import Decimal


def validate_schema(schema):
    if not isinstance(schema, dict) or not re.match(r'^[A-Za-z][A-Za-z0-9_]{0,127}$', schema.get('type_name', '')):
        raise ValueError('Invalid artifact type name')
    if not schema.get('display_name') or not isinstance(schema.get('attributes'), list):
        raise ValueError('Artifact name and fields are required')
    names = set()
    for field in schema['attributes']:
        name = field.get('name', '')
        if not re.match(r'^[A-Za-z][A-Za-z0-9_]{0,127}$', name) or name in names:
            raise ValueError('Invalid or duplicate artifact field: ' + name)
        names.add(name)
        if field.get('value_type') not in ('STRING', 'INTEGER', 'LONG', 'DOUBLE', 'DATETIME', 'BYTE', 'JSON'):
            raise ValueError('Unsupported field type')
    return schema


def typed_cell(value, field, meta):
    if value is None or value == '': return None
    kind = field['value_type']
    text = _bb_text(value)
    if kind in ('STRING', 'JSON', 'BYTE'):
        if kind == 'JSON': json.loads(text)
        if kind == 'BYTE':
            raw = text[2:] if text.startswith('0x') else text
            if not re.match(r'^(?:[0-9a-fA-F]{2})*$', raw): raise ValueError('Binary fields require hex bytes')
        return text
    if kind == 'DATETIME':
        parsed = timestamp_details(value, meta.get('timestamp_unit', 'auto'),
                                   meta.get('timestamp_format', ''), meta.get('timestamp_precision', 'source'))
        if parsed['timestamp_epoch'] is None: raise ValueError('Invalid UTC timestamp: ' + text)
        return parsed
    if kind in ('INTEGER', 'LONG'):
        number = Decimal(text)
        if number != number.to_integral_value(): raise ValueError('Expected an integer: ' + text)
        result = int(number)
        bound = 2**31 if kind == 'INTEGER' else 2**63
        if not -bound <= result < bound: raise ValueError('Integer out of range')
        return result
    number = float(text.replace(',', '.'))
    if math.isnan(number) or math.isinf(number): raise ValueError('Nonfinite number')
    name = field['name']
    if name in ('TSK_GEO_LATITUDE', 'TSK_GEO_LATITUDE_START', 'TSK_GEO_LATITUDE_END') and abs(number) > 90:
        raise ValueError('Latitude outside -90..90')
    if name in ('TSK_GEO_LONGITUDE', 'TSK_GEO_LONGITUDE_START', 'TSK_GEO_LONGITUDE_END') and abs(number) > 180:
        raise ValueError('Longitude outside -180..180')
    return number
