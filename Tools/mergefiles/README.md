# MergeFiles in the image input dialog

`merge_files.py` is the user-supplied reference, preserved unchanged. Its header
identifies it as a Python port of Jens Elstner's mergeFiles 0.3.3. This branch
implements those byte transformation semantics natively in Java, so the packaged
Autopsy does not require Tk or a separate interpreter for MergeFiles.

Open **Add Data Source → Disk Image → Select Data Source**, then
**MergeFiles – Dateien verschachteln…** below the optional NAND configuration.

* Add local chip/image files. Reorder or remove entries as needed.
* Block size defaults to one byte. With inputs ABC and xyz, byte interleaving
  gives AxByCz. A block size of two emits two bytes from each selected input.
* Empty order uses the list order. `3,3,1,2,2` repeats blocks from input 3 and 2.
* **Bytes innerhalb jedes Blocks umkehren** reverses each selected block,
  not the order of the blocks or the whole image.
* Exactly the number of full blocks in the **shortest selected input** is read.
  Omitted inputs still limit that length, matching the supplied reference.
  Trailing incomplete blocks are discarded; no padding/concatenation is inferred.
* The synchronized two-file Hex/ASCII preview uses the same byte offset, accepts
  decimal or `0x…` offsets, and moves both views by 16 bytes with the mouse wheel.
  **Nach Block-Swap** previews full source blocks after the reversal setting,
  rather than pretending to show the final interleaved output.
* Choose an output `.img`. The default is a unique filename in the case Export
  folder. Existing destinations require confirmation; an input file (including
  a hardlink/symlink alias) cannot be overwritten. Inputs are opened read-only.
* Processing streams to a temporary sibling file. Cancellation/failure leaves
  an existing destination intact. An output is committed only after all full
  blocks have been processed and source sizes/modification identities rechecked.
* On success choose **Ergebnis als Image übernehmen**. The image-input path is
  set to the result and stale image hashes/password/NAND configuration are
  cleared. Normal Autopsy image validation/import follows; no data source is
  silently added. A zero-byte result cannot be used as an image. Close the tool
  without that button to leave the current image selection unchanged.

`tests/mergefiles/test_mergefiles.py` executes only the supplied reference's
stdlib merger functions (not its Tk GUI) and compares exact output bytes for
block sizes, both reversal settings, repeated/omitted inputs, uneven lengths,
large streamed blocks, and preview mappings. It also exercises cancellation,
output/source aliases, replacement and evidence preservation. The Windows
workflow runs these tests and validates that the native classes are packaged,
then uses the existing full JNI/launcher checks before automatic publication.
