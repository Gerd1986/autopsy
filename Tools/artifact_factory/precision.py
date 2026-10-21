# -*- coding: utf-8 -*-
"""Exact UTC parsing shared by preview and exported Jython modules (Python 2/3)."""
import datetime
import re
from decimal import Decimal, ROUND_FLOOR


def timestamp_details(value, unit='auto', pattern='', precision='source'):
    empty = {'timestamp_epoch': None, 'timestamp_str': None, 'timestamp_raw': None,
             'timestamp_epoch_decimal': None, 'timestamp_nanosecond': None}
    if value is None or isinstance(value, bool):
        return empty
    raw = unicode(value).strip() if 'unicode' in globals() else str(value).strip()
    if not raw:
        return empty
    result = dict(empty);result['timestamp_raw'] = raw;result['timestamp_str'] = raw
    try:
        digits = 0
        if not pattern and re.match(r'^[+-]?\d+(?:\.\d+)?$', raw):
            number = Decimal(raw)
            chosen = unit
            if chosen == 'auto':
                magnitude = abs(number)
                chosen = ('nanoseconds' if magnitude >= Decimal('100000000000000000') else
                          'microseconds' if magnitude >= Decimal('100000000000000') else
                          'milliseconds' if magnitude >= Decimal('100000000000') else 'seconds')
            scale = {'seconds': 0, 'milliseconds': 3, 'microseconds': 6, 'nanoseconds': 9}[chosen]
            number = number.scaleb(-scale)
            digits = min(9, max(0, -number.as_tuple().exponent))
        else:
            text = raw
            offset = 0
            match = re.search(r'(Z| UTC|[+-]\d{2}:?\d{2})$', text)
            if match:
                zone = match.group(1);text = text[:match.start()].strip()
                if zone not in ('Z', ' UTC'):
                    sign = -1 if zone.startswith('-') else 1
                    parts = zone[1:].replace(':', '')
                    hours, minutes = int(parts[:2]), int(parts[2:])
                    if hours > 23 or minutes > 59: return result
                    offset = sign * (hours * 3600 + minutes * 60)
            fraction = re.search(r'[.,](\d{1,9})(?=$)', text)
            tail = Decimal(0)
            if fraction and ':' in text:
                digits = len(fraction.group(1));tail = Decimal('0.' + fraction.group(1))
                if not pattern:
                    text = text[:fraction.start()]
            formats = [pattern] if pattern else [
                '%Y-%m-%dT%H:%M:%S', '%Y-%m-%d %H:%M:%S', '%Y/%m/%d %H:%M:%S',
                '%Y/%m/%d, %H:%M:%S', '%d.%m.%Y %H:%M:%S', '%Y-%m-%d', '%d.%m.%Y']
            dt = None
            for fmt in formats:
                try:
                    fmt = fmt.replace('%z','').replace('%Z','').strip() if match else fmt
                    dt = datetime.datetime.strptime(text, fmt);break
                except ValueError:
                    pass
            if dt is None: return result
            delta = dt - datetime.datetime(1970, 1, 1)
            number = Decimal(delta.days * 86400 + delta.seconds - offset)
            number += Decimal(dt.microsecond).scaleb(-6) if pattern else tail
            if pattern and '%f' in pattern and not digits: digits = 6
        whole = int(number.to_integral_value(rounding=ROUND_FLOOR))
        nanos = int((number - Decimal(whole)) * Decimal(1000000000))
        dt = datetime.datetime(1970, 1, 1) + datetime.timedelta(seconds=whole)
        if precision != 'source': digits = max(digits, int(precision))
        fractional = ('.' + ('%09d' % nanos)[:digits]) if digits else ''
        result.update(timestamp_epoch=whole, timestamp_nanosecond=nanos,
                      timestamp_epoch_decimal=format(number, 'f'),
                      timestamp_str=dt.strftime('%Y-%m-%d %H:%M:%S') + fractional + ' UTC')
    except (ValueError, ArithmeticError, OverflowError, KeyError):
        pass
    return result


def _timestamp_to_epoch_and_str(value):
    parsed = timestamp_details(value)
    return parsed['timestamp_epoch'], parsed['timestamp_str']
