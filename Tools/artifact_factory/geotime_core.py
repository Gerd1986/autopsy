"""
Binary Geo + Timestamp Detector (GUI, 2 Tabs)

Tab 1: Geo
- erkennt/liest roh Lon/Lat-Paare (float32/64, semicircles, fixed-point int32 scales, int16 scaled, WKB)
- Step/Stride (Bytes) für alle Formate (misaligned via step=1)
- Geofence: Radius oder Rectangle (wirkt auf Vorschau + Export)
- Export: CSV (offset,lon,lat) + GPX (WPT/Track) mit offset in <desc>

Tab 2: Timestamps (Variante A)
- erkennt/liest roh Zeitstempel (unix i32, unix i64 s/ms/us/ns, unix float64, filetime, gps week+towms)
- AUTO: probiert Formate x Endianness x Steps, wählt bestes
- Plausibler Zeitraum (Start/Ende in UTC) als Filter + Scoring
- Vorschau: Tabelle (offset + ISO UTC + unix_seconds)
- Export: CSV (offset,timestamp_iso_utc,unix_seconds)

Adapted for Autopsy without Tk/matplotlib from Gerd1986/binary_timestamp_geo_parser
commit 5ce4c9d41df22bb764ac068f8d38f5b7cf12ab8f. GUI lives in the Java module.
"""

from __future__ import annotations

import os
import csv
import math
import struct
from dataclasses import dataclass
from datetime import datetime, timezone, timedelta
from typing import Callable, List, Optional, Tuple, Iterable, Dict



# =========================
# Shared constants
# =========================

GEO_FILTER = None
UTC = timezone.utc
EARTH_RADIUS_KM = 6371.0088

# Geo
SEMICIRCLES_TO_DEG = 180.0 / (2**31)
FIXEDPOINT_SCALES = [1e7, 1e6, 1e5, 1e4, 1e3]

# Auto stepping
AUTO_STEPS = [1, 2, 4, 8, 16, 32, 64]
AUTO_MAX_SCAN_BYTES = 5_000_000


# =========================
# Shared helpers
# =========================

def is_plausible_lon_lat(lon: float, lat: float) -> bool:
    return math.isfinite(lon) and math.isfinite(lat) and (-180.0 <= lon <= 180.0) and (-90.0 <= lat <= 90.0) and (GEO_FILTER is None or GEO_FILTER(lon, lat))

def score_candidate(points_count: int, bad: int, attempts: int) -> float:
    if attempts <= 0:
        return 0.0
    ratio = points_count / max(1, attempts)
    penalty = 1.0 / (1.0 + (bad / max(1, attempts)) * 2.0)
    return (points_count ** 0.5) * ratio * penalty

def haversine_km(lon1, lat1, lon2, lat2):
    phi1 = math.radians(lat1)
    phi2 = math.radians(lat2)
    dphi = math.radians(lat2 - lat1)
    dlmb = math.radians(lon2 - lon1)
    a = math.sin(dphi / 2) ** 2 + math.cos(phi1) * math.cos(phi2) * math.sin(dlmb / 2) ** 2
    a = max(0.0, min(1.0, a))
    c = 2 * math.atan2(math.sqrt(a), math.sqrt(1 - a))
    return EARTH_RADIUS_KM * c


# =========================
# GEO PARSERS
# =========================

def parse_pairs_at_step(data: bytes, st: struct.Struct, decode_pair, max_points: int, step: int):
    step = max(1, int(step))
    rec_size = st.size
    limit = len(data) - rec_size
    if limit < 0:
        return [], 0, 0

    pts = []
    bad = 0
    attempts = 0

    for off in range(0, limit + 1, step):
        attempts += 1
        try:
            raw = st.unpack_from(data, off)
        except struct.error:
            bad += 1
            continue

        lon, lat = decode_pair(raw)
        if is_plausible_lon_lat(lon, lat):
            pts.append({"lon": float(lon), "lat": float(lat), "offset": off})
            if len(pts) >= max_points:
                break
        else:
            bad += 1

    return pts, bad, attempts

