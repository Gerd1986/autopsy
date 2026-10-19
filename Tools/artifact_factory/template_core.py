# -*- coding: utf-8 -*-
"""Parser/template core adapted from Gerd1986/autopsy-artifact-factory/wizard.py.
Source blob: 349f5d930d153767546d01c63d704b4e28aad367.
The desktop UI is implemented natively by Autopsy; no pandas/Tk dependency.
"""
import os

EMBEDDED_PARSER_BLOCK = r'''
# ===============================
# Embedded Parser (no pandas)
# Supports: CSV, Regex/Text
# Geo: emits timestamp_epoch (int UTC) + timestamp_str
# Extras: preprocessing, sqlite sidecars, duplicate-safe temp names
# ===============================

import csv
import re
import datetime
import os
import shutil
import tempfile
import subprocess
import shlex
import uuid


def _bb_text(value):
    """
    Preserve Unicode for Autopsy/Jython Blackboard text values and remove
    U+200E LEFT-TO-RIGHT MARK. No ASCII conversion is performed.
    """
    if value is None:
        return u""
    try:
        if isinstance(value, unicode):
            text = value
        else:
            text = unicode(value)
    except Exception:
        try:
            text = unicode(str(value), "utf-8")
        except Exception:
            text = unicode(repr(value), "utf-8", "replace")
    return text.replace(unichr(0x200E), u"")

def _timestamp_to_epoch_and_str(val):
    if val is None:
        return (None, None)
    s = str(val).strip()
    if not s:
        return (None, None)
    if re.match(r"^[+-]?\d+(?:\.\d+)?$", s):
        try:
            num = float(s) if "." in s else int(s)
            if num > 1000000000000:
                num //= 1000
            dt = datetime.datetime.utcfromtimestamp(num)
            return (int(num), dt.strftime("%Y-%m-%d %H:%M:%S"))
        except Exception:
            return (None, s)
    fmts = [
        "%Y-%m-%d %H:%M:%S",
        "%Y/%m/%d %H:%M:%S",
        "%Y/%m/%d, %H:%M:%S",
        "%Y/%m/%d, %H:%M:%S.%f",
        "%Y-%m-%dT%H:%M:%S",
        "%Y-%m-%dT%H:%M:%S.%fZ",
        "%Y-%m-%dT%H:%M:%SZ",
        "%Y-%m-%d",
        "%d.%m.%Y %H:%M:%S",
        "%d.%m.%Y",
    ]
    for fmt in fmts:
        try:
            dt = datetime.datetime.strptime(s, fmt)
            epoch = int((dt - datetime.datetime(1970, 1, 1)).total_seconds())
            return (epoch, dt.strftime("%Y-%m-%d %H:%M:%S"))
        except Exception:
            continue
    return (None, s)

def _normalize_float(val, mn=None, mx=None):
    if val is None:
        return None
    try:
        import math
        f = float(str(val).replace(",", "."))
        if math.isnan(f) or math.isinf(f): return None
        if mn is not None and f < mn:
            return None
        if mx is not None and f > mx:
            return None
        return f
    except Exception:
        return None

def _normalize_phone(val):
    if val is None:
        return None
    s = str(val).strip()
    if not s:
        return None
    plus = s.startswith("+")
    digits = re.sub(r"\D", "", s)
    if not digits:
        return None
    return ("+" + digits) if plus else digits

def _normalize_mac(val):
    if val is None:
        return None
    s = re.sub(r"[^0-9A-Fa-f]", "", str(val)).upper()
    if len(s) == 12:
        return ":".join([s[i:i+2] for i in range(0, 12, 2)])
    return s

def _normalize_duration(val):
    if val is None:
        return None
    s = str(val).strip()
    if not s:
        return None
    if re.match(r"^\d+$", s):
        return int(s)
    parts = s.split(":")
    try:
        nums = [int(p) for p in parts]
        if len(nums) == 2:
            return nums[0] * 60 + nums[1]
        if len(nums) == 3:
            return nums[0] * 3600 + nums[1] * 60 + nums[2]
    except Exception:
        pass
    return None

def _unique_temp_name(original_name, prefix="tmp_"):
    stem, ext = os.path.splitext(original_name)
    return prefix + stem + "_" + uuid.uuid4().hex[:12] + ext

def _copy_sqlite_with_sidecars(db_path, dst_dir):
    if not os.path.isdir(dst_dir):
        os.makedirs(dst_dir)
    dst_db = os.path.join(dst_dir, _unique_temp_name(os.path.basename(db_path), "sqlite_"))
    shutil.copy2(db_path, dst_db)
    for suffix in ["-wal", "-shm"]:
        src = db_path + suffix
        if os.path.exists(src):
            shutil.copy2(src, dst_db + suffix)
    return dst_db

def _preprocess_steps_from_meta(meta):
    steps = []
    raw_steps = meta.get("preprocess_steps")
    if raw_steps:
        for idx, step in enumerate(raw_steps):
            if not isinstance(step, dict):
                continue
            cmd = str(step.get("command") or "").strip()
            if not cmd:
                continue
            steps.append({
                "name": str(step.get("name") or ("step_%d" % (idx + 1))).strip(),
                "command": cmd,
                "output_suffix": str(step.get("output_suffix") or "").strip() or ".txt",
                "shell": bool(step.get("shell", False)),
            })
    elif meta.get("preprocess_enabled"):
        cmd = str(meta.get("preprocess_command") or "").strip()
        if cmd:
            steps.append({
                "name": "step_1",
                "command": cmd,
                "output_suffix": str(meta.get("preprocess_output_suffix") or "").strip() or ".txt",
                "shell": False,
            })
    return steps

def _run_preprocess_step(step, input_path, step_index, script_dir, inputs):
    out_suffix = step.get("output_suffix") or os.path.splitext(input_path)[1] or ".txt"
    tmp_dir = tempfile.mkdtemp(prefix="autopsy_pre_")
    out_path = os.path.join(tmp_dir, "preprocessed_step_%d" % (step_index + 1) + out_suffix)
    cmd_template = step["command"]
    if script_dir:
        cmd_template = cmd_template.replace("{script_dir}", str(script_dir).replace("\\", "/"))
    joined = subprocess.list2cmdline(inputs) if os.name == "nt" else " ".join("'" + v.replace("'", "'\\''") + "'" for v in inputs)
    cmd = cmd_template.replace("{inputs}", joined)
    for number, value in enumerate(inputs, 1): cmd = cmd.replace("{input%d}" % number, value)
    cmd = cmd.replace("{input}", input_path).replace("{output}", out_path)
    if step.get("shell", False):
        pipe = subprocess.Popen(cmd, shell=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    else:
        pipe = subprocess.Popen(cmd if os.name == "nt" else shlex.split(cmd), shell=False, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    stdout_data, stderr_data = pipe.communicate()
    if pipe.returncode != 0:
        if stderr_data is None:
            stderr_text = ""
        else:
            try:
                stderr_text = stderr_data.decode("utf-8", "replace")
            except Exception:
                stderr_text = str(stderr_data)
        raise Exception("Preprocessing fehlgeschlagen: " + stderr_text)
    if not os.path.exists(out_path):
        f = open(out_path, "wb")
        try:
            if stdout_data:
                f.write(stdout_data)
        finally:
            f.close()
    return out_path

def _run_preprocess_if_needed(meta):
    steps = _preprocess_steps_from_meta(meta)
    if not steps:
        return dict(meta)
    new_meta = dict(meta)
    current_path = meta["path"]
    script_dir = meta.get("script_dir")
    for idx, step in enumerate(steps):
        current_path = _run_preprocess_step(step, current_path, idx, script_dir, meta.get("preprocess_inputs", []))
    new_meta["path"] = current_path
    return new_meta

def _iter_rows_from_csv_file(path, sep):
    if sep == "\\t":
        sep = "\t"
    with open(path, "rb") as f:
        reader = csv.reader(f, delimiter=str(sep))
        headers = [value.decode("utf-8-sig", "replace") for value in next(reader)]
        for values in reader:
            yield dict(zip(headers, [value.decode("utf-8", "replace") for value in values]))


def _iter_rows_from_regex_file(path, pattern):
    import io
    pat = re.compile(pattern)
    with io.open(path, "r", encoding="utf-8-sig", errors="replace") as f:
        text = f.read()
    for match in pat.finditer(text):
        yield match.groupdict()


def parse_rows(meta, mapping, plugin_type):
    st = meta.get("source_type")
    meta = _run_preprocess_if_needed(meta)
    if st == "csv":
        src_iter = _iter_rows_from_csv_file(meta["path"], meta.get("sep", ","))
    elif st == "dlt":
        src_iter = iter_dlt(meta["path"])
    elif st == "regex":
        src_iter = _iter_rows_from_regex_file(meta["path"], meta["regex"])
    elif st == "sqlite":
        sql = meta.get("query") or ("SELECT * FROM " + meta.get("table"))
        src_iter = parse_rows_sqlite_jdbc(meta["path"], sql)
    else:
        raise Exception("Unknown source_type: %s" % str(st))
    out = []
    for row in src_iter:
        rec = {}
        if plugin_type == "Geo-Track":
            epoch, ts_str = _timestamp_to_epoch_and_str(row.get(mapping.get("Timestamp")))
            rec["timestamp_epoch"] = epoch
            rec["timestamp_str"] = ts_str
            rec["longitude"] = _normalize_float(row.get(mapping.get("Longitude")), -180, 180)
            rec["latitude"] = _normalize_float(row.get(mapping.get("Latitude")), -90, 90)
            rec["speed"] = _normalize_float(row.get(mapping.get("Geschwindigkeit")))
        elif plugin_type == "Last-Position":
            rec["remark"] = row.get(mapping.get("Kommentar"))
            epoch, ts_str = _timestamp_to_epoch_and_str(row.get(mapping.get("Timestamp")))
            rec["timestamp_epoch"] = epoch
            rec["timestamp_str"] = ts_str
            rec["longitude"] = _normalize_float(row.get(mapping.get("Longitude")), -180, 180)
            rec["latitude"] = _normalize_float(row.get(mapping.get("Latitude")), -90, 90)
        elif plugin_type == "Geo-Bookmark":
            rec["remark"] = row.get(mapping.get("Kommentar"))
            rec["longitude"] = _normalize_float(row.get(mapping.get("Longitude")), -180, 180)
            rec["latitude"] = _normalize_float(row.get(mapping.get("Latitude")), -90, 90)
        elif plugin_type == "Event":
            epoch, ts_str = _timestamp_to_epoch_and_str(row.get(mapping.get("Timestamp")))
            rec["timestamp_epoch"] = epoch
            rec["timestamp_str"] = ts_str
            rec["event_name"] = row.get(mapping.get("Eventname"))
            rec["remark"] = row.get(mapping.get("Kommentar"))
        elif plugin_type == "Mobile":
            rec["lastname"] = _bb_text(row.get(mapping.get("Nachname")))
            rec["firstname"] = _bb_text(row.get(mapping.get("Vorname")))
            rec["phone"] = _normalize_phone(row.get(mapping.get("Telefonnummer")))
            rec["bt_mac"] = _normalize_mac(row.get(mapping.get("BluetoothAdresse")))
        elif plugin_type == "Bluetooth":
            rec["devicename"] = row.get(mapping.get("Geraetename"))
            rec["bt_mac"] = _normalize_mac(row.get(mapping.get("BluetoothAdresse")))
        elif plugin_type == "Call":
            rec["caller"] = _bb_text(row.get(mapping.get("Anrufername")))
            rec["caller_mac"] = _normalize_mac(row.get(mapping.get("MACAdresse")))
            rec["callee"] = _bb_text(row.get(mapping.get("Angerufener")))
            rec["callee_number"] = _normalize_phone(row.get(mapping.get("Nummer")))
            epoch, ts_str = _timestamp_to_epoch_and_str(row.get(mapping.get("Timestamp")))
            rec["timestamp_epoch"] = epoch
            rec["timestamp_str"] = ts_str
            rec["duration_seconds"] = _normalize_duration(row.get(mapping.get("Dauer")))
        out.append(rec)
    return out
'''

