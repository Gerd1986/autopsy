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
- The type selector also lists every standard/custom **Data Artifact** registered
  in the open case, including imported LAVA types. Existing fields are discovered
  from persisted attributes. Additional fields can be selected from the case's
  attribute catalog or created with a name and type (STRING, DATETIME, DOUBLE,
  INTEGER, LONG, BYTE as hex, JSON). Numeric case IDs are never embedded in plugins.
- New-type presets cover all integrated VLEAPP icon families: vehicle, power,
  battery, diagnostics, settings, activity, temperature, navigation, contacts,
  messages, calls, Bluetooth/WLAN, USB, media, accounts and the other categories.
  Enter a free artifact name, add/remove fields, and map source columns. Presets
  and existing case types show their matching icons in the selector. Analysis
  Results are not exposed through this Data Artifact writer.
- Timestamp input supports Unix seconds/milliseconds/microseconds/nanoseconds,
  automatic units, ISO offsets, decimal point/comma fractions and custom strptime
  formats (`%f` for fractional seconds). Unzoned text is interpreted as UTC.
  Select minimum display precision `2` for hundredths or `3` for milliseconds;
  source precision is preserved even when higher. Decimal arithmetic avoids a
  float roundtrip. Preview, CSV and exported Jython use the same parsing code.
- Autopsy's standard DATETIME/map/timeline API stores whole Unix seconds. The
  generated module additionally stores the full UTC value, exact decimal epoch
  and raw input as visible attributes; no fractional part is silently discarded.
  Every mapped DATETIME field gets its own precision attributes. Original rows
  are preserved as JSON. New custom types with standard latitude/longitude fields
  get linked GPS artifacts for the normal Geolocation viewer; saved favorites
  and position samples use their respective map types.
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
  Artifact/precision changes can reuse the source preview. Re-export existing
  ingest scripts to use the new schema and subsecond implementation; previously
  exported self-contained plugins retain their original code.

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

## DLT and GeoTime Carving

Branch `feature/dlt-geotime-carving` adds:

* **DLT Table** in the lower Autopsy content pane. Select a `.dlt` file, or a
  file with a DLT storage/serial signature. Recognition also works before MIME
  ingest and on files previously identified as binary. File Type Identification
  reports signed DLT content as `application/x-dlt`. The viewer reads a temporary
  copy; evidence is unchanged. Pages contain up to 500 messages, with sorting,
  a literal text filter for the current page, and full field/payload/hex details.
* The Artifact Wizard source selector now includes **dlt**. Header and decoded
  payload columns can be mapped to the seven existing artifact types. The same
  DLT reader powers the preview, normalization and exported standalone Jython
  ingest module. The **DLT Viewer** preview tab has paging and payload details.
* **Preprocessing-Dateien auswählen…** opens a separate multiple-file chooser.
  Its ordered list appears in the tooltip. `{inputs}` expands to all selected
  files with command-line quoting. `{input1}`, `{input2}`, etc. refer to one file
  each and should be quoted in commands. `{input}` remains the current pipeline
  input; `{output}` and `{script_dir}` retain their previous meanings. For example:
  `python {inputs} "{input}" "{output}"` can pass a selected script plus its
  selected configuration before the evidence path. Exporting a module copies
  these resources to a unique sibling directory referenced by the module.
  Install/copy **both** the `.py` and its resource directory together. Local
  preview/resource paths are not embedded in exported metadata.
* **GeoTime Carving**, immediately right of Geolocation, opens a native Swing
  tool for an external binary file or the selected Autopsy file/unallocated
  object. Source content is read-only. It uses **the actual Geolocation MapPanel**
  and the same map-source preferences, including configured offline ZIP/MBTiles.
  It supports the reference parser's WKB, float32/64, semicircles, int32 scales
  1e7 through 1e3, int16 scaled coordinates, LE/BE and swapped pairs. Timestamp
  modes include Unix i32 seconds, i64 s/ms/us/ns, float64 seconds, FILETIME and
  GPS week/TOW milliseconds. Manual stride and auto format/stride detection are
  available. Radius/rectangle geofences apply **during auto scoring and full
  parsing**; UTC time bounds apply to both as well. GPS times retain the original
  parser's lack of leap-second correction. Geo and time are independent scans.
  Clicking a geo table row centers the map. Results retain byte offsets. CSV,
  GPX waypoints/track (geo only), and settings are saved to a new directory under
  the case's `Export/geotime-carving` directory (the actual path is shown).
  The table/map display up to 20,000 hits; exports contain all hits up to the
  selected limit. Reaching the hit limit ends the scan. AUTO samples the first
  5 MB before rerunning the chosen candidate on the full file. Interpret matches
  as carving candidates and verify them against source bytes. Cancel/closing the
  tool stops background processing; an interrupted export may leave partial files
  in that run's unique directory. A completed run includes `settings.json`.

