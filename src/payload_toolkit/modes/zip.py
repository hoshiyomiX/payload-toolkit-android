"""
modes/zip.py — Generate a flashable OTA ZIP from partition images.

Called from Kotlin via Chaquopy:
    payload_toolkit.modes.zip.run(
        images={"boot": "/boot.img", "system": "/system.img"},
        device="S666LN",
        fingerprint="itel/S666LN/S666LN:12/SP1A.210812.016/V023:user/release-keys",
        compress="bzip2",
        output_path="/path/to/output.zip",
        cert_path="/path/to/cert.pem"  # optional
    )

Returns dict: {'success': True/False, 'output': str}
"""

import os
import tempfile
import time

from .. import _report_progress
from ..ota_metadata import build_ota_zip, generate_payload_properties
from ..payload import write_payload, verify_payload, read_payload, human_size


def _parse_args(args, kwargs):
    """Normalise Chaquopy dict-arg vs direct keyword-arg calling convention."""
    if args and isinstance(args[0], dict):
        return args[0]
    return kwargs


def run(*args, **kwargs):
    """Generate a flashable OTA ZIP from partition images.

    Parameters (via dict or kwargs)
    -------------------------------
    images      : dict  — {partition_name: image_file_path, ...}
    device      : str   — Device identifier
    fingerprint : str   — Build fingerprint string
    compress    : str   — Compression algorithm (default "bzip2")
    output_path : str   — Path for output .zip file
    cert_path   : str/None — Path to OTA certificate (optional)
    block_size  : int   — Block size in bytes (default 4096)

    Returns
    -------
    dict
        success   : bool
        output    : str
        zip_path  : str
        zip_size  : int
    """
    params = _parse_args(args, kwargs)
    images = params.get("images", {})
    device = str(params.get("device", ""))
    fingerprint = str(params.get("fingerprint", ""))
    compress_alg = str(params.get("compress", "bzip2"))
    output_path = str(params.get("output_path", ""))
    cert_path = params.get("cert_path", None)
    if cert_path is not None:
        cert_path = str(cert_path)
    block_size = int(params.get("block_size", 4096))

    lines = []
    t0 = time.time()

    # ── Validate inputs ──
    if not images:
        return {"success": False, "output": "[!] Error: no images specified",
                "error": "no images specified"}

    if not device:
        return {"success": False, "output": "[!] Error: device is required",
                "error": "device is required"}

    if not fingerprint:
        return {"success": False, "output": "[!] Error: fingerprint is required",
                "error": "fingerprint is required"}

    if not output_path:
        return {"success": False, "output": "[!] Error: output_path is required",
                "error": "output_path is required"}

    # Normalise images dict
    if isinstance(images, dict):
        images = {str(k): str(v) for k, v in images.items()}

    # Validate image files
    for name, path in images.items():
        if not os.path.isfile(path):
            return {"success": False,
                    "output": f"[!] Image not found: {name} -> {path}",
                    "error": f"image file not found: {path}"}

    try:
        lines.append("═══ OTA ZIP GENERATION ═══")
        lines.append(f"Device: {device}")
        lines.append(f"Fingerprint: {fingerprint}")
        lines.append(f"Compression: {compress_alg}")
        lines.append(f"Output: {output_path}")
        lines.append(f"Partitions: {len(images)}")
        lines.append("")

        # ── Pass 1: Generate payload.bin to a temp file ──
        _report_progress(1, 3, "Generating payload.bin")

        # Use a temp file for the intermediate payload.bin
        temp_dir = os.path.dirname(output_path) or "."
        temp_payload = os.path.join(temp_dir, ".tmp_payload.bin")

        try:
            partitions_data = [
                {"name": name, "image_path": path, "compress": compress_alg}
                for name, path in images.items()
            ]

            gen_result = write_payload(
                output_path=temp_payload,
                partitions_data=partitions_data,
                block_size=block_size,
                minor_version=0,
            )

            if not gen_result["success"]:
                lines.append(gen_result.get("output", ""))
                lines.append("[!] Failed to generate payload.bin")
                output = "\n".join(lines)
                print(output)
                return {"success": False, "output": output,
                        "error": gen_result.get("error", "payload generation failed")}

            lines.append(gen_result.get("output", ""))
            lines.append("")

            # ── Pass 2: Build OTA ZIP ──
            _report_progress(2, 3, "Building OTA ZIP")

            zip_result = build_ota_zip(
                payload_path=temp_payload,
                output_path=output_path,
                device=device,
                fingerprint=fingerprint,
                cert_path=cert_path,
            )

            lines.append(zip_result.get("output", ""))

            if not zip_result["success"]:
                lines.append("[!] Failed to build OTA ZIP")
                output = "\n".join(lines)
                print(output)
                return {"success": False, "output": output,
                        "error": zip_result.get("error", "ZIP build failed")}

            # ── Pass 3: Verify ──
            _report_progress(3, 3, "Verifying OTA ZIP")
            lines.append("")
            lines.append("[*] Verifying payload inside OTA ZIP...")

            verify_result = verify_payload(temp_payload)
            if verify_result["success"]:
                lines.append("[+] Payload verification: PASSED")
            else:
                lines.append("[!] Payload verification: FAILED")
                lines.append(verify_result.get("output", ""))

        finally:
            # Clean up temp payload
            if os.path.isfile(temp_payload):
                try:
                    os.remove(temp_payload)
                except OSError:
                    pass

        # ── Summary ──
        elapsed = time.time() - t0
        zip_size = os.path.getsize(output_path) if os.path.isfile(output_path) else 0

        lines.append("")
        lines.append("═══ SUMMARY ═══")
        lines.append(f"OTA ZIP: {output_path}")
        lines.append(f"ZIP Size: {human_size(zip_size)}")
        lines.append(f"Device: {device}")
        lines.append(f"Fingerprint: {fingerprint}")
        lines.append(f"Compression: {compress_alg}")
        lines.append(f"Partitions: {len(images)}")
        lines.append(f"\n[+] OTA ZIP generated in {elapsed:.1f}s")

        output = "\n".join(lines)
        print(output)

        return {
            "success": True,
            "output": output,
            "zip_path": output_path,
            "zip_size": zip_size,
        }

    except Exception as exc:
        err_msg = f"[!] Error generating OTA ZIP: {exc}"
        lines.append(err_msg)
        import traceback
        lines.append(traceback.format_exc())
        output = "\n".join(lines)
        print(output)
        return {"success": False, "output": output, "error": str(exc)}