SQLITE_JDBC_BLOCK = r'''
from java.sql import DriverManager

def parse_rows_sqlite_jdbc(db_path, sql_query):
    rows = []
    tmp_dir = tempfile.mkdtemp(prefix="autopsy_sqlite_")
    conn = None
    stmt = None
    rs = None
    try:
        db_copy = _copy_sqlite_with_sidecars(db_path, tmp_dir)
        conn = DriverManager.getConnection("jdbc:sqlite:" + db_copy)
        stmt = conn.createStatement()
        rs = stmt.executeQuery(sql_query)
        meta = rs.getMetaData()
        while rs.next():
            rec = {}
            for i in range(1, meta.getColumnCount()+1):
                rec[meta.getColumnLabel(i)] = rs.getString(i)
            rows.append(rec)
        return rows
    finally:
        if rs is not None: rs.close()
        if stmt is not None: stmt.close()
        if conn is not None: conn.close()
        shutil.rmtree(tmp_dir, ignore_errors=True)

'''

def build_artifact_block(plugin_type):
    if plugin_type == "Geo-Track":
        return r'''
                    timestamp_epoch = row.get("timestamp_epoch")
                    latitude = row.get("latitude")
                    longitude = row.get("longitude")
                    speed = row.get("speed")
                    attrs = ArrayList()
                    if timestamp_epoch is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_DATETIME, moduleName, long(timestamp_epoch)))
                    if latitude is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_GEO_LATITUDE, moduleName, float(latitude)))
                    if longitude is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE, moduleName, float(longitude)))
                    if speed is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_GEO_VELOCITY, moduleName, float(speed)))
                    art = file.newDataArtifact(BlackboardArtifact.Type(BlackboardArtifact.ARTIFACT_TYPE.TSK_GPS_TRACKPOINT), attrs)
                    blackboard.indexArtifact(art)
'''
    elif plugin_type == "Last-Position":
        return r'''
                    remark = row.get("remark")
                    timestamp_epoch = row.get("timestamp_epoch")
                    latitude = row.get("latitude")
                    longitude = row.get("longitude")
                    attrs = ArrayList()
                    if remark is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_COMMENT, moduleName, _bb_text(remark)))
                    if timestamp_epoch is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_DATETIME, moduleName, long(timestamp_epoch)))
                    if latitude is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_GEO_LATITUDE, moduleName, float(latitude)))
                    if longitude is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE, moduleName, float(longitude)))
                    art = file.newDataArtifact(BlackboardArtifact.Type(BlackboardArtifact.ARTIFACT_TYPE.TSK_GPS_LAST_KNOWN_LOCATION), attrs)
                    blackboard.indexArtifact(art)
'''
    elif plugin_type == "Geo-Bookmark":
        return r'''
                    remark = row.get("remark")
                    latitude = row.get("latitude")
                    longitude = row.get("longitude")
                    attrs = ArrayList()
                    if remark is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_COMMENT, moduleName, _bb_text(remark)))
                    if latitude is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_GEO_LATITUDE, moduleName, float(latitude)))
                    if longitude is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_GEO_LONGITUDE, moduleName, float(longitude)))
                    art = file.newDataArtifact(BlackboardArtifact.Type(BlackboardArtifact.ARTIFACT_TYPE.TSK_GPS_BOOKMARK), attrs)
                    blackboard.indexArtifact(art)
'''
    elif plugin_type == "Event":
        return r'''
                    timestamp_epoch = row.get("timestamp_epoch")
                    event_name = row.get("event_name")
                    remark = row.get("remark")
                    attrs = ArrayList()
                    if timestamp_epoch is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_DATETIME, moduleName, long(timestamp_epoch)))
                    if event_name is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_PROG_NAME, moduleName, _bb_text(event_name)))
                    if remark is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_COMMENT, moduleName, _bb_text(remark)))
                    art = file.newDataArtifact(BlackboardArtifact.Type(BlackboardArtifact.ARTIFACT_TYPE.TSK_RECENT_OBJECT), attrs)
                    blackboard.indexArtifact(art)
'''
    elif plugin_type == "Mobile":
        return r'''
                    firstname = row.get("firstname")
                    lastname = row.get("lastname")
                    phone = row.get("phone")
                    bt_mac = row.get("bt_mac")
                    attrs = ArrayList()
                    if lastname is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_NAME, moduleName, _bb_text(lastname)))
                    if firstname is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_NAME_PERSON, moduleName, _bb_text(firstname)))
                    if phone is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_PHONE_NUMBER, moduleName, _bb_text(phone)))
                    if bt_mac is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_MAC_ADDRESS, moduleName, _bb_text(bt_mac)))
                    art = file.newDataArtifact(BlackboardArtifact.Type(BlackboardArtifact.ARTIFACT_TYPE.TSK_CONTACT), attrs)
                    blackboard.indexArtifact(art)
'''
    elif plugin_type == "Bluetooth":
        return r'''
                    devicename = row.get("devicename")
                    bt_mac = row.get("bt_mac")
                    attrs = ArrayList()
                    if devicename is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_DEVICE_NAME, moduleName, _bb_text(devicename)))
                    if bt_mac is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_MAC_ADDRESS, moduleName, _bb_text(bt_mac)))
                    art = file.newDataArtifact(BlackboardArtifact.Type(BlackboardArtifact.ARTIFACT_TYPE.TSK_BLUETOOTH_PAIRING), attrs)
                    blackboard.indexArtifact(art)
'''
    else:
        return r'''
                    caller = row.get("caller")
                    caller_mac = row.get("caller_mac")
                    callee = row.get("callee")
                    callee_number = row.get("callee_number")
                    timestamp_epoch = row.get("timestamp_epoch")
                    duration_seconds = row.get("duration_seconds")
                    attrs = ArrayList()
                    if timestamp_epoch is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_DATETIME, moduleName, long(timestamp_epoch)))
                    if caller is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_NAME_PERSON, moduleName, _bb_text(caller)))
                    if caller_mac is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_MAC_ADDRESS, moduleName, _bb_text(caller_mac)))
                    if callee is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_NAME_PERSON, moduleName, _bb_text(callee)))
                    if callee_number is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_PHONE_NUMBER, moduleName, _bb_text(callee_number)))
                    if duration_seconds is not None:
                        attrs.add(BlackboardAttribute(BlackboardAttribute.ATTRIBUTE_TYPE.TSK_COMMENT, moduleName, "Duration (s): %s" % str(duration_seconds)))
                    art = file.newDataArtifact(BlackboardArtifact.Type(BlackboardArtifact.ARTIFACT_TYPE.TSK_CALLLOG), attrs)
                    blackboard.indexArtifact(art)
'''

