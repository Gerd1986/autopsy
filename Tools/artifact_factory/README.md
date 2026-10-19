# Integrated Artifact Wizard

The native Swing dialog is opened from **Artifact Wizard**, immediately after
Discovery in the main case toolbar, or from Tools. It uses Autopsy's bundled
Python runtime and requires no pandas, Tk, pip installation or external wizard.
The backend uses Python's standard library and a JSON protocol over stdin/stdout.
Generated ingest modules contain their own parser and run in Autopsy's Jython.

The parser/template functionality is adapted from Gerd1986/autopsy-artifact-factory
`wizard.py`, source blob `349f5d930d153767546d01c63d704b4e28aad367`:
https://github.com/Gerd1986/autopsy-artifact-factory/blob/main/wizard.py
The offline cleaner is adapted from the user's `data_artifact_cleaner_v3.py`.

## Parser and plugin

- CSV with configurable delimiter, SQLite table or SQL query, and text/Regex with
  named groups. Optional multiline/DOTALL matching supports records spanning lines.
- Preview up to 50 rows and map individual source columns to artifact fields.
- Geo-Track, Last-Position, Geo-Bookmark, Event, Mobile/contact, Bluetooth and Call.
- Input filename/pattern and module display name independent of the preview file.
- Reorderable preprocessing steps with command, output suffix and explicit shell
  mode. `{input}`, `{output}` and `{script_dir}` are supported; stdout is captured
  as bytes when the command does not produce its own output file. Commands are
  executed only when the user runs the preview/test/export operation.
- Test normalization, export normalized UTF-8 CSV and export a standalone Jython
  data-source ingest module. Module generation does not automatically install or
  run an ingest module. Use Autopsy's Python module installation workflow.
- The preview source is never overwritten. SQLite evidence and matching WAL/SHM
  are copied before opening SQLite; committed WAL data is included. SQL preview
  connections are read-only. Use stable evidence copies rather than a live app DB.
- Generated modules use Unicode-safe JSON metadata/name literals, correctly
  quoted SQL table names, and only sidecars sharing the evidence file's parent
  path. Exported templates do not embed the local evidence/preview path.
- Changing the source settings or pipeline requires a refreshed preview before
  a test/export. Empty tables retain their column names for mapping.

## Blackboard cleanup

The second tab operates on the current **single-user SQLite case**. It is the
Data Artifact cleaner, not an indiscriminate deletion of Analysis Results.

1. Stop ingest, load the preview and review counts/types.
2. Choose Data Artifact deletion and type `LOESCHEN` in the dialog.
3. Autopsy rechecks the case/ingest state and closes the case.
4. The cleaner checks the SQLite schema/integrity, verifies the preview count and
   creates a consistent `autopsy.db.backup-<timestamp>-<id>` beside the case DB.
5. In one transaction it deletes selected Data Artifacts and their attributes,
   tags, data-artifact rows and associated Timeline events/descriptions. It checks
   foreign keys and preservation of Analysis Results before committing.
6. Autopsy reopens the case after success or failure, refreshing its case caches.
   Failed SQL rolls back; a failure to reopen reports the original case path.

Source content/files, Analysis Results and their scores are preserved. Multi-user
PostgreSQL cases and active ingests are rejected. The existing external keyword
search index is not rebuilt by this Blackboard database operation; old indexed
hits may require the normal keyword-index maintenance workflow. The confirmation
panel makes this scope explicit. This tool does not delete evidence files.

The backend protocol requires explicit confirmation and an offline flag for
apply; it is not a database-lock/security substitute. The native UI performs the
actual case close and re-open. Never call the apply backend on a case open in
another Autopsy process. SQLite lock failures abort without applying changes.

## Validation

`python tests/artifact_factory/test_backend.py` exercises all source types,
normalization, sidecars, preprocessing, templates, cleaner backup and rollback.
Add `--jython /path/to/jython-standalone-2.7.4.jar` for actual Jython parsing and
compilation of all generated module types.

The Windows feature build additionally compiles the production Core module,
checks packaged script identity, runs the Java/Python bridge with the isolated
Python runtime, creates a real native SleuthkitCase with Data/Analysis artifacts,
cleans and reopens it, and verifies preserved files, Analysis Results and scores.
The lifecycle test rejects applying cleanup while a case remains open and tests
reopening after successful cleanup, cleanup failure and close failure.
