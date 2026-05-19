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
DT_STRTAB = 5
DT_STRSZ = 10
DT_SONAME = 14


def _find_dynamic_and_strtab(data):
    """Find PT_DYNAMIC, DT_STRTAB, DT_STRSZ in an ELF64 binary.
    Returns (dynamic_off, dynamic_size, strtab_off, strsz) or None."""
    size = len(data)
    if size < 64 or data[:4] != ELF_MAGIC or data[4] != 2 or data[5] != 1:
        return None

    e_phoff = struct.unpack_from('<Q', data, 32)[0]
    e_phentsize = struct.unpack_from('<H', data, 54)[0]
    e_phnum = struct.unpack_from('<H', data, 56)[0]

    dynamic_off = dynamic_size = 0
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
        return None

    # Scan .dynamic for DT_STRTAB and DT_STRSZ
    strtab_off = strsz = 0
    pos = dynamic_off
    while pos + 16 <= min(dynamic_off + dynamic_size, size):
        d_tag = struct.unpack_from('<Q', data, pos)[0]
        d_val = struct.unpack_from('<Q', data, pos + 8)[0]
        if d_tag == DT_NULL:
            break
        if d_tag == DT_STRTAB:
            strtab_off = d_val
        elif d_tag == DT_STRSZ:
            strsz = d_val
        pos += 16

    if strtab_off == 0 or strsz == 0:
        return None

    return dynamic_off, dynamic_size, strtab_off, strsz


def _str_replace_in_place(data, strtab_off, strsz, old_str, new_str):
    """Replace a string in the .dynstr table in-place.
    new_str must be <= len(old_str). Returns True if replaced."""
    if len(new_str) > len(old_str):
        return False
    # Search for old_str in .dynstr region
    search_area = data[strtab_off:strtab_off + strsz]
    # Split into null-terminated strings and find the match
    idx = search_area.find(old_str.encode())
    while idx >= 0:
        # Verify it's a complete null-terminated string
        end = idx + len(old_str)
        if end <= len(search_area) and (end == len(search_area) or search_area[end] == 0):
            # Check it starts at a string boundary (preceding byte is 0 or idx==0)
            if idx == 0 or search_area[idx - 1] == 0:
                # Replace in-place: new_str + null padding
                abs_idx = strtab_off + idx
                new_bytes = new_str.encode() + b'\x00' * (len(old_str) - len(new_str))
                for j, b in enumerate(new_bytes):
                    data[abs_idx + j] = b
                return True
        idx = search_area.find(old_str.encode(), idx + 1)
    return False


def fix_soname(path, desired_soname):
    """Set DT_SONAME to desired_soname (typically the filename).

    Replaces the SONAME string in .dynstr in-place.
    Returns True if SONAME was set, False if not found or on error.
    """
    try:
        with open(path, 'r+b') as f:
            data = bytearray(f.read())
    except Exception:
        return False

    result = _find_dynamic_and_strtab(data)
    if result is None:
        return False
    dynamic_off, dynamic_size, strtab_off, strsz = result

    # Find DT_SONAME entry to get current SONAME string
    pos = dynamic_off
    while pos + 16 <= min(dynamic_off + dynamic_size, len(data)):
        d_tag = struct.unpack_from('<Q', data, pos)[0]
        if d_tag == DT_NULL:
            break
        if d_tag == DT_SONAME:
            str_off = struct.unpack_from('<Q', data, pos + 8)[0]
            # Read current SONAME string
            end = data.index(0, str_off)
            current = data[str_off:end].decode('ascii', errors='replace')
            if current == desired_soname:
                return False  # Already correct
            # Replace in .dynstr
            if _str_replace_in_place(data, strtab_off, strsz, current, desired_soname):
                with open(path, 'wb') as f:
                    f.write(data)
                return True
            return False
        pos += 16
    return False


def fix_needed(path, old_name, new_name):
    """Replace a DT_NEEDED string in .dynstr (versioned -> unversioned).
    Returns True if replaced, False if not found or on error.
    """
    try:
        with open(path, 'r+b') as f:
            data = bytearray(f.read())
    except Exception:
        return False

    result = _find_dynamic_and_strtab(data)
    if result is None:
        return False
    dynamic_off, dynamic_size, strtab_off, strsz = result

    if _str_replace_in_place(data, strtab_off, strsz, old_name, new_name):
        with open(path, 'wb') as f:
            f.write(data)
        return True
    return False


def fix_needed_all(path, jni_dir):
    """Patch ALL versioned DT_NEEDED entries to unversioned.
    Uses patchelf --print-needed to discover entries, then binary-patches
    .dynstr for files too small for patchelf (< 8KB).

    Returns count of patched entries.
    """
    import subprocess
    try:
        result = subprocess.run(
            ['patchelf', '--print-needed', path],
            capture_output=True, text=True, timeout=10)
        needed_list = [n for n in result.stdout.strip().split('\n') if n and n.endswith('.so')]
    except Exception:
        return 0

    patched = 0
    for needed in needed_list:
        if '.so.' not in needed:
            continue
        unversioned = needed.split('.so.')[0] + '.so'
        if os.path.isfile(os.path.join(jni_dir, unversioned)):
            if fix_needed(path, needed, unversioned):
                patched += 1
    return patched


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
