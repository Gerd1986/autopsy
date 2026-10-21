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
attribute with a numbered display label, preserving duplicate header names in
the Autopsy table as well as in the database. The source row JSON
array preserves nulls, number representations and base64-marked BLOB values.
Additional attributes record source row number (1-based import order), parser
module, category, table and original source path as reported by VLEAPP.

Artifacts link to the imported LAVA database/TSV, not to an invented source file
in the original vehicle extraction. All report rows are imported without a UI
preview limit. Rows are streamed and committed in batches of at most 500.

LAVA's `artifact_icon` is mapped through a fixed semantic allowlist. Report-supplied
paths or executable icon content are never loaded. Specific artifact names take
precedence over broad manufacturer/category titles for legacy exports. Contacts,
calls, messages, favorites, searches, Bluetooth, Wi-Fi, USB, audio, photos, video,
software, accounts and networks use matching Autopsy icons. Vehicle, power,
battery, settings, diagnostics, signals, temperature, radio, chip, clock,
navigation, database and weather categories have dedicated original 16-pixel
glyphs. `generate_icons.py` reproduces those PNGs (Pillow, development only).
Icons are resolved from persisted type names, so they survive case reopen and
cannot leak numeric custom type IDs between cases. New types appear under the
normal custom/Data Artifact results and keep every original column inspectable.

## Geolocation and readable UTC dates

Explicit latitude/longitude degree columns create linked standard GPS artifacts
in the same transaction as their custom report row. This makes the records usable
by the normal Geolocation viewer's source, artifact-type and time filters.
Favorites use GPS Bookmark; known positions use GPS Trackpoint; destinations and
other geographic references use GPS Search. These are map presentation categories,
not evidence that a vehicle visited a saved destination. The full original row,
module, table, source and row number remain attached, with an associated-artifact
link to the custom VLEAPP record. Start/end coordinate pairs get separate labels;
they are not joined into an invented traveled route. Missing, nonfinite and
out-of-range coordinates are excluded from the map while their report rows stay
available. Raw/scaled/as-stored/radian columns are never treated as degree pairs.

LAVA `object_columns` with type `datetime` supplies Unix seconds in UTC. The
matching table field is shown as `21.10.2026 08:53:16.125 UTC`, retaining fractional
seconds. Type `date` is displayed as `21.10.2026` without adding a timezone.
Each original temporal value is also exposed as an original-field attribute,
and the original JSON row and report snapshot remain unchanged. Standard map
time attributes use Unix seconds, as required by Autopsy. ISO offsets are
converted to UTC; missing/invalid values remain unchanged. Untyped legacy
timestamp columns support explicit ms/us/ns units and epoch-sized numeric values.
Naive untyped local clocks, durations, uptime, counters, offsets and as-stored
fields are not assigned an invented UTC offset. No communication relationships
are inferred from imported text.

To apply these import-time changes to an existing report, use the updated build
and reimport the original export into a new test case. Previously imported
records are not silently rewritten.

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

### Source-resolution regression fix

The DSP resolves source files from the committed logical data-source tree. It
does not build a file index from FileManager progress callbacks, which only
report directories in this Autopsy version. Packaged integration tests now run
the actual FileManager for LAVA and nested TSV exports with duplicate basenames,
verify the Data Artifacts/result-tree query and ensure each artifact links to
its report file. Only the application ingest event publisher is isolated in the
standalone test; production FileManager and native case storage are real.

If an import made with the first VLEAPP release contains report files but no
artifacts, install the corrected build and import the original export into a
new test case. Existing cases are not silently modified or re-imported. The
old import-status.txt in the case's VLEAPP module folder records the failed
source lookup.
