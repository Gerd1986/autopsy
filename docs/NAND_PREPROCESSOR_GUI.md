# Optional NAND preprocessing during image import

In **Add Data Source → Disk Image or VM File**, select the dump as usual. Use
**NAND config (optional) → Browse...** to select a JSON device profile. The chooser
starts in the shipped `nand/profiles` directory; external JSON profiles work too.
**None** clears the selection. Every new import starts without a profile.

Without a selected configuration, Autopsy imports the original image normally.
With a configuration, Autopsy first runs the shipped Sleuth Kit NAND preprocessor
in a background task, with BCH ECC enabled and undecodable codewords set to fail.
The original is read only. The raw NAND dump does not have to pass Sleuth Kit's
image-open check before preprocessing.

Each run creates a separate directory under the current case's export directory:

```
Export/nand-preprocessed/dump-<unique>/
    clean.ubi
    clean.ubi.report.json
    device-profile.json
    preprocessor.log
```

The path uses the case's actual export directory (including multi-user cases).
The exact selected configuration is copied before processing. The report records
input/output/profile SHA-256, geometry and ECC statistics. Autopsy registers and
analyzes **clean.ubi**, only after the preprocessor succeeds. Ingest modules work
on that image. The derived image must stay at this path while the case is used.

On preprocessing failure or cancellation, no data source is registered and
incomplete output is removed; the configuration and log remain for diagnosis.
Separate imports never overwrite each other's exports. Cancellation after image
registration has the same best-effort behavior as ordinary Autopsy imports.
Original dump hashes and decryption passwords are not applied to derived bytes;
those fields are cleared/disabled while a NAND profile is selected.

The Qualcomm Kommbox 4352-byte profile comes from Sleuth Kit commit
`89c86ef49169c6971fde7cd4f0dac4f46495fe79` (preprocessor code originally
`d47dacc059bac937311306daea86ea8889dcabe8`). It produces 4096-byte logical pages,
with the final 484 bytes per page unprotected by ECC. See
`Tools/nand/UBI_PREPROCESSOR.md` and `profiles/README.md` for profile format
and correction limits. Configurations describe byte ranges and BCH parameters;
they are not executable scripts.

The fresh Windows build packages an isolated Python 3.10 runtime, so end users
need no Python installation. Other source builds need Python 3.10+ (`python` on
Windows, `python3` elsewhere) unless `nand/python/python.exe` is bundled. The
preprocessor requires only the Python standard library. The fresh build also
pins Sleuth Kit to the merged UBI/UBIFS revision above.

Validation: `python tests/nand/test_bridge.py` compiles the Java process bridge
and exercises actual preprocessing, ECC correction, failure, cancellation,
report/config preservation, paths with spaces and distinct export directories.
The fresh Windows workflow builds the complete Autopsy GUI and smoke-tests the
packaged runtime and launcher.

## LBA mapping profiles

The bundled preprocessor also accepts the optional mapping fields from Sleuth Kit
`develop-4.1x`. Selecting a mapping config corrects BCH first, then reconstructs
logical sector order before registering the exported image. `clean.ubi` is the
legacy export filename; its content may also be FAT32 or another filesystem.
The native filesystem parser detects content rather than relying on the suffix.

`synthetic-lba-bch-fat32.json` is a test-only profile for the published synthetic
NAND fixture. Do not use it as a guessed real-device profile. Field documentation
is included in `Tools/nand/NAND_LBA_MAPPING.md`. Without a selected config the
preprocessor is still bypassed.

The Windows release workflow tests both the source scripts and the installed
Python runtime through the production Java bridge, compares the exported FAT32
image byte-for-byte and verifies native fsstat/fls/icat file payload hashes.
