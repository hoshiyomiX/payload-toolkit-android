#!/usr/bin/env python3
"""validate_elf.py — Validate ELF files: phdr table + PT_LOAD/PT_DYNAMIC segment bounds.

Supports both ELF32 (armeabi-v7a) and ELF64 (arm64-v8a) formats.

Detects .so files that would crash the Android bionic linker with
"Load CHECK 'did_read_' failed" — this occurs when a PT_LOAD segment's
p_offset + p_filesz exceeds the file size (the linker mmaps the file
and tries to read segment data past EOF).

Usage:
    python3 validate_elf.py <directory>   — validate all .so files in directory
    python3 validate_elf.py <file.so>     — validate a single file
    python3 validate_elf.py --audit-needed <directory> — check for versioned DT_NEEDED

Exit code: 0 if all valid, 1 if any file has errors.
"""
import struct
import sys
import os


# ELF constants
ELF_MAGIC = b'\x7fELF'
ELFCLASS32 = 1
ELFCLASS64 = 2
ELFDATA2LSB = 1  # Little-endian
PT_LOAD = 1
PT_DYNAMIC = 2

# Dynamic section tags
DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10
DT_SONAME = 14


def _get_elf_class(data):
    """Return ELF class (ELFCLASS32 or ELFCLASS64) or None if invalid."""
    if len(data) < 16 or data[:4] != ELF_MAGIC or data[5] != ELFDATA2LSB:
        return None
    cls = data[4]
    if cls not in (ELFCLASS32, ELFCLASS64):
        return None
    return cls


def _parse_ehdr(data, elf_class):
    """Parse ELF header fields. Returns (e_phoff, e_phentsize, e_phnum) or None."""
    if elf_class == ELFCLASS64:
        if len(data) < 64:
            return None
        e_phoff = struct.unpack_from('<Q', data, 32)[0]
        e_phentsize = struct.unpack_from('<H', data, 54)[0]
        e_phnum = struct.unpack_from('<H', data, 56)[0]
    else:  # ELFCLASS32
        if len(data) < 52:
            return None
        e_phoff = struct.unpack_from('<I', data, 28)[0]
        e_phentsize = struct.unpack_from('<H', data, 42)[0]
        e_phnum = struct.unpack_from('<H', data, 44)[0]
    return e_phoff, e_phentsize, e_phnum


def _parse_phdr(data, elf_class, entry_off):
    """Parse a single program header entry. Returns dict or None."""
    size = len(data)
    if elf_class == ELFCLASS64:
        # ELF64 Phdr layout (56 bytes each):
        #   0: p_type   (4B)   4: p_flags  (4B)
        #   8: p_offset (8B)  16: p_vaddr  (8B)  24: p_paddr (8B)
        #  32: p_filesz (8B)  40: p_memsz  (8B)  48: p_align (8B)
        if entry_off + 56 > size:
            return None
        p_type = struct.unpack_from('<I', data, entry_off)[0]
        p_offset = struct.unpack_from('<Q', data, entry_off + 8)[0]
        p_filesz = struct.unpack_from('<Q', data, entry_off + 32)[0]
    else:  # ELFCLASS32
        # ELF32 Phdr layout (32 bytes each):
        #   0: p_type   (4B)   4: p_offset (4B)   8: p_vaddr (4B)
        #  12: p_paddr  (4B)  16: p_filesz (4B)  20: p_memsz (4B)
        #  24: p_flags  (4B)  28: p_align  (4B)
        if entry_off + 32 > size:
            return None
        p_type = struct.unpack_from('<I', data, entry_off)[0]
        p_offset = struct.unpack_from('<I', data, entry_off + 4)[0]
        p_filesz = struct.unpack_from('<I', data, entry_off + 16)[0]
    return {'p_type': p_type, 'p_offset': p_offset, 'p_filesz': p_filesz}


