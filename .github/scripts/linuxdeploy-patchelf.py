#!/usr/bin/env python3
"""Preserve verified Runtime ELF bytes while linuxdeploy packages desktop libraries."""
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys

REAL_PATCHELF = '/usr/bin/patchelf'


def protected_runtime_file(path):
    if not path.is_file():
        return False
    for parent in path.parents:
        manifest = parent / 'runtime-bundle.json'
        if parent.name != 'resources' or not manifest.is_file():
            continue
        entries = json.loads(manifest.read_text(encoding='utf-8'))['files']
        expected = entries.get(path.relative_to(parent).as_posix())
        if expected is None:
            return False
        actual = hashlib.sha256(path.read_bytes()).hexdigest()
        if actual != expected:
            raise RuntimeError(f'Runtime bytes changed before AppImage packaging: {path}')
        return True
    return False


def has_dynamic_segment(path):
    with path.open('rb') as stream:
        header = stream.read(64)
        if header[:4] != b'\x7fELF' or header[4] not in (1, 2) or header[5] not in (1, 2):
            raise RuntimeError(f'Not a supported ELF file: {path}')
        endian = '<' if header[5] == 1 else '>'
        if header[4] == 2:
            offset = struct.unpack_from(endian + 'Q', header, 32)[0]
            size, count = struct.unpack_from(endian + 'HH', header, 54)
        else:
            offset = struct.unpack_from(endian + 'I', header, 28)[0]
            size, count = struct.unpack_from(endian + 'HH', header, 42)
        for index in range(count):
            stream.seek(offset + index * size)
            if struct.unpack(endian + 'I', stream.read(4))[0] == 2:  # PT_DYNAMIC
                return True
    return False


def main():
    args = sys.argv[1:]
    if args and protected_runtime_file(Path(args[-1]).resolve()):
        if args[0] == '--set-rpath' and len(args) == 3:
            # The bundle already has working relative Java/CLI paths and hashes them.
            return 0
        if args[0] == '--print-rpath' and len(args) == 2:
            if not has_dynamic_segment(Path(args[-1])):
                return 0
        else:
            raise RuntimeError(f'Unsupported patchelf operation on verified Runtime: {args[0]}')
    return subprocess.run([REAL_PATCHELF, *args], check=False).returncode


if __name__ == '__main__':
    sys.exit(main())
