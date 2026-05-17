"""
payload_toolkit — AOSP OTA payload.bin manipulation toolkit.

Designed for embedding in Android APK via Chaquopy (CPython 3.12).
Uses only Python stdlib — no external dependencies.

Modes:
    info  — Parse and display payload.bin metadata
    dump  — Extract partition images from payload.bin
    gen   — Generate a payload.bin from .img files
    zip   — Generate a flashable OTA ZIP from .img files
    sign  — Sign payload.bin with RSA key
"""

__version__ = "2.0.0"
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
    """Internal helper to invoke the progress callback if set."""
    if _progress_callback is not None:
        try:
            _progress_callback(int(current), int(total), str(message))
        except Exception:
            pass  # Don't let progress reporting crash the operation


def main(*args, **kwargs):
    """CLI-like main entry point.

    Can be called from Kotlin via Chaquopy or from a Python REPL.
    Accepts a dict of parameters (Chaquopy style) or keyword arguments.

    Parameters
    ----------
    mode : str
        One of: "info", "dump", "gen", "zip", "sign"
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

    supported = ("info", "dump", "gen", "zip", "sign")
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
