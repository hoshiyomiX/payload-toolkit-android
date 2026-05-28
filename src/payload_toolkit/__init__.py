"""
payload_toolkit — AOSP OTA payload.bin manipulation toolkit.

Designed for Android APK via zipapp (.pyz) + Termux Python.
Core uses only Python stdlib.  Optional: brotli (pip install).

Modes:
    info  — Parse and display payload.bin metadata
    dump  — Extract partition images from payload.bin
    gen   — Generate a payload.bin from .img files
    zip   — Generate a flashable OTA ZIP from .img files (AOSP payload.bin format)
    dd    — Generate a otaku-format flashable ZIP (dd-based flasher)
    sign  — Sign payload.bin with RSA key
"""

__version__ = "3.1.0"
__author__ = "PayloadToolkit"

# ---------------------------------------------------------------------------
# Progress callback — can be set from Kotlin via Chaquopy
# ---------------------------------------------------------------------------
_progress_callback = None


def set_progress_callback(callback):
    """Register a progress callback from Kotlin.

    The callback should accept three arguments:
        callback(current: int, total: int, message: str)

    Example (from Kotlin):
        payload_toolkit.set_progress_callback(lambda c, t, m: ...)
    """
    global _progress_callback
    _progress_callback = callback


def _report_progress(current, total, message="", percent=None):
    """Internal helper to invoke the progress callback if set.

    Also prints a machine-readable progress marker to stdout so the
    Kotlin exec-mode bridge can parse it in real time (line-by-line).
    Format:  __PROGRESS__<current>/<total>/<message>[/<percent>]

    When *percent* is provided (0-100), it is appended as a 4th field
    so the Kotlin side can use the exact compression percentage for the
    progress bar instead of the coarse integer current/total division.
    """
    # Stdout marker for Kotlin stdout-line parsing (exec mode)
    try:
        msg_clean = str(message).replace("/", "_").replace("\\", "_")
        if percent is not None:
            print(f"__PROGRESS__{int(current)}/{int(total)}/{msg_clean}/{int(percent)}", flush=True)
        else:
            print(f"__PROGRESS__{int(current)}/{int(total)}/{msg_clean}", flush=True)
    except Exception:
        pass

    # In-process callback (JNI mode or direct Python usage)
    if _progress_callback is not None:
        try:
            pct = int(percent) if percent is not None else (int(current) * 100 // max(int(total), 1))
            _progress_callback(int(current), int(total), str(message), pct)
        except TypeError:
            # Legacy callback with 3 args
            try:
                _progress_callback(int(current), int(total), str(message))
            except Exception:
                pass
        except Exception:
            pass  # Don't let progress reporting crash the operation


# ---------------------------------------------------------------------------
# Dependency health check
# ---------------------------------------------------------------------------

def check_dependencies():
    """Check availability of all optional and stdlib extension modules.

    Returns a dict with:
        'all_ok'       : bool  — True if all required modules are available
        'missing'      : list  — names of missing modules
        'optional'     : list  — names of missing optional modules
        'available'    : list  — names of available extension modules
        'compression'  : list  — compression algorithms currently supported
        'python_ver'   : str   — Python version string
        'platform'     : str   — platform identifier
    """
    import sys as _sys

    results = {
        "all_ok": True,
        "missing": [],
        "optional": [],
        "available": [],
        # Start with only truly universal algorithms; add others based on tests
        "compression": ["none", "gzip"],
        "python_ver": _sys.version,
        "platform": _sys.platform,
    }

    # --- hashlib: test actual functionality, not just importability ---
    # On some Android devices, the hashlib module loads but all algorithms
    # fail because _hashlib.so cannot find libcrypto.so.  A simple
    # __import__("hashlib") succeeds, but hashlib.sha256() would raise.
    # We must verify by calling an actual hash function.
    try:
        import hashlib as _hl
        _test_hash = _hl.sha256(b"probe").hexdigest()
        if len(_test_hash) == 64:
            results["available"].append("hashlib")
        else:
            raise ValueError("sha256 produced wrong length")
    except Exception:
        results["missing"].append("hashlib")
        results["all_ok"] = False

    # --- bz2: test actual compression, not just importability ---
    try:
        import bz2 as _bz2_mod
        _bz2_mod.compress(b"probe")
        results["available"].append("bz2")
        results["compression"].append("bzip2")
    except Exception:
        # bz2 is optional — bzip2 is just one of several compression options.
        # Don't set all_ok=False; the user can use gzip, xz, or brotli instead.
        results["missing"].append("bz2")

    # --- gzip: test actual compression ---
    # gzip is usually pure-Python + zlib C ext; test to be safe.
    try:
        import gzip as _gz_mod
        import io as _io
        _buf = _io.BytesIO()
        with _gz_mod.GzipFile(fileobj=_buf, mode="wb") as _f:
            _f.write(b"probe")
        results["available"].append("gzip")
    except Exception:
        # gzip failed — remove from compression list
        if "gzip" in results["compression"]:
            results["compression"].remove("gzip")
        results["optional"].append("gzip")

    # --- lzma: optional, test actual compression ---
    try:
        import lzma as _lzma_mod
        _lzma_mod.compress(b"probe", format=_lzma_mod.FORMAT_XZ)
        results["available"].append("lzma")
        results["compression"].append("xz")
    except Exception:
        results["optional"].append("lzma")

    # --- brotli: optional, test actual compression ---
    try:
        import brotli as _br_mod
        _br_mod.compress(b"probe")
        results["available"].append("brotli")
        results["compression"].append("brotli")
    except Exception:
        results["optional"].append("brotli")

    return results


def check_dependencies_text():
    """Return a concise, single-line dependency summary for the UI log."""
    info = check_dependencies()
    # Extract short Python version (e.g. "3.13.13" from full string)
    py_short = info['python_ver'].split()[0] if info['python_ver'] else "?"
    compression = ', '.join(info['compression'])

    if info["all_ok"]:
        return f"v{__version__} | Python {py_short} | {compression}"
    else:
        missing = ', '.join(info['missing']) if info['missing'] else ''
        return f"v{__version__} | Python {py_short} | {compression} | missing: {missing}"


def main(*args, **kwargs):
    """CLI-like main entry point.

    Can be called from Kotlin via Chaquopy or from a Python REPL.
    Accepts a dict of parameters (Chaquopy style) or keyword arguments.

    Parameters
    ----------
    mode : str
        One of: "info", "dump", "gen", "zip", "dd", "sign"
    **kwargs :
        Mode-specific parameters forwarded to the mode handler.

    Returns
    -------
    dict
        {'success': bool, 'output': str, ...}
    """
    params = {}
    if args and isinstance(args[0], dict):
        params = args[0]
    else:
        params = kwargs

    mode = str(params.get("mode", "")).lower().strip()

    supported = ("info", "dump", "gen", "zip", "dd", "sign")
    if mode not in supported:
        return {
            "success": False,
            "error": f"Unknown mode '{mode}'. Supported modes: {', '.join(supported)}",
        }

    try:
        mod = __import__(f"payload_toolkit.modes.{mode}", fromlist=["run"])
        result = mod.run(params)
        if not isinstance(result, dict):
            result = {"success": True, "output": str(result)}
        return result
    except Exception as exc:
        return {"success": False, "error": str(exc)}
