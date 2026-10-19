#!/usr/bin/env python3
"""Offline transactional Data Artifact cleaner, based on data_artifact_cleaner_v3.

Only called after Autopsy closes the single-user case. Analysis Results and
source content are preserved; an SQLite backup precedes every applied deletion.
"""
import datetime
import json
from pathlib import Path
import sqlite3
import sys
import uuid

TARGET = '''SELECT a.artifact_id,a.artifact_obj_id FROM blackboard_artifacts a
JOIN blackboard_artifact_types t ON t.artifact_type_id=a.artifact_type_id
WHERE t.category_type=0'''
REQUIRED = {'tsk_objects','blackboard_artifacts','blackboard_artifact_types',
            'blackboard_attributes','tsk_analysis_results','tsk_events','tsk_event_descriptions'}


def scalar(connection, sql):
    return connection.execute(sql).fetchone()[0]


def dispatch(request):
    database = Path(request['database']).resolve()
    if not database.is_file(): raise ValueError('Case database not found')
    apply = request.get('action') == 'apply'
    if request.get('action') not in ('preview','apply'): raise ValueError('Unknown cleaner action')
    if apply and (request.get('confirmation') != 'LOESCHEN' or request.get('case_closed') is not True):
        raise ValueError('Close the case and explicitly confirm LOESCHEN before deletion')
    uri = database.as_uri()+('?mode=rw' if apply else '?mode=ro')
    with sqlite3.connect(uri, uri=True, timeout=10) as c:
        c.execute('PRAGMA foreign_keys=ON'); c.execute('PRAGMA busy_timeout=10000')
        tables = {r[0] for r in c.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        if not REQUIRED <= tables: raise ValueError('Unsupported case database schema')
        count = scalar(c, 'SELECT count(*) FROM ('+TARGET+')')
        analysis = scalar(c, 'SELECT count(*) FROM tsk_analysis_results')
        types = [{'name':name,'count':n} for name,n in c.execute('''SELECT t.display_name,count(*)
            FROM blackboard_artifacts a JOIN blackboard_artifact_types t
            ON a.artifact_type_id=t.artifact_type_id WHERE t.category_type=0
            GROUP BY t.artifact_type_id,t.display_name ORDER BY count(*) DESC''')]
        result = {'data_artifacts':count,'analysis_results_preserved':analysis,'types':types,'database':str(database)}
        if not apply: return result
        if type(request.get('expected_count')) is not int or request['expected_count'] != count:
            raise ValueError('Artifact count changed; refresh the preview before deletion')
        if not count:
            result.update(deleted=0, backup=None)
            return result
        if scalar(c,'PRAGMA quick_check') != 'ok': raise ValueError('Database quick_check failed')
        if c.execute('PRAGMA foreign_key_check').fetchone() is not None:
            raise ValueError('Pre-existing foreign key errors; database was not modified')
        backup = database.with_name(database.name+'.backup-'+datetime.datetime.now().strftime('%Y%m%d-%H%M%S')+'-'+uuid.uuid4().hex[:8])
        with sqlite3.connect(backup) as dst:
            c.backup(dst)
            if scalar(dst,'PRAGMA quick_check') != 'ok': raise ValueError('Backup integrity check failed')
        c.execute('BEGIN EXCLUSIVE')
        try:
            if scalar(c, 'SELECT count(*) FROM ('+TARGET+')') != count:
                raise ValueError('Artifact count changed after backup')
            c.execute('CREATE TEMP TABLE kill_artifacts(artifact_id INTEGER PRIMARY KEY, artifact_obj_id INTEGER UNIQUE NOT NULL)')
            c.execute('INSERT INTO kill_artifacts '+TARGET)
            c.execute('CREATE TEMP TABLE kill_desc(event_description_id INTEGER PRIMARY KEY)')
            c.execute('INSERT INTO kill_desc SELECT event_description_id FROM tsk_event_descriptions WHERE artifact_id IN (SELECT artifact_id FROM kill_artifacts)')
            c.execute('DELETE FROM tsk_events WHERE event_description_id IN (SELECT event_description_id FROM kill_desc)')
            c.execute('DELETE FROM tsk_event_descriptions WHERE event_description_id IN (SELECT event_description_id FROM kill_desc)')
            c.execute('DELETE FROM blackboard_attributes WHERE artifact_id IN (SELECT artifact_id FROM kill_artifacts)')
            if 'blackboard_artifact_tags' in tables:
                c.execute('DELETE FROM blackboard_artifact_tags WHERE artifact_id IN (SELECT artifact_id FROM kill_artifacts)')
            if 'tsk_data_artifacts' in tables:
                c.execute('DELETE FROM tsk_data_artifacts WHERE artifact_obj_id IN (SELECT artifact_obj_id FROM kill_artifacts)')
            c.execute('DELETE FROM blackboard_artifacts WHERE artifact_id IN (SELECT artifact_id FROM kill_artifacts)')
            c.execute('DELETE FROM tsk_objects WHERE obj_id IN (SELECT artifact_obj_id FROM kill_artifacts)')
            if scalar(c,'SELECT count(*) FROM ('+TARGET+')'): raise ValueError('Data Artifacts remain')
            if scalar(c,'SELECT count(*) FROM tsk_analysis_results') != analysis:
                raise ValueError('Analysis Results changed; deletion rolled back')
            if c.execute('PRAGMA foreign_key_check').fetchone() is not None:
                raise ValueError('Foreign key errors; deletion rolled back')
            c.commit()
        except BaseException:
            c.rollback(); raise
        result.update(deleted=count,backup=str(backup))
        return result


if __name__ == '__main__':
    try:
        print(json.dumps({'ok':True,**dispatch(json.load(sys.stdin))}))
    except Exception as exc:
        print(json.dumps({'ok':False,'error':str(exc)})); sys.exit(2)