def _find_dynamic_and_strtab(data):
    """Find PT_DYNAMIC, DT_STRTAB, DT_STRSZ in an ELF binary (32 or 64-bit).

    Returns (dynamic_off, dynamic_size, strtab_off, strsz) or None.
    """
    elf_class = _get_elf_class(data)
    if elf_class is None:
        return None

    hdr = _parse_ehdr(data, elf_class)
    if hdr is None:
        return None
    e_phoff, e_phentsize, e_phnum = hdr

    # Find PT_DYNAMIC segment
    size = len(data)
    dynamic_off = dynamic_size = 0
    for i in range(e_phnum):
        entry_off = e_phoff + i * e_phentsize
        phdr = _parse_phdr(data, elf_class, entry_off)
        if phdr is None:
            break
        if phdr['p_type'] == PT_DYNAMIC:
            dynamic_off = phdr['p_offset']
            dynamic_size = phdr['p_filesz']
            break

    if dynamic_off == 0 or dynamic_size < 16:
        return None

    # Dynamic entry size depends on ELF class:
    #   ELF64: 16 bytes (d_tag: 8B, d_val: 8B)
    #   ELF32: 8 bytes  (d_tag: 4B, d_val: 4B)
    dyn_entry_size = 16 if elf_class == ELFCLASS64 else 8
    d_tag_fmt = '<Q' if elf_class == ELFCLASS64 else '<I'
    d_val_fmt = '<Q' if elf_class == ELFCLASS64 else '<I'
    d_val_off_in_entry = 8 if elf_class == ELFCLASS64 else 4

    # Scan .dynamic for DT_STRTAB and DT_STRSZ
    strtab_off = strsz = 0
    pos = dynamic_off
    end = min(dynamic_off + dynamic_size, size)
    while pos + dyn_entry_size <= end:
        d_tag = struct.unpack_from(d_tag_fmt, data, pos)[0]
        d_val = struct.unpack_from(d_val_fmt, data, pos + d_val_off_in_entry)[0]
        if d_tag == DT_NULL:
            break
        if d_tag == DT_STRTAB:
            strtab_off = d_val
        elif d_tag == DT_STRSZ:
            strsz = d_val
        pos += dyn_entry_size

    if strtab_off == 0 or strsz == 0:
        return None

    return dynamic_off, dynamic_size, strtab_off, strsz


def validate_elf(path):
    """Validate a single ELF file. Returns None if OK, or error string."""
    try:
        with open(path, 'rb') as f:
            data = f.read()
    except Exception as e:
        return f"cannot read: {e}"

    size = len(data)
    if size < 52:
        return f"too small for ELF header ({size} bytes)"

    # Check ELF magic
    if data[:4] != ELF_MAGIC:
        return f"bad ELF magic: {data[:4].hex()}"

    # Check EI_DATA (byte 5): must be 1 for little-endian
    if data[5] != ELFDATA2LSB:
        return f"not little-endian (data={data[5]})"

    # Detect ELF class
    elf_class = _get_elf_class(data)
    if elf_class is None:
        return f"unknown ELF class (class={data[4]})"

    class_name = "ELF32" if elf_class == ELFCLASS32 else "ELF64"
    min_size = 52 if elf_class == ELFCLASS32 else 64
    if size < min_size:
        return f"too small for {class_name} header ({size} bytes)"

    # Parse ELF header
    hdr = _parse_ehdr(data, elf_class)
    if hdr is None:
        return f"failed to parse {class_name} header"
    e_phoff, e_phentsize, e_phnum = hdr

    # Check phdr table bounds
    ph_table_end = e_phoff + e_phnum * e_phentsize
    if ph_table_end > size:
        return (f"phdr table overflows: offset={e_phoff} + "
                f"{e_phnum}x{e_phentsize} = {ph_table_end} > fileSize={size}")

    # Validate each program header entry
    for i in range(e_phnum):
        entry_off = e_phoff + i * e_phentsize
        phdr = _parse_phdr(data, elf_class, entry_off)
        if phdr is None:
            return f"phdr[{i}] entry extends past file (offset={entry_off})"

        p_type = phdr['p_type']
        p_offset = phdr['p_offset']
        p_filesz = phdr['p_filesz']

        if p_type in (PT_LOAD, PT_DYNAMIC):
            seg_end = p_offset + p_filesz
            if seg_end > size:
                seg_name = "PT_LOAD" if p_type == PT_LOAD else "PT_DYNAMIC"
                return (f"{seg_name}[{i}] segment overflows: offset={p_offset} + "
                        f"filesz={p_filesz} = {seg_end} > fileSize={size}")

    return None


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


