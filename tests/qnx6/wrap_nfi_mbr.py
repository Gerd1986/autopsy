#!/usr/bin/env python3
"""Wrap the NFI QNX6 partition fixture in a standard MBR disk image.

The partition starts at LBA 2048 (1 MiB); partition type 0x4D is QNX4.x.
QNX6 does not have a universally assigned MBR type; 0x4D is used here
only as a QNX-family partition marker, not for filesystem detection.
"""
import argparse
import pathlib
import struct

SECTOR = 512
START_LBA = 2048


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("partition", type=pathlib.Path)
    parser.add_argument("disk", type=pathlib.Path)
    args = parser.parse_args()
    payload = args.partition.read_bytes()
    if not payload or len(payload) % SECTOR:
        raise ValueError("Partition image must be nonempty and 512-byte aligned")
    sectors = len(payload) // SECTOR
    if sectors > 0xFFFFFFFF - START_LBA:
        raise ValueError("Partition too large for MBR")
    mbr = bytearray(SECTOR)
    # Boot flag, starting CHS, type, ending CHS, starting LBA, sectors.
    struct.pack_into("<B3sB3sII", mbr, 446, 0, bytes((0,2,0)), 0x4D,
                     bytes((254,255,255)), START_LBA, sectors)
    mbr[510:512] = bytes((85,170))
    with args.disk.open("wb") as output:
        output.write(mbr)
        output.write(bytes(START_LBA * SECTOR - SECTOR))
        output.write(payload)
    print(f"MBR disk: {args.disk}, partition LBA={START_LBA}, sectors={sectors}, type=0x4D")


if __name__ == "__main__":
    main()
