"""
modes/gen.py — Generate a payload.bin from partition .img files.

Called from Kotlin via Chaquopy:
    payload_toolkit.modes.gen.run(
        images={"boot": "/path/to/boot.img", "system": "/path/to/system.img"},
        compress="bzip2",
        output_path="/path/to/output.bin"
    )

Returns dict: {'success': True/False, 'output': str}
"""

import os
import time

from .. import _report_progress
from ..payload import write_payload, verify_payload, human_size


def _parse_args(args, kwargs):
    """Normalise Chaquopy dict-arg vs direct keyword-arg calling convention."""
    if args and isinstance(args[0], dict):
        return args[0]
    return kwargs


def run(*args, **kwargs):
    """Generate a partial payload.bin from .img files.

    Parameters (via dict or kwargs)
    -------------------------------
    images      : dict  — {partition_name: image_file_path, ...}
    compress    : str   — Compression algorithm ("none", "bzip2", "gzip", "xz", "brotli")
    output_path : str   — Path for output payload.bin
    block_size  : int   — Block size in bytes (default 4096)

    Returns
    -------
    dict
        success : bool
        output  : str
    """
    params = _parse_args(args, kwargs)
    images = params.get("images", {})
    compress_alg = str(params.get("compress", "none"))
    output_path = str(params.get("output_path", ""))
    block_size = int(params.get("block_size", 4096))

    lines = []
    t0 = time.time()

    # ── Validate inputs ──
    if not images:
        return {"success": False, "output": "[!] Error: no images specified",
                "error": "no images specified"}

    if not output_path:
        return {"success": False, "output": "[!] Error: output_path is required",
                "error": "output_path is required"}

    # Normalise images dict (Chaquopy may pass string keys/values)
    if isinstance(images, dict):
        images = {str(k): str(v) for k, v in images.items()}
    else:
        return {"success": False, "output": "[!] Error: images must be a dict",
                "error": "images must be a dict"}

    # Validate all image files exist
    valid_images = []
    for name, path in images.items():
        if not os.path.isfile(path):
            lines.append(f"[!] Image not found: {name} -> {path}")
            return {"success": False,
                    "output": "\n".join(lines),
                    "error": f"image file not found: {path}"}
        valid_images.append({
            "name": name,
            "image_path": path,
            "compress": compress_alg,
        })

    try:
        # ── Phase 1: Generate payload.bin ──
        lines.append(f"═══ PAYLOAD.BIN GENERATION ═══")
        lines.append(f"Output: {output_path}")
        lines.append(f"Compression: {compress_alg}")
        lines.append(f"Block size: {block_size}")
        lines.append(f"Partitions: {len(valid_images)}")
        lines.append("")

        result = write_payload(
            output_path=output_path,
            partitions_data=valid_images,
            block_size=block_size,
            minor_version=0,
        )

        lines.append(result.get("output", ""))

        if not result["success"]:
            output = "\n".join(lines)
            print(output)
            return {"success": False, "output": output,
                    "error": result.get("error", "unknown error")}

        # ── Phase 2: Self-verify ──
        lines.append("")
        lines.append("[*] Verifying generated payload...")
        verify_result = verify_payload(output_path)
        lines.append(verify_result.get("output", ""))

        # ── Summary ──
        elapsed = time.time() - t0
        lines.append("")
        lines.append(f"═══ SUMMARY ═══")
        lines.append(f"Output: {output_path}")
        lines.append(f"Size: {human_size(result.get('file_size', 0))}")
        lines.append(f"Partitions: {len(result.get('partitions', []))}")

        if result.get("partitions"):
            lines.append("")
            for part in result["partitions"]:
                ratio_pct = part.get("ratio", 0) * 100
                lines.append(
                    f"  {part['name']}: "
                    f"{human_size(part['original_size'])} → "
                    f"{human_size(part['compressed_size'])} "
                    f"({ratio_pct:.1f}%) [{part.get('algorithm', 'none')}]"
                )

        lines.append(f"\n[+] Done in {elapsed:.1f}s")

        output = "\n".join(lines)
        print(output)

        return {
            "success": True,
            "output": output,
            "output_path": output_path,
            "file_size": result.get("file_size", 0),
            "partitions": result.get("partitions", []),
        }

    except Exception as exc:
        err_msg = f"[!] Error generating payload: {exc}"
        lines.append(err_msg)
        import traceback
        lines.append(traceback.format_exc())
        output = "\n".join(lines)
        print(output)
        return {"success": False, "output": output, "error": str(exc)}
