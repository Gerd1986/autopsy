#!/usr/bin/env python3
"""Exercise the production Java bridge and actual BCH preprocessor without Autopsy dependencies."""
import hashlib
import json
from pathlib import Path
import random
import shutil
import subprocess
import sys
import tempfile
import struct
import zlib

ROOT = Path(__file__).resolve().parents[2]

def gf_mul(a, b):
    out = 0
    while b:
        if b & 1:
            out ^= a
        b >>= 1
        a <<= 1
        if a & 8192:
            a ^= 0x201b
    return out


def generator():
    exponents = [1]
    for _ in range(8190):
        exponents.append(gf_mul(exponents[-1], 2))
    roots = set()
    for first in range(1, 17):
        i = first
        while i not in roots:
            roots.add(i)
            i = (i*2) % 8191
    polynomial = [1]
    for root in sorted(roots):
        out = [0]*(len(polynomial)+1)
        for i, coefficient in enumerate(polynomial):
            out[i] ^= gf_mul(coefficient, exponents[root])
            out[i+1] ^= coefficient
        polynomial = out
    assert len(polynomial) == 105 and set(polynomial) <= {0, 1}
    return sum(c << i for i, c in enumerate(polynomial))


def encode(payload, polynomial):
    remainder = int.from_bytes(payload, 'big') << 104
    while remainder.bit_length() > 104:
        remainder ^= polynomial << (remainder.bit_length()-105)
    return remainder.to_bytes(13, 'big')


def pack_page(data, polynomial):
    assert len(data) == 4096
    page = bytearray(b'\xff'*4352)
    for i in range(7):
        base = 532*i
        payload = data[516*i:516*(i+1)]
        page[base:base+372] = payload[:372]
        page[base+373:base+517] = payload[372:]
        page[base+517:base+530] = b'\xff'*13 if payload == b'\xff'*516 else encode(payload, polynomial)
    page[3724:4096] = data[3612:3984]
    page[4097:4209] = data[3984:]
    return bytes(page)



