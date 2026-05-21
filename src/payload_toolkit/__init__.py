"""
payload_toolkit — AOSP OTA payload.bin manipulation toolkit.

Designed for Android APK via zipapp (.pyz) + Termux Python.
Core uses only Python stdlib.  Optional: brotli (pip install).

Modes:
    info  — Parse and display payload.bin metadata
    dump  — Extract partition images from payload.bin
    gen   — Generate a payload.bin from .img files
    zip   — Generate a flashable OTA ZIP from .img files (AOSP payload.bin format)
    dd    — Generate a ddbundle-format flashable ZIP (dd-based flasher)
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


def _report_progress(current, total, message=""):
    """Internal helper to invoke the progress callback if set.

    Also prints a machine-readable progress marker to stdout so the
    Kotlin exec-mode bridge can parse it in real time (line-by-line).
    Format:  __PROGRESS__<current>/<total>/<message>
    """
    # Stdout marker for Kotlin stdout-line parsing (exec mode)
    try:
        msg_clean = str(message).replace("/", "_").replace("\\", "_")
        print(f"__PROGRESS__{int(current)}/{int(total)}/{msg_clean}", flush=True)
    except Exception:
        pass

    # In-process callback (JNI mode or direct Python usage)
    if _progress_callback is not None:
        try:
            _progress_callback(int(current), int(total), str(message))
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
        "compression": ["none", "gzip", "bzip2"],
        "python_ver": _sys.version,
        "platform": _sys.platform,
    }

    # Required stdlib C extensions
    required = {
        "hashlib": "SHA-256 hashing (stdlib C ext)",
        "bz2": "bzip2 compression (stdlib C ext)",
    }
    # Optional stdlib / third-party extensions
    optional = {
        "lzma": "XZ/LZMA compression (stdlib C ext, needs liblzma)",
        "brotli": "brotli compression (pip install brotli)",
    }

    for mod_name, _desc in required.items():
        try:
            __import__(mod_name)
            results["available"].append(mod_name)
        except ImportError:
            results["missing"].append(mod_name)
            results["all_ok"] = False

    for mod_name, _desc in optional.items():
        try:
            __import__(mod_name)
            results["available"].append(mod_name)
            if mod_name == "lzma":
                results["compression"].append("xz")
            elif mod_name == "brotli":
                results["compression"].append("brotli")
        except ImportError:
            results["optional"].append(mod_name)

    return results


def check_dependencies_text():
    """Return a human-readable dependency report as a string."""
    info = check_dependencies()
    lines = []
    lines.append("payload_toolkit v%s" % __version__)
    lines.append("Python: %s" % info["python_ver"].split("\n")[0])
    lines.append("Platform: %s" % info["platform"])
    lines.append("")

    if info["missing"]:
        lines.append("MISSING (required):")
        for m in info["missing"]:
            lines.append("  [!] %s" % m)
        lines.append("")

    if info["optional"]:
        lines.append("MISSING (optional):")
        for m in info["optional"]:
            lines.append("  [-] %s" % m)
        lines.append("")

    lines.append("Available: %s" % ", ".join(info["available"]) if info["available"] else "Available: (none)")
    lines.append("Compression: %s" % ", ".join(info["compression"]))
    lines.append("")

    if info["all_ok"]:
        lines.append("Status: OK - all required dependencies available")
    else:
        lines.append("Status: INCOMPLETE - some required modules missing")
        lines.append("")
        lines.append("Fix: On Termux run:  pkg install python")

    return "\n".join(lines)


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
