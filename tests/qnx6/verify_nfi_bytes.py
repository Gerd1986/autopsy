#!/usr/bin/env python3
"""Verify QNX6 regular file bytes against the NFI qnxmount reference tarball."""
import argparse
import hashlib
import re
import subprocess
import tarfile


def run(*args):
    return subprocess.run(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True).stdout


def main():
    p = argparse.ArgumentParser()
    for arg in ("image", "archive", "fls", "icat"):
        p.add_argument(arg)
    a = p.parse_args()
    listing = run(a.fls, "-f", "qnx6", "-r", "-p", a.image).decode("utf-8", "replace")
    files = {}
    for line in listing.splitlines():
        # TSK fls format: r/r <inode>[-attr-type-attr-id]: <relative path>
        m = re.match(r"^r/[^\s]*\s+(\d+)(?:-\d+-\d+)?:\s*(.*?)\s*$", line)
        if m:
            files[m.group(2).removeprefix("./")] = m.group(1)
    if not files:
        raise RuntimeError("No regular QNX6 files in fls output:\n" + listing[:4000])
    count = 0
    with tarfile.open(a.archive, "r:gz") as archive:
        for member in archive:
            if not member.isfile():
                continue
            name = member.name.removeprefix("./").lstrip("/")
            inode = files.get(name)
            if inode is None:
                raise RuntimeError(f"Missing QNX6 file: {name}")
            expected = archive.extractfile(member).read()
            actual = run(a.icat, "-f", "qnx6", a.image, inode)
            if actual != expected:
                raise RuntimeError(
                    f"Content mismatch: {name}; expected sha256="
                    f"{hashlib.sha256(expected).hexdigest()}, actual sha256="
                    f"{hashlib.sha256(actual).hexdigest()}"
                )
            count += 1
            print(f"PASS {name}: {len(actual)} bytes", flush=True)
    if count == 0:
        raise RuntimeError("No regular files in NFI reference archive")
    print(f"PASS: byte-for-byte comparison of {count} NFI QNX6 files")


if __name__ == "__main__":
    main()