DLT scope: classic DLT **v1 storage files** (`DLT 01`) and serial framing
(`DLS 01`), standard/extended headers, storage UTC time, ECU/APID/CTID,
message counter, session, uptime, message type/level, verbose scalar arguments
(strings including UTF-8, bool, integers, float32/64, raw bytes). Non-verbose
records expose message ID and raw hex; no FIBEX dictionary is inferred. Arrays,
fixed point, structs and trace argument layouts retain raw payload with a decode
status. Corrupt sections are resynchronized; truncated records are reported.
DLT v2 is explicitly rejected rather than interpreted as v1. Headerless raw
network captures and packet reassembly are outside this reader's scope.

Geo/time core provenance:
https://github.com/Gerd1986/binary_timestamp_geo_parser/tree/5ce4c9d41df22bb764ac068f8d38f5b7cf12ab8f
(stdlib parser/export functions adapted without desktop dependencies).
DLT wire reference:
https://github.com/COVESA/dlt-daemon/blob/master/include/dlt/dlt_protocol.h
and `dlt_common.h` / `src/shared/dlt_common.c` in the same repository.

Additional validation:
`python tests/artifact_factory/test_dlt_geotime.py --jython PATH/TO/jython.jar`.
The Windows workflow additionally checks the packaged isolated Python bridge,
registered DLT viewer, exact toolbar ordering, and the existing native-case
cleaner/launcher/FTL100 checks. The new branch builds a downloadable Actions
artifact; publication of the previous Wizard branch is independent.

### Plugin tools, directory input and case browser

The preview's **Datei…**, **Input-Ordner…** and **Aus Fall wählen…** buttons
select the primary evidence input. The case browser lazily traverses the open
case, allowing file or directory selection without a manual export. It stages
private temporary bytes for external processes; source evidence stays intact.
SQLite WAL/SHM siblings are staged from the same parent. A directory input needs
an explicit preprocessing step that turns it into CSV/SQLite/text/DLT. The
exported ingest module also stages matching source directories recursively.

**Skript-/Tool-Ordner…** selects a separate tool/configuration directory. It is
copied recursively into the exported plugin's unique resource folder, preserving
relative subdirectories. Place the exported `.py` and that folder together when
installing. Export outside the selected tool directory; symlinked tool entries
are rejected rather than silently packaging incomplete trees.

For shell-free commands, relative Python/Perl scripts and EXEs are resolved in
the explicitly selected tool directory first, then the plugin directory, then
the packaged resource directory. Preview's default plugin directory is Autopsy's
user `python_modules` folder; exported modules use their own `__file__` parent.
Absolute program paths are preserved. Working directory is the selected tool
folder, otherwise the plugin folder, so adjacent configurations are available.
Bare `.py` commands use the bundled Python 3 when running inside packaged Autopsy
(with system `python` fallback); bare `.pl` commands use `perl` from PATH. Explicit
interpreters like `py -3`, `python` and `perl` continue to work. EXEs run directly.

Examples (Shell unchecked):

```
python decoder.py "{input}"
perl decoder.pl "{input}" "{output}"
decoder.exe "{input_dir}" "{output}"
pas_geotime_stdout.py "{input}" --tz +00:00
```

`{script_dir}` now consistently denotes the plugin folder; `{tool_dir}` the
selected tool folder, falling back to the plugin folder; `{input_dir}` the
current input directory (or the parent of a file). `{inputs}` and `{input1}` etc.
retain the additional-resource semantics above. stdout is used when the command
does not create `{output}`. Shell mode intentionally preserves user shell syntax
and requires explicit paths/placeholders instead of automatic token rewriting.

Additional coverage: `test_preprocessing.py --jython PATH/TO/jython.jar` tests
plugin/tool resolution, directory inputs, relative configurations, Windows
argument quoting and real generated-parser execution. `CaseInputStagingTest`
uses a real native SleuthkitCase to validate file/folder copying, WAL/SHM, source
preservation and cleanup. The packaged toolbar-order test uses the actual
registered Timeline action ID (`timeline.Timeline`).

### NAND output naming in this branch

The optional NAND preprocessing bridge starts with `clean.img`. It keeps the
generic `.img` suffix for FAT images, whole-flash images and unknown content.
Only an image starting with a UBI v1 erase-counter header with valid offsets,
zero padding and a correct CRC is named `clean.ubi`. A UBI partition embedded
later in a raw flash image does not change its container extension. The image
bytes and hashes are unaffected; the sibling `.report.json` filename and its
`output` field follow the final name. Rename decisions are appended to the log.
The standalone vendored NAND scripts and device profiles are unchanged.
