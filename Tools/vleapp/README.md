# VLEAPP export data source

Choose **Add Data Source → VLEAPP Export (LAVA / TSV)**, select a completed report
folder, then continue through the normal wizard. This imports an existing report;
it does not execute VLEAPP or require a separate VLEAPP installation.

Supported input:

* Full LAVA report containing `_lava_data.lava` and `_lava_artifacts.db`. The JSON
  category, artifact name, module, table and `column_map` drive the import. Both
  files are required. Metadata table names are validated against actual SQLite
  tables and SQL identifiers are quoted. Database connections are read-only.
* Legacy report with `_TSV Exports/**/*.tsv`, or that directory selected directly.
  UTF-8/BOM, quoted tabs/newlines and repeated append-mode headers are supported.
* If a LAVA pair is present it is used exclusively; TSV duplicates are not imported.
  Incomplete LAVA is an error, not a silent fallback to possibly incomplete TSV.
* HTML-only reports cannot supply reliable complete rows and are rejected. Views
  omitted from HTML because of VLEAPP's row limit remain available via LAVA/TSV.

A fresh snapshot is stored in the case's `ModuleOutput/VLEAPP/import-…/report`.
The entire report, including media and HTML assets, is registered as logical
files. Original export bytes are not changed; symlinks and special files are
rejected. Export sizes/identities are checked while copying. Import only completed,
stable reports. Plan enough case disk space for a complete copy. `manifest.json`
records source paths, per-file SHA-256, table metadata and record counts;
`import-status.txt` records completion or failure. Do not delete this case folder:
the logical data source references its report snapshot.

## New artifact types and icons

Each source table/schema gets its own persistent custom **Data Artifact** type,
shown as `VLEAPP · category · artifact name`. Names are hashed from category,
name, module, table and column schema so unrelated reports cannot collide just
because they have the same visible title. Each column gets its own custom string
attribute, preserving duplicate header names by ordinal. The source row JSON
array preserves nulls, number representations and base64-marked BLOB values.
Additional attributes record source row number (1-based import order), parser
module, category, table and original source path as reported by VLEAPP.

Artifacts link to the imported LAVA database/TSV, not to an invented source file
in the original vehicle extraction. All report rows are imported without a UI
preview limit. Rows are streamed and committed in batches of at most 500.

Contacts, calls, messages, locations/navigation, Bluetooth, Wi-Fi and media get
existing matching Autopsy icons; other records use the device icon. Icon choice
is based on the report/category title and is presentation only. It does not infer
or rewrite coordinates, timestamps, call direction or communication accounts.
Icons are resolved from persisted type names, so they survive case reopen and
cannot leak numeric custom type IDs between cases. New types appear under the
normal custom/Data Artifact results and keep every original column inspectable.
No automatic standard Geolocation/Timeline/Communications records are fabricated
from ambiguous column labels or time zones.

The normal Ingest options remain available after import; the artifact import
itself is not a rerunnable ingest module. Importing the same report again creates
a separate data source. On cancellation/error after data source creation, the
snapshot and any committed records remain visible with a noncritical error;
never silently retry expecting it to append only missing rows. A cancelled batch
rolls back, but already committed batches are retained and reported.

## Validation and provenance

`python tests/vleapp/test_vleapp.py` tests writer-layout LAVA and TSV fixtures,
Unicode, quoted multiline text, null/BLOB, duplicated metadata, repeated headers,
missing/corrupt/incomplete exports, source preservation and folder boundaries.
`python tests/vleapp/test_packaged.py DISTRIBUTION` additionally uses bundled
Python and the production Java importer with a native SleuthkitCase (1,201 rows,
reopen, attribute values, persistent icons and initial/mid-import cancellation).
Windows CI also checks the actual packaged DataSourceProcessor service entry.

Wire-format reference inspected: abrignoni/VLEAPP commit
`958212c5fd5890e34c17b229ef9b0875eb530783`, `scripts/lavafuncs.py`
(`lava_process_artifact`, `lava_create_sqlite_table`) and
`scripts/ilapfuncs.py` (`tsv`). No upstream parser code is vendored.