def build_autopsy_template(plugin_type, filename, meta, mapping, embed_parser=True):
    module_display_name = meta.get("module_display_name") or (plugin_type + " Universal Plugin")
    module_literal = "u" + ascii(module_display_name)
    filename_literal = "u" + ascii(filename)
    import json
    meta_literal = repr(json.dumps(meta, ensure_ascii=True))
    mapping_literal = repr(json.dumps(mapping, ensure_ascii=True))

    header = f'''# coding=utf-8

import json
FILENAME = {filename_literal}
META = json.loads({meta_literal})
MAPPING = json.loads({mapping_literal})

from org.sleuthkit.autopsy.ingest import IngestModuleFactoryAdapter, DataSourceIngestModule, IngestModule
from org.sleuthkit.autopsy.casemodule import Case
from org.sleuthkit.datamodel import Blackboard, BlackboardAttribute, BlackboardArtifact
from org.sleuthkit.autopsy.datamodel import ContentUtils
from org.sleuthkit.autopsy.ingest import IngestServices
from org.sleuthkit.autopsy.ingest import IngestMessage
from java.util import ArrayList
from java.io import File
import os
'''

    parts = [header]
    if embed_parser:
        parts.append(EMBEDDED_PARSER_BLOCK)
        parts.append(SQLITE_JDBC_BLOCK)
        if meta.get("source_type") == "dlt":
            from pathlib import Path
            parts.append((Path(__file__).parent / "dlt.py").read_text(encoding="utf-8"))

    artifact_block = build_artifact_block(plugin_type)

    core = f'''
class UniversalPluginFactory(IngestModuleFactoryAdapter):
    def getModuleDisplayName(self):
        return {module_literal}
    def getModuleDescription(self):
        return "Generated by stable wizard + preprocessing"
    def getModuleVersionNumber(self):
        return "2.0"
    def isDataSourceIngestModuleFactory(self):
        return True
    def createDataSourceIngestModule(self, options):
        return UniversalPluginModule()

class UniversalPluginModule(DataSourceIngestModule):
    def startUp(self, context):
        self.context = context
        self.moduleName = {module_literal}

    def process(self, dataSource, progressBar):
        case = Case.getCurrentCase()
        fm = case.getServices().getFileManager()
        blackboard = case.getServices().getBlackboard()

        files = fm.findFiles(dataSource, FILENAME)
        if files is None or files.isEmpty():
            return IngestModule.ProcessResult.OK

        for file in files:
            try:
                tempDir = os.path.join(Case.getCurrentCase().getTempDirectory(), "{plugin_type}_Universal")
                try:
                    os.mkdir(tempDir)
                except:
                    pass

                outFile = os.path.join(tempDir, _unique_temp_name(file.getName(), "evidence_"))
                ContentUtils.writeToFile(file, File(outFile))

                meta = dict(META)
                meta["path"] = outFile
                meta["script_dir"] = os.path.dirname(__file__).replace("\\\\", "/")

                meta["preprocess_inputs"] = [os.path.join(os.path.dirname(__file__), meta.get("preprocess_resource_dir", ""), n) for n in meta.get("preprocess_resources", [])]
                if meta.get("source_type") == "sqlite":
                    for suffix in ["-wal", "-shm"]:
                        try:
                            related = fm.findFiles(dataSource, file.getName() + suffix)
                            if related is not None:
                                for candidate in related:
                                    if candidate.getParentPath() == file.getParentPath():
                                        ContentUtils.writeToFile(candidate, File(outFile + suffix))
                                        break
                        except:
                            pass

                rows = parse_rows(meta, MAPPING, "{plugin_type}")
                moduleName = self.moduleName

                for row in rows:
{artifact_block}
            except Exception as e:
                IngestServices.getInstance().postMessage(
                    IngestMessage.createErrorMessage(self.moduleName, "Error processing " + file.getName(), str(e))
                )
                pass

        return IngestModule.ProcessResult.OK
'''
    parts.append(core)
    return "\n".join(parts)