def parse_float_pairs(data: bytes, endian: str, float_type: str, max_points: int, step: int, swapped: bool):
    st = struct.Struct(endian + float_type + float_type)
    def decode(t):
        a, b = t
        lon, lat = (b, a) if swapped else (a, b)
        return float(lon), float(lat)
    return parse_pairs_at_step(data, st, decode, max_points=max_points, step=step)

def parse_semicircles_i32(data: bytes, endian: str, max_points: int, step: int, swapped: bool):
    st = struct.Struct(endian + "ii")
    def decode(t):
        a, b = t
        lon_sc, lat_sc = (b, a) if swapped else (a, b)
        return lon_sc * SEMICIRCLES_TO_DEG, lat_sc * SEMICIRCLES_TO_DEG
    return parse_pairs_at_step(data, st, decode, max_points=max_points, step=step)

def parse_int32_scaled(data: bytes, endian: str, scale: float, max_points: int, step: int, swapped: bool):
    st = struct.Struct(endian + "ii")
    inv = 1.0 / float(scale)
    def decode(t):
        a, b = t
        lon_i, lat_i = (b, a) if swapped else (a, b)
        return lon_i * inv, lat_i * inv
    return parse_pairs_at_step(data, st, decode, max_points=max_points, step=step)

def parse_int16_scaled_180(data: bytes, endian: str, max_points: int, step: int, swapped: bool):
    st = struct.Struct(endian + "hh")
    mul = 180.0 / 32768.0
    def decode(t):
        a, b = t
        lon_i, lat_i = (b, a) if swapped else (a, b)
        return lon_i * mul, lat_i * mul
    return parse_pairs_at_step(data, st, decode, max_points=max_points, step=step)

def parse_wkb_points(data: bytes, max_points: int, step: int):
    pts = []
    step = max(1, int(step))
    limit = len(data)

    i = 0
    attempts = 0
    while i + 21 <= limit and len(pts) < max_points:
        attempts += 1
        bo = data[i]
        if bo in (0, 1):
            endian = ">" if bo == 0 else "<"
            try:
                geom_type = struct.unpack_from(endian + "I", data, i + 1)[0]
            except struct.error:
                i += step
                continue

            if geom_type == 1:
                try:
                    x, y = struct.unpack_from(endian + "dd", data, i + 5)
                except struct.error:
                    i += step
                    continue

                if is_plausible_lon_lat(x, y):
                    pts.append({"lon": float(x), "lat": float(y), "offset": i})
                    i += 21
                    continue
        i += step

    return pts, 0, attempts


# =========================
# GEO EXPORT
# =========================

def write_geo_csv(path: str, points):
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["offset", "longitude", "latitude"])
        for p in points:
            w.writerow([p["offset"], f"{p['lon']:.10f}", f"{p['lat']:.10f}"])

