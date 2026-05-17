"""
_bootstrap.py — Runtime bridge: extracts .pyz from assets, adds to sys.path.
Called from PythonBridge.kt after Chaquopy initialization.
"""
import sys
import os

def setup(pyz_path):
    """Add payload_toolkit.pyz to sys.path so 'import payload_toolkit' works.
    
    Args:
        pyz_path: Absolute path to the extracted .pyz file.
    
    Returns:
        dict with 'success', 'version', 'path' keys.
    """
    if not os.path.isfile(pyz_path):
        return {"success": False, "error": f"pyz not found: {pyz_path}"}
    
    # Add to sys.path (Python's zipimport handles .pyz files)
    if pyz_path not in sys.path:
        sys.path.insert(0, pyz_path)
    
    # Verify import works
    try:
        import payload_toolkit
        return {
            "success": True,
            "version": payload_toolkit.__version__,
            "path": pyz_path
        }
    except ImportError as e:
        return {"success": False, "error": f"import failed: {e}"}