def main():
    with tempfile.TemporaryDirectory(prefix="nand bridge ") as temp:
        work = Path(temp)
        classes = work / "classes"
        classes.mkdir()
        compiler = ["javac"] if shutil.which("javac") else ["java", "com.sun.tools.javac.Main"]
        subprocess.run(compiler + ["-d", str(classes),
            str(ROOT / "Core/src/org/sleuthkit/autopsy/casemodule/NandPreprocessor.java"),
            str(ROOT / "tests/nand/BridgeHarness.java")], check=True)
        script = ROOT / "Tools/nand/preprocess_ubi.py"
        config = ROOT / "Tools/nand/profiles/qualcomm-kommbox-4352.json"
        exports = work / "case exports"
        source = work / "original dump.bin"
        rng = random.Random(1337)
        data = rng.randbytes(4096)
        page = pack_page(data, generator())
        damaged = bytearray(page)
        damaged[0] ^= 0x80
        source.write_bytes(damaged)
        original_hash = hashlib.sha256(source.read_bytes()).hexdigest()
        def run(profile=config, selected_script=script, mode="normal", dump=source):
            return subprocess.run(["java", "-cp", str(classes),
                "org.sleuthkit.autopsy.casemodule.BridgeHarness",
                str(dump), str(profile), str(exports), str(selected_script), sys.executable, mode],
                capture_output=True, text=True, timeout=15)
        first = run()
        assert first.returncode == 0, first.stderr
        output = Path(first.stdout.strip())
        assert output.parent.parent == exports / "nand-preprocessed"
        assert output.suffix == ".img"
        assert output.read_bytes() == data
        assert (output.parent / "device-profile.json").read_bytes() == config.read_bytes()
        report = json.loads(Path(str(output) + ".report.json").read_text())
        assert report["corrected_bits"] == 1 and report["status"] == "completed"
        assert report["input_sha256"] == original_hash
        assert report["output_sha256"] == hashlib.sha256(data).hexdigest()
        assert report["profile_sha256"] == hashlib.sha256(config.read_bytes()).hexdigest()
        assert report["output"] == str(output.absolute())
        # UBI naming requires a real v1 EC header, valid offsets and its CRC.
        header=bytearray(64);header[:4]=b'UBI#';header[4]=1
        struct.pack_into('>II',header,16,512,1024)
        struct.pack_into('>I',header,60,zlib.crc32(header[:60])^0xffffffff)
        ubi_data=bytes(header)+b'\xff'*(4096-64)
        ubi_source=work/'UBI dump.bin';ubi_source.write_bytes(pack_page(ubi_data,generator()))
        ubi_result=run(dump=ubi_source);assert ubi_result.returncode==0,ubi_result.stderr
        ubi_output=Path(ubi_result.stdout.strip());assert ubi_output.suffix=='.ubi' and ubi_output.read_bytes()==ubi_data
        ubi_report=json.loads(Path(str(ubi_output)+'.report.json').read_text())
        assert ubi_report['output']==str(ubi_output.absolute()) and ubi_report['output_sha256']==hashlib.sha256(ubi_data).hexdigest()
        header[60]^=1
        false_source=work/'false UBI dump.bin';false_source.write_bytes(pack_page(bytes(header)+b'\xff'*(4096-64),generator()))
        false_result=run(dump=false_source);assert false_result.returncode==0,false_result.stderr
        false_output=Path(false_result.stdout.strip());assert false_output.suffix=='.img'
        second = run()
        assert second.returncode == 0 and Path(second.stdout.strip()) != output
        assert output.read_bytes() == data
        bad = work / "invalid config.json"
        bad.write_text('{"schema_version": 99}')
        failure = run(profile=bad)
        assert failure.returncode != 0 and "No data source was added" in failure.stderr
        # A selected nine-bit corruption is outside the correction radius.
        broken = bytearray(page)
        for bit in range(9):
            broken[bit // 8] ^= 1 << (7 - bit % 8)
        invalid_dump = work / "bad ECC.bin"
        invalid_dump.write_bytes(broken)
        assert run(dump=invalid_dump).returncode != 0
        partial = work / "partial.bin"
        partial.write_bytes(page[:-1])
        assert run(dump=partial).returncode != 0
        assert run(selected_script=work / "missing.py").returncode != 0
        # Simulate a long child after it has started writing incomplete output.
        slow = work / "slow child.py"
        slow.write_text("import pathlib,sys,time\noutput=pathlib.Path(sys.argv[sys.argv.index('--output')+1])\noutput.write_bytes(b'incomplete')\n(output.parent / '.clean.img.partial').write_bytes(b'partial')\ntime.sleep(30)\n")
        cancelled = run(selected_script=slow, mode="cancel")
        assert cancelled.returncode == 3, cancelled.stderr
        interrupted = run(selected_script=slow, mode="interrupt")
        assert interrupted.returncode == 3, interrupted.stderr
        incomplete = work / "incomplete success.py"
        incomplete.write_text("import pathlib,sys\noutput=pathlib.Path(sys.argv[sys.argv.index('--output')+1])\noutput.write_bytes(b'no report')\n")
        assert run(selected_script=incomplete).returncode != 0
        dirs = list((exports / "nand-preprocessed").iterdir())
        success = {output.parent, Path(second.stdout.strip()).parent, ubi_output.parent, false_output.parent}
        for directory in dirs:
            if directory not in success:
                assert not (directory / "clean.ubi").exists()
                assert not (directory / "clean.img").exists()
                assert not list(directory.glob("clean.*.report.json"))
                assert not list(directory.glob(".clean.*"))
                assert (directory / "device-profile.json").exists()
                assert (directory / "preprocessor.log").exists()
        assert hashlib.sha256(source.read_bytes()).hexdigest() == original_hash
        print("PASS: Java bridge, BCH correction, reports, config snapshots, separate exports, malformed profile, ECC failure, partial dump, missing tool, incomplete result, cancellation and interruption")

if __name__ == "__main__":
    main()
