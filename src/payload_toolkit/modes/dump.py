"""
modes/dump.py — Extract partition images from payload.bin.

Called from Kotlin via Chaquopy:
    payload_toolkit.modes.dump.run(payload_path="/path", output_dir="/out", partitions=["boot","system"])

Returns dict: {'success': True/False, 'output': str, 'extracted': [...]}
"""

import hashlib
import os
import sys
import time

from .. import _report_progress
from ..compression import detect_compression, decompress
from ..payload import read_payload, human_size
from ..protobuf import OP_TYPE_NAMES


def _parse_args(args, kwargs):
    """Normalise Chaquopy dict-arg vs direct keyword-arg calling convention."""
    if args and isinstance(args[0], dict):
        return args[0]
    return kwargs


def run(*args, **kwargs):
    """Extract partition images from payload.bin.

    Parameters (via dict or kwargs)
    -------------------------------
    payload_path : str       — Path to payload.bin
    output_dir   : str       — Directory for extracted images
    partitions   : list/None — List of partition names to extract (None = all)

    Returns
    -------
    dict
        success   : bool
        output    : str   (log text)
        extracted : list  (per-partition summary dicts)
    """
    params = _parse_args(args, kwargs)
    payload_path = str(params.get("payload_path", ""))
    output_dir = str(params.get("output_dir", ""))
    partitions_filter = params.get("partitions", None)

    lines = []
    t0 = time.time()

    # ── Validate inputs ──
    if not payload_path:
        return {"success": False, "output": "[!] Error: payload_path is required",
                "error": "payload_path is required"}
    if not output_dir:
        return {"success": False, "output": "[!] Error: output_dir is required",
                "error": "output_dir is required"}
    if not os.path.isfile(payload_path):
        return {"success": False,
                "output": f"[!] Error: file not found: {payload_path}",
                "error": f"file not found: {payload_path}"}

    # Normalise partitions filter
    if partitions_filter is not None:
        if isinstance(partitions_filter, str):
            partitions_filter = [s.strip() for s in partitions_filter.split(",") if s.strip()]
        else:
            partitions_filter = [str(p) for p in partitions_filter]
        partitions_filter = set(partitions_filter)
    else:
        partitions_filter = None

    try:
        # ── Read payload ──
        info = read_payload(payload_path)
        info["_path"] = payload_path  # needed for extraction

        manifest = info["manifest"]
        block_size = manifest["block_size"]
        all_partitions = info["partitions"]

        if not all_partitions:
            return {"success": True, "output": "[*] No partitions found in payload",
                    "extracted": []}

        # ── Filter partitions ──
        if partitions_filter:
            to_extract = [p for p in all_partitions
                          if p["partition_name"] in partitions_filter]
            if not to_extract:
                available = [p["partition_name"] for p in all_partitions]
                return {
                    "success": False,
                    "output": (f"[!] None of the requested partitions found. "
                               f"Requested: {partitions_filter}, "
                               f"Available: {available}"),
                    "error": "requested partitions not found",
                }
        else:
            to_extract = all_partitions

        # ── Create output directory ──
        os.makedirs(output_dir, exist_ok=True)
        lines.append(f"[*] Extracting {len(to_extract)} partition(s) to {output_dir}")
        lines.append("")

        data_offset = info["data_offset"]
        extracted = []
        total = len(to_extract)

        for idx, part in enumerate(to_extract):
            name = part["partition_name"]
            ops = part.get("install_operations", [])

            _report_progress(idx + 1, total, f"Extracting {name}")
            lines.append(f"[{idx + 1}/{total}] {name} ({len(ops)} operation(s))")

            # Calculate expected output size from new_partition_info
            new_info = part.get("new_partition_info")
            expected_size = 0
            expected_hash = b""
            if new_info:
                expected_size = new_info.get("partition_size", 0)
                expected_hash = new_info.get("hash", b"")

            # Extract and decompress all operations
            output_chunks = []
            total_written = 0

            with open(payload_path, "rb") as f:
                for op_idx, op in enumerate(ops):
                    op_type = op["type"]
                    data_len = op["data_length"]

                    # ZERO: fill with zeros
                    if op_type == 21:
                        dst_extents = op.get("dst_extents", [])
                        num_blocks = sum(ext["num_blocks"] for ext in dst_extents)
                        zero_data = b"\x00" * (num_blocks * block_size)
                        output_chunks.append(zero_data)
                        total_written += len(zero_data)
                        lines.append(f"    Op #{op_idx}: ZERO — "
                                     f"{human_size(len(zero_data))}")
                        continue

                    # DISCARD: skip
                    if op_type == 22:
                        lines.append(f"    Op #{op_idx}: DISCARD — skipped")
                        continue

                    if data_len == 0:
                        lines.append(f"    Op #{op_idx}: {op['type_name']} — "
                                     f"no data (0 bytes)")
                        continue

                    # Read compressed data
                    abs_offset = data_offset + op["data_offset"]
                    f.seek(abs_offset)
                    compressed_data = f.read(data_len)

                    if len(compressed_data) < data_len:
                        lines.append(f"    [!] Op #{op_idx}: TRUNCATED — "
                                     f"expected {data_len}, got {len(compressed_data)}")
                        continue

                    # Detect and decompress
                    # Strategy: try auto-detect from magic bytes first (most
                    # reliable), then fall back to the operation-type hint.
                    alg_hint = detect_compression(op_type)
                    decompressed = None
                    used_auto = False

                    # Step 1: Try auto-detect
                    try:
                        decompressed = decompress(compressed_data, algorithm="auto")
                        used_auto = True
                    except Exception:
                        pass

                    # Step 2: Fall back to type-based detection
                    if decompressed is None:
                        try:
                            decompressed = decompress(compressed_data, algorithm=alg_hint)
                        except Exception:
                            # Step 3: Last resort — use raw data
                            decompressed = compressed_data
                            lines.append(f"    Op #{op_idx}: WARNING — "
                                         f"decompression failed, using raw data")

                    # Trim/pad to match dst_extents size
                    dst_extents = op.get("dst_extents", [])
                    if dst_extents:
                        expected_op_size = sum(
                            ext["num_blocks"] * block_size for ext in dst_extents
                        )
                        if len(decompressed) < expected_op_size:
                            decompressed += b"\x00" * (expected_op_size - len(decompressed))
                        elif len(decompressed) > expected_op_size:
                            decompressed = decompressed[:expected_op_size]

                    output_chunks.append(decompressed)
                    total_written += len(decompressed)

                    lines.append(
                        f"    Op #{op_idx}: {op['type_name']} — "
                        f"{human_size(data_len)} → {human_size(len(decompressed))}"
                    )

            # Assemble full partition image
            image_data = b"".join(output_chunks)

            # Trim to expected size if known
            if expected_size > 0 and len(image_data) > expected_size:
                image_data = image_data[:expected_size]
            elif expected_size > 0 and len(image_data) < expected_size:
                image_data += b"\x00" * (expected_size - len(image_data))

            # Compute SHA-256
            actual_sha = hashlib.sha256(image_data).digest()
            sha_match = ""
            if expected_hash:
                if actual_sha == expected_hash:
                    sha_match = " ✓ SHA-256 OK"
                else:
                    sha_match = " ✗ SHA-256 MISMATCH!"

            # Write to file
            out_path = os.path.join(output_dir, f"{name}.img")
            with open(out_path, "wb") as f:
                f.write(image_data)

            lines.append(f"    Written: {out_path} ({human_size(len(image_data))})"
                         f"{sha_match}")

            extracted.append({
                "name": name,
                "path": out_path,
                "size": len(image_data),
                "size_human": human_size(len(image_data)),
                "sha256": actual_sha.hex(),
                "sha256_match": (actual_sha == expected_hash) if expected_hash else None,
            })

        # ── Summary ──
        elapsed = time.time() - t0
        lines.append("")
        lines.append(f"[+] Extracted {len(extracted)} partition(s) in {elapsed:.1f}s")
        total_size = sum(e["size"] for e in extracted)
        lines.append(f"[+] Total extracted: {human_size(total_size)}")

        output = "\n".join(lines)
        print(output)

        return {
            "success": True,
            "output": output,
            "extracted": extracted,
        }

    except Exception as exc:
        err_msg = f"[!] Error during extraction: {exc}"
        lines.append(err_msg)
        import traceback
        lines.append(traceback.format_exc())
        output = "\n".join(lines)
        print(output)
        return {"success": False, "output": output, "error": str(exc)}
