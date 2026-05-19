#!/usr/bin/env python3
"""validate_elf.py — Validate ELF files: phdr table + PT_LOAD/PT_DYNAMIC segment bounds.

Detects .so files that would crash the Android bionic linker with
"Load CHECK 'did_read_' failed" — this occurs when a PT_LOAD segment's
p_offset + p_filesz exceeds the file size (the linker mmaps the file
and tries to read segment data past EOF).

Usage:
    python3 validate_elf.py <directory>   — validate all .so files in directory
    python3 validate_elf.py <file.so>     — validate a single file

Exit code: 0 if all valid, 1 if any file has errors.
"""
import struct
import sys
import os


# ELF constants
ELF_MAGIC = b'\x7fELF'
PT_LOAD = 1
PT_DYNAMIC = 2


def validate_elf(path):
    """Validate a single ELF file. Returns None if OK, or error string."""
    try:
        with open(path, 'rb') as f:
            data = f.read()
    except Exception as e:
        return f"cannot read: {e}"

    size = len(data)
    if size < 64:
        return f"too small for ELF64 header ({size} bytes)"

    # Check ELF magic
    if data[:4] != ELF_MAGIC:
        return f"bad ELF magic: {data[:4].hex()}"

    # Check EI_CLASS (byte 4): must be 2 for ELF64
    if data[4] != 2:
        return f"not ELF64 (class={data[4]})"

    # Check EI_DATA (byte 5): must be 1 for little-endian
    if data[5] != 1:
        return f"not little-endian (data={data[5]})"

    # ELF64 header fields (all little-endian on ARM64)
    # e_phoff: offset 32, 8 bytes
    # e_phentsize: offset 54, 2 bytes
    # e_phnum: offset 56, 2 bytes
    e_phoff = struct.unpack_from('<Q', data, 32)[0]
    e_phentsize = struct.unpack_from('<H', data, 54)[0]
    e_phnum = struct.unpack_from('<H', data, 56)[0]

    # Check phdr table bounds
    ph_table_end = e_phoff + e_phnum * e_phentsize
    if ph_table_end > size:
        return (f"phdr table overflows: offset={e_phoff} + "
                f"{e_phnum}x{e_phentsize} = {ph_table_end} > fileSize={size}")

    # Validate each program header entry
    # ELF64 Phdr layout (56 bytes each):
    #   0: p_type   (4 bytes, Elf64_Word)
    #   4: p_flags  (4 bytes, Elf64_Word)
    #   8: p_offset (8 bytes, Elf64_Off)
    #  16: p_vaddr  (8 bytes, Elf64_Addr)
    #  24: p_paddr  (8 bytes, Elf64_Addr)
    #  32: p_filesz (8 bytes, Elf64_Xword)
    #  40: p_memsz  (8 bytes, Elf64_Xword)
    #  48: p_align  (8 bytes, Elf64_Xword)
    for i in range(e_phnum):
        entry_off = e_phoff + i * e_phentsize
        if entry_off + e_phentsize > size:
            return f"phdr[{i}] entry extends past file (offset={entry_off})"

        p_type = struct.unpack_from('<I', data, entry_off)[0]
        p_offset = struct.unpack_from('<Q', data, entry_off + 8)[0]
        p_filesz = struct.unpack_from('<Q', data, entry_off + 32)[0]

        if p_type == PT_LOAD:
            seg_end = p_offset + p_filesz
            if seg_end > size:
                return (f"PT_LOAD[{i}] segment overflows: offset={p_offset} + "
                        f"filesz={p_filesz} = {seg_end} > fileSize={size}")
        elif p_type == PT_DYNAMIC:
            seg_end = p_offset + p_filesz
            if seg_end > size:
                return (f"PT_DYNAMIC[{i}] segment overflows: offset={p_offset} + "
                        f"filesz={p_filesz} = {seg_end} > fileSize={size}")

    return None


# ELF dynamic section constants
DT_NULL = 0
DT_NEEDED = 1
DT_SONAME = 14


def strip_soname(path):
    """Strip DT_SONAME from an ELF64 shared library by zeroing the
    d_un.d_val field of the DT_SONAME entry in the .dynamic section.

    Returns True if SONAME was stripped, False if not found or on error."""
    try:
        with open(path, 'r+b') as f:
            data = bytearray(f.read())
    except Exception:
        return False

    size = len(data)
    if size < 64 or data[:4] != ELF_MAGIC or data[4] != 2 or data[5] != 1:
        return False

    e_phoff = struct.unpack_from('<Q', data, 32)[0]
    e_phentsize = struct.unpack_from('<H', data, 54)[0]
    e_phnum = struct.unpack_from('<H', data, 56)[0]

    # Find PT_DYNAMIC segment
    dynamic_off = 0
    dynamic_size = 0
    for i in range(e_phnum):
        entry_off = e_phoff + i * e_phentsize
        if entry_off + 24 > size:
            break
        p_type = struct.unpack_from('<I', data, entry_off)[0]
        if p_type == PT_DYNAMIC:
            dynamic_off = struct.unpack_from('<Q', data, entry_off + 8)[0]
            dynamic_size = struct.unpack_from('<Q', data, entry_off + 32)[0]
            break

    if dynamic_off == 0 or dynamic_size < 16:
        return False

    # Scan .dynamic entries for DT_SONAME
    # ELF64 Dyn: d_tag (8 bytes) + d_un (8 bytes) = 16 bytes each
    modified = False
    pos = dynamic_off
    while pos + 16 <= min(dynamic_off + dynamic_size, size):
        d_tag = struct.unpack_from('<Q', data, pos)[0]
        if d_tag == DT_NULL:
            break
        if d_tag == DT_SONAME:
            # Zero out the d_val (bytes pos+8 to pos+15)
            for j in range(8):
                data[pos + 8 + j] = 0
            modified = True
        pos += 16

    if modified:
        with open(path, 'wb') as f:
            f.write(data)
    return modified


def main():
    if len(sys.argv) < 2:
        print("Usage: validate_elf.py <file_or_directory>")
        sys.exit(2)

    target = sys.argv[1]
    errors = []
    checked = 0

    if os.path.isfile(target):
        checked = 1
        err = validate_elf(target)
        if err:
            errors.append(f"{os.path.basename(target)}: {err}")
    elif os.path.isdir(target):
        for name in sorted(os.listdir(target)):
            if name.endswith('.so'):
                path = os.path.join(target, name)
                checked += 1
                err = validate_elf(path)
                if err:
                    errors.append(f"{name}: {err}")
    else:
        print(f"ERROR: {target} is not a file or directory")
        sys.exit(2)

    print(f"    Checked {checked} .so files")
    if errors:
        print(f"    ERROR: {len(errors)} files have ELF segment overflow:")
        for e in errors:
            print(f"      {e}")
        sys.exit(1)
    else:
        print(f"    [OK] All {checked} .so files pass PT_LOAD/PT_DYNAMIC validation")


if __name__ == '__main__':
    main()
