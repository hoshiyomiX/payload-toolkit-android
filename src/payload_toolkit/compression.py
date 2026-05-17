"""
compression.py — Compression / decompression for AOSP payload.bin operations.

Supported algorithms (all via Python stdlib except brotli):
    none    — pass-through (no compression)
    bzip2   — bz2 module
    gzip    — gzip module
    xz      — lzma module (XZ format)
    brotli  — optional 'brotli' package (raises RuntimeError if unavailable)

The compression algorithm is determined by the InstallOperation type:
    REPLACE / REPLACE_BZ  →  "none" (raw data, though REPLACE_BZ historically
                             could mean bzip2, AOSP uses it as a no-op flag)
    REPLACE_XZ            →  "xz"
    PUIGZIP (14)          →  "gzip"
    BROTLI_BZ (23)        →  "brotli"
    ZERO (21)             →  "none" (zero-fill, no data to decompress)
    DISCARD (22)          →  "none" (no data)
"""

import bz2
import gzip
import io
import sys

# Import lzma only if available; requires liblzma on the system.
# On Termux: liblzma is included with 'pkg install python'.
try:
    import lzma as _lzma_mod
    _HAS_LZMA = True
except ImportError:
    _lzma_mod = None
    _HAS_LZMA = False

# Import brotli only if available; requires 'pip install brotli'.
try:
    import brotli as _brotli_mod
    _HAS_BROTLI = True
except ImportError:
    _brotli_mod = None
    _HAS_BROTLI = False

# ---------------------------------------------------------------------------
# Public API
# ---------------------------------------------------------------------------

# Canonical algorithm names used throughout the toolkit.
ALG_NONE = "none"
ALG_BZIP2 = "bzip2"
ALG_GZIP = "gzip"
ALG_XZ = "xz"
ALG_BROTLI = "brotli"
ALG_AUTO = "auto"

ALL_ALGORITHMS = (ALG_NONE, ALG_BZIP2, ALG_GZIP, ALG_XZ, ALG_BROTLI)


def compress(data, algorithm="gzip"):
    """Compress *data* with the specified algorithm.

    Parameters
    ----------
    data : bytes
        Raw data to compress.
    algorithm : str
        One of "none", "bzip2", "gzip", "xz", "brotli".

    Returns
    -------
    bytes
        Compressed data (or *data* unchanged for "none").

    Raises
    ------
    RuntimeError
        If brotli is requested but the brotli module is not installed.
    ValueError
        If *algorithm* is not recognised.
    """
    if not isinstance(data, bytes):
        data = bytes(data)

    alg = _normalise(algorithm)

    if alg == ALG_NONE:
        return data

    if alg == ALG_BZIP2:
        return bz2.compress(data, compresslevel=9)

    if alg == ALG_GZIP:
        buf = io.BytesIO()
        with gzip.GzipFile(fileobj=buf, mode="wb", compresslevel=9, mtime=0) as f:
            f.write(data)
        return buf.getvalue()

    if alg == ALG_XZ:
        if not _HAS_LZMA:
            raise RuntimeError(
                "XZ compression requires the 'lzma' module (liblzma).  "
                "On Termux: pkg install python  (includes liblzma).  "
                "On Linux: apt install liblzma-dev && reinstall Python."
            )
        return _lzma_mod.compress(data, format=_lzma_mod.FORMAT_XZ, preset=9 | _lzma_mod.PRESET_EXTREME)

    if alg == ALG_BROTLI:
        if not _HAS_BROTLI:
            raise RuntimeError(
                "brotli compression requires the 'brotli' Python package.  "
                "Install it via pip:  pip install brotli"
            )
        return _brotli_mod.compress(data, quality=11)

    raise ValueError(f"Unknown compression algorithm: {algorithm!r}")


def decompress(data, algorithm="auto"):
    """Decompress *data*.

    Parameters
    ----------
    data : bytes
        Compressed (or raw) data.
    algorithm : str
        One of "none", "bzip2", "gzip", "xz", "brotli", "auto".
        "auto" attempts to detect the format from magic bytes.

    Returns
    -------
    bytes
        Decompressed data.

    Raises
    ------
    RuntimeError
        If brotli data is encountered but the brotli module is not installed.
    ValueError
        If the algorithm cannot be detected or is not recognised.
    """
    if not isinstance(data, bytes):
        data = bytes(data)

    alg = _normalise(algorithm)

    if alg == ALG_AUTO:
        alg = _detect_from_data(data)

    if alg == ALG_NONE:
        return data

    if alg == ALG_BZIP2:
        return bz2.decompress(data)

    if alg == ALG_GZIP:
        buf = io.BytesIO(data)
        with gzip.GzipFile(fileobj=buf, mode="rb") as f:
            return f.read()

    if alg == ALG_XZ:
        if not _HAS_LZMA:
            raise RuntimeError(
                "XZ decompression requires the 'lzma' module (liblzma).  "
                "On Termux: pkg install python  (includes liblzma)."
            )
        return _lzma_mod.decompress(data, format=_lzma_mod.FORMAT_XZ)

    if alg == ALG_BROTLI:
        if not _HAS_BROTLI:
            raise RuntimeError(
                "brotli decompression requires the 'brotli' Python package.  "
                "Install it via pip:  pip install brotli"
            )
        return _brotli_mod.decompress(data)

    raise ValueError(f"Unknown compression algorithm: {algorithm!r}")