def write_gpx(path: str, points, name: str, as_track: bool):
    from xml.sax.saxutils import escape
    now = datetime.now(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")
    name_xml = escape(name)
    header = (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        '<gpx version="1.1" creator="BinaryGeoTimestampDetector" '
        'xmlns="http://www.topografix.com/GPX/1/1" '
        'xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" '
        'xsi:schemaLocation="http://www.topografix.com/GPX/1/1 '
        'http://www.topografix.com/GPX/1/1/gpx.xsd">\n'
        f"  <metadata><name>{name_xml}</name><time>{now}</time></metadata>\n"
    )
    with open(path, "w", encoding="utf-8") as f:
        f.write(header)
        if not as_track:
            for idx, p in enumerate(points, start=1):
                f.write(
                    f'  <wpt lat="{p["lat"]:.10f}" lon="{p["lon"]:.10f}">'
                    f"<name>pt{idx}</name><desc>offset={p['offset']}</desc></wpt>\n"
                )
        else:
            f.write(f"  <trk><name>{name_xml}</name><trkseg>\n")
            for p in points:
                f.write(
                    f'    <trkpt lat="{p["lat"]:.10f}" lon="{p["lon"]:.10f}">'
                    f"<desc>offset={p['offset']}</desc></trkpt>\n"
                )
            f.write("  </trkseg></trk>\n")
        f.write("</gpx>\n")


# =========================
# TIMESTAMP PARSER + AUTO
# =========================

UNIX_EPOCH = datetime(1970, 1, 1, tzinfo=UTC)
FILETIME_EPOCH = datetime(1601, 1, 1, tzinfo=UTC)
GPS_EPOCH = datetime(1980, 1, 6, tzinfo=UTC)

@dataclass(frozen=True)
class TimeWindow:
    start: datetime  # inclusive UTC
    end: datetime    # inclusive UTC
    def contains(self, dt: datetime) -> bool:
        dt = dt.astimezone(UTC)
        return self.start <= dt <= self.end

@dataclass(frozen=True)
class TimestampItem:
    offset: int
    dt: datetime
    unix_seconds: float

@dataclass(frozen=True)
class CandidateSpec:
    fmt: str
    endian: str     # "<" or ">"
    st: struct.Struct
    decode: Callable[[Tuple], Optional[datetime]]
    notes: str

@dataclass(frozen=True)
class CandidateResult:
    score: float
    fmt: str
    step: int
    endian: str
    items: List[TimestampItem]
    bad: int
    attempts: int
    notes: str

def _safe_add_seconds(epoch: datetime, seconds: float) -> Optional[datetime]:
    try:
        return epoch + timedelta(seconds=seconds)
    except (OverflowError, ValueError):
        return None

def dt_to_iso_millis(dt: datetime) -> str:
    dt = dt.astimezone(UTC)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.") + f"{int(dt.microsecond/1000):03d}Z"

def parse_datetimes_at_step(data: bytes, st: struct.Struct, decode_dt, window: TimeWindow, max_items: int, step: int):
    step = max(1, int(step))
    rec_size = st.size
    limit = len(data) - rec_size
    if limit < 0:
        return [], 0, 0

    out: List[TimestampItem] = []
    bad = 0
    attempts = 0

    for off in range(0, limit + 1, step):
        attempts += 1
        try:
            raw = st.unpack_from(data, off)
        except struct.error:
            bad += 1
            continue

        dt = decode_dt(raw)
        if dt is None:
            bad += 1
            continue
        dt = dt.astimezone(UTC)
        if not window.contains(dt):
            bad += 1
            continue

        unix = (dt - UNIX_EPOCH).total_seconds()
        out.append(TimestampItem(offset=off, dt=dt, unix_seconds=unix))
        if len(out) >= max_items:
            break

    return out, bad, attempts

def build_time_specs() -> List[CandidateSpec]:
    specs: List[CandidateSpec] = []

    def dec_unix_i32(raw):
        (v,) = raw
        return _safe_add_seconds(UNIX_EPOCH, float(int(v)))

    def make_dec_unix_i64(unit: str):
        mul = {"s": 1.0, "ms": 1e-3, "us": 1e-6, "ns": 1e-9}[unit]
        def dec(raw):
            (v,) = raw
            return _safe_add_seconds(UNIX_EPOCH, float(v) * mul)
        return dec

    def dec_unix_f64(raw):
        (v,) = raw
        if not math.isfinite(v) or abs(v) > 1e12:
            return None
        return _safe_add_seconds(UNIX_EPOCH, float(v))

    def dec_filetime(raw):
        (v,) = raw
        return _safe_add_seconds(FILETIME_EPOCH, float(v) / 10_000_000.0)

    def dec_gps_week_towms(raw):
        week, tow_ms = raw
        if week > 10000 or tow_ms > 7 * 24 * 3600 * 1000:
            return None
        return _safe_add_seconds(GPS_EPOCH, week * 7 * 24 * 3600 + (tow_ms / 1000.0))

    for endian, tag in [("<", "le"), (">", "be")]:
        specs.append(CandidateSpec(
            fmt=f"unix_i32_s_{tag}",
            endian=endian,
            st=struct.Struct(endian + "i"),
            decode=dec_unix_i32,
            notes="int32 seconds since 1970-01-01",
        ))
        for unit in ["s", "ms", "us", "ns"]:
            specs.append(CandidateSpec(
                fmt=f"unix_i64_{unit}_{tag}",
                endian=endian,
                st=struct.Struct(endian + "q"),
                decode=make_dec_unix_i64(unit),
                notes=f"int64 {unit} since unix epoch",
            ))
        specs.append(CandidateSpec(
            fmt=f"unix_f64_s_{tag}",
            endian=endian,
            st=struct.Struct(endian + "d"),
            decode=dec_unix_f64,
            notes="float64 seconds since unix epoch",
        ))
        specs.append(CandidateSpec(
            fmt=f"filetime_u64_{tag}",
            endian=endian,
            st=struct.Struct(endian + "Q"),
            decode=dec_filetime,
            notes="uint64 100ns ticks since 1601-01-01",
        ))
        specs.append(CandidateSpec(
            fmt=f"gps_week_towms_{tag}",
            endian=endian,
            st=struct.Struct(endian + "HI"),
            decode=dec_gps_week_towms,
            notes="uint16 GPS week + uint32 tow_ms (no leap-sec correction)",
        ))

    return specs

def autodetect_timestamps(
    data: bytes,
    window: TimeWindow,
    max_items: int,
    steps: Iterable[int] = AUTO_STEPS,
    scan_max_bytes: int = AUTO_MAX_SCAN_BYTES,
) -> Tuple[Optional[CandidateResult], List[CandidateResult]]:
    data_auto = data[:scan_max_bytes] if len(data) > scan_max_bytes else data
    specs = build_time_specs()
    results: List[CandidateResult] = []

    for stp in steps:
        for spec in specs:
            items, bad, attempts = parse_datetimes_at_step(
                data=data_auto, st=spec.st, decode_dt=spec.decode,
                window=window, max_items=max_items, step=stp
            )
            sc = score_candidate(len(items), bad, attempts)
            results.append(CandidateResult(
                score=sc, fmt=spec.fmt, step=int(stp), endian=spec.endian,
                items=items, bad=bad, attempts=attempts, notes=spec.notes
            ))

    results.sort(key=lambda r: r.score, reverse=True)
    if not results or results[0].score <= 0:
        return None, results

    best_scan = results[0]
    spec_map: Dict[str, CandidateSpec] = {s.fmt: s for s in build_time_specs()}
    spec = spec_map.get(best_scan.fmt)
    if spec is None:
        return None, results

    full_items, full_bad, full_attempts = parse_datetimes_at_step(
        data=data, st=spec.st, decode_dt=spec.decode,
        window=window, max_items=max_items, step=best_scan.step
    )
    best_full = CandidateResult(
        score=score_candidate(len(full_items), full_bad, full_attempts),
        fmt=best_scan.fmt, step=best_scan.step, endian=best_scan.endian,
        items=full_items, bad=full_bad, attempts=full_attempts, notes=best_scan.notes
    )
    return best_full, results

def parse_timestamps_by_format(data: bytes, fmt: str, step: int, window: TimeWindow, max_items: int) -> CandidateResult:
    spec = next((s for s in build_time_specs() if s.fmt == fmt), None)
    if spec is None:
        raise ValueError(f"Unknown timestamp fmt: {fmt}")
    items, bad, attempts = parse_datetimes_at_step(data, spec.st, spec.decode, window, max_items, step)
    sc = score_candidate(len(items), bad, attempts)
    return CandidateResult(sc, fmt, int(step), spec.endian, items, bad, attempts, spec.notes)

def write_timestamps_csv(path: str, items: List[TimestampItem]) -> None:
    with open(path, "w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["offset", "timestamp_iso_utc", "unix_seconds"])
        for it in items:
            w.writerow([it.offset, dt_to_iso_millis(it.dt), f"{it.unix_seconds:.3f}"])


# =========================
# GUI: Tabs
# =========================