def _iter_dynamic_entries(data, dynamic_off, dynamic_size):
    """Iterate over .dynamic entries, yielding (d_tag, d_val) pairs.

    Handles both ELF32 and ELF64 entry sizes.
    """
    elf_class = _get_elf_class(data)
    if elf_class is None:
        return

    dyn_entry_size = 16 if elf_class == ELFCLASS64 else 8
    d_tag_fmt = '<Q' if elf_class == ELFCLASS64 else '<I'
    d_val_fmt = '<Q' if elf_class == ELFCLASS64 else '<I'
    d_val_off = 8 if elf_class == ELFCLASS64 else 4

    pos = dynamic_off
    end = min(dynamic_off + dynamic_size, len(data))
    while pos + dyn_entry_size <= end:
        d_tag = struct.unpack_from(d_tag_fmt, data, pos)[0]
        d_val = struct.unpack_from(d_val_fmt, data, pos + d_val_off)[0]
        yield d_tag, d_val
        if d_tag == DT_NULL:
            break
        pos += dyn_entry_size


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
    for d_tag, d_val in _iter_dynamic_entries(data, dynamic_off, dynamic_size):
        if d_tag == DT_SONAME:
            str_idx = int(d_val)
            # d_val is offset INTO .dynstr (strtab-relative).
            # Actual file offset = strtab_off + str_idx.
            if str_idx >= strsz:
                return False
            str_off = strtab_off + str_idx
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


def get_dt_needed_list(data):
    """Parse DT_NEEDED entries directly from ELF binary data.

    Returns list of needed library names.  Uses struct-based parsing of
    the .dynamic section and .dynstr — no external tools required.
    This is more reliable than `patchelf --print-needed` because it
    works even on files that patchelf cannot read after section growth.

    Supports both ELF32 and ELF64 formats.
    """
    result = _find_dynamic_and_strtab(data)
    if result is None:
        return []
    dynamic_off, dynamic_size, strtab_off, strsz = result

    needed = []
    for d_tag, d_val in _iter_dynamic_entries(data, dynamic_off, dynamic_size):
        if d_tag == DT_NEEDED:
            # d_val is offset INTO .dynstr (strtab-relative, not a vaddr).
            # The actual file offset = strtab_off + d_val.
            # NOTE: This assumes DT_STRTAB vaddr == file offset, which is
            # true for Termux libs (first PT_LOAD: p_vaddr=0).
            str_idx = int(d_val)
            if str_idx >= strsz:
                continue
            str_file_off = strtab_off + str_idx
            try:
                str_end = data.index(0, str_file_off, strtab_off + strsz)
                name = data[str_file_off:str_end].decode('ascii', errors='replace')
                needed.append(name)
            except (ValueError, IndexError):
                pass
    return needed


def fix_needed_all(path, jni_dir):
    """Patch ALL versioned DT_NEEDED entries to unversioned.

    Parses DT_NEEDED directly from the ELF binary (no patchelf dependency)
    and patches strings in-place in .dynstr.  This modifies the ORIGINAL
    string that both DT_NEEDED and .gnu.version_r reference, so there
    is no verneed mismatch after patching.

    Patches unconditionally — the prepare script guarantees all needed
    shared libs exist as unversioned .so files before this runs.  The
    previous file-existence check caused silent no-ops on arm32 where
    libcrypto.so.3 existed in .dynstr but the check missed it.

    Returns count of patched entries.
    """
    try:
        with open(path, 'r+b') as f:
            data = bytearray(f.read())
    except Exception:
        return 0

    result = _find_dynamic_and_strtab(data)
    if result is None:
        return 0
    dynamic_off, dynamic_size, strtab_off, strsz = result

    # Parse DT_NEEDED entries directly from binary
    needed_list = get_dt_needed_list(data)
    if not needed_list:
        return 0

    patched = 0
    for needed_name in needed_list:
        if '.so.' not in needed_name:
            continue
        unversioned = needed_name.split('.so.')[0] + '.so'
        if len(unversioned) <= len(needed_name):
            if _str_replace_in_place(data, strtab_off, strsz, needed_name, unversioned):
                patched += 1

    if patched > 0:
        with open(path, 'wb') as f:
            f.write(data)

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


# -- DT_NEEDED audit mode ----------------------------------------------
# Usage: python3 validate_elf.py --audit-needed <directory>
# Scans all .so files and lists any versioned DT_NEEDED entries.
if __name__ == '__main__' and len(sys.argv) >= 3 and sys.argv[1] == '--audit-needed':
    audit_dir = sys.argv[2]
    found = 0
    for name in sorted(os.listdir(audit_dir)):
        if not name.endswith('.so'):
            continue
        path = os.path.join(audit_dir, name)
        try:
            with open(path, 'rb') as f:
                data = bytearray(f.read())
        except Exception:
            continue
        needed_list = get_dt_needed_list(data)
        for n in needed_list:
            if '.so.' in n:
                print(f"    VERSIONED: {name} needs {n}")
                found += 1
    if found == 0:
        print(f"    [OK] No versioned DT_NEEDED entries found")
    else:
        print(f"    WARNING: {found} versioned DT_NEEDED entries remain")
    sys.exit(0 if found == 0 else 1)


if __name__ == '__main__':
    main()