def detect_compression(operation_type):
    """Map an InstallOperation type enum value to a compression algorithm string.

    Parameters
    ----------
    operation_type : int
        The InstallOperation.type field (e.g. 0, 8, 12, 14, 23).

    Returns
    -------
    str
        One of "none", "gzip", "xz", "brotli".
    """
    # REPLACE = 0 → no compression
    if operation_type == 0:
        return ALG_NONE

    # REPLACE_XZ = 8 → XZ
    if operation_type == 8:
        return ALG_XZ

    # REPLACE_BZ = 12 → In AOSP this is often used as REPLACE (no compression)
    # Historically could mean bzip2 but in practice payload.bin stores raw data.
    if operation_type == 12:
        return ALG_NONE

    # PUIGZIP = 14 → gzip
    if operation_type == 14:
        return ALG_GZIP

    # BROTLI_BSDIFF = 23 → brotli
    if operation_type == 23:
        return ALG_BROTLI

    # ZERO / DISCARD → no data
    if operation_type in (21, 22):
        return ALG_NONE

    # Default: no compression
    return ALG_NONE


def operation_type_for_algorithm(algorithm):
    """Map a compression algorithm name to the recommended InstallOperation type.

    This is the inverse of :func:`detect_compression`.

    Returns
    -------
    int
        InstallOperation type enum value.
    """
    alg = _normalise(algorithm)
    mapping = {
        ALG_NONE: 0,       # REPLACE
        ALG_BZIP2: 0,      # REPLACE (no standard AOSP type for bzip2;
                           # data is bzip2-compressed but stored under REPLACE type;
                           # dump side uses auto-detect from magic bytes)
        ALG_GZIP: 14,      # PUIGZIP
        ALG_XZ: 8,         # REPLACE_XZ
        ALG_BROTLI: 23,    # BROTLI_BZ
    }
    return mapping.get(alg, 0)


def is_brotli_available():
    """Return True if the brotli module is importable."""
    return _HAS_BROTLI


def is_lzma_available():
    """Return True if the lzma module is importable."""
    return _HAS_LZMA


# ---------------------------------------------------------------------------
# Internal helpers
# ---------------------------------------------------------------------------

def _normalise(algorithm):
    """Normalise an algorithm name to lowercase, handling common aliases."""
    if algorithm is None:
        return ALG_NONE
    alg = str(algorithm).lower().strip()
    # Aliases
    aliases = {
        "": ALG_NONE,
        "raw": ALG_NONE,
        "none": ALG_NONE,
        "bz2": ALG_BZIP2,
        "bzip2": ALG_BZIP2,
        "gz": ALG_GZIP,
        "gzip": ALG_GZIP,
        "lzma": ALG_XZ,
        "xz": ALG_XZ,
        "br": ALG_BROTLI,
        "brotli": ALG_BROTLI,
    }
    return aliases.get(alg, alg)


def _detect_from_data(data):
    """Attempt to detect the compression format from magic bytes.

    Returns a canonical algorithm string.
    """
    if not data or len(data) < 4:
        return ALG_NONE

    # Detect format from magic bytes.
    # Order matters: check specific magic patterns first.

    # Gzip magic: 1F 8B
    if data[:2] == b"\x1F\x8B":
        return ALG_GZIP

    # Bzip2 magic: 42 5A 68 ("BZh")
    if data[:3] == b"\x42\x5A\x68":
        return ALG_BZIP2

    # XZ magic: FD 37 7A 58 5A 00 (only if lzma module is available)
    if _HAS_LZMA and len(data) >= 6:
        if data[:6] == b"\xFD\x37\x7A\x58\x5A\x00":
            return ALG_XZ

    # Brotli: no reliable magic, try trial decompression if available.
    if _HAS_BROTLI and len(data) >= 3:
        try:
            _brotli_mod.decompress(data)
            return ALG_BROTLI
        except Exception:
            pass

    return ALG_NONE
