"""
modes/info.py — Parse and display payload.bin metadata.

Called from Kotlin via Chaquopy:
    payload_toolkit.modes.info.run(payload_path="/path/to/payload.bin", verbose=False)

Returns dict: {'success': True/False, 'output': str, 'partitions': [...]}
"""

import os
import time

from .. import _report_progress
from ..payload import read_payload, human_size
from ..protobuf import OP_TYPE_NAMES


def _parse_args(args, kwargs):
    """Normalise Chaquopy dict-arg vs direct keyword-arg calling convention."""
    if args and isinstance(args[0], dict):
        return args[0]
    return kwargs


def run(*args, **kwargs):
    """Parse and display payload.bin metadata.

    Accepts either a dict as first positional arg (Chaquopy style) or
    keyword arguments.

    Parameters (via dict or kwargs)
    -------------------------------
    payload_path : str   — Path to payload.bin
    verbose      : bool  — Show detailed per-operation info (default False)

    Returns
    -------
    dict
        success    : bool
        output     : str   (formatted text for display)
        partitions : list  (summary dicts per partition)
    """
    params = _parse_args(args, kwargs)
    payload_path = str(params.get("payload_path", ""))
    verbose = bool(params.get("verbose", False))

    lines = []
    t0 = time.time()

    if not payload_path:
        return {"success": False, "output": "[!] Error: payload_path is required",
                "error": "payload_path is required"}

    if not os.path.isfile(payload_path):
        return {"success": False,
                "output": f"[!] Error: file not found: {payload_path}",
                "error": f"file not found: {payload_path}"}

    try:
        info = read_payload(payload_path)
        header = info["header"]
        manifest = info["manifest"]
        partitions = manifest.get("partitions", [])
        file_size = info["file_size"]

        # ── Header section ──
        lines.append("═══ PAYLOAD.BIN INFO ═══")
        lines.append(f"File: {os.path.basename(payload_path)} ({human_size(file_size)})")
        lines.append(f"Version: {header['version']}")
        lines.append(f"Block Size: {manifest['block_size']}")
        lines.append(f"Minor Version: {header.get('minor_version', 'N/A')}")
        lines.append(f"Manifest Size: {human_size(info['manifest_bytes'].__len__())}")
        lines.append(f"Data Offset: {info['data_offset']} (0x{info['data_offset']:X})")
        lines.append(f"Partitions: {len(partitions)}")

        if info.get("metadata_sig_offset"):
            lines.append(f"Metadata Sig Offset: {info['metadata_sig_offset']}")
        lines.append("")

        # ── Partition table ──
        if partitions:
            # Column widths
            COL_NAME = 20
            COL_SIZE = 12
            COL_COMP = 14
            COL_HASH = 14
            COL_OPS = 8

            hline = "┌" + "─" * COL_NAME + "┬" + "─" * COL_SIZE + "┬" + "─" * COL_COMP + "┬" + "─" * COL_HASH + "┬" + "─" * COL_OPS + "┐"
            mline = "├" + "─" * COL_NAME + "┼" + "─" * COL_SIZE + "┼" + "─" * COL_COMP + "┼" + "─" * COL_HASH + "┼" + "─" * COL_OPS + "┤"
            eline = "└" + "─" * COL_NAME + "┴" + "─" * COL_SIZE + "┴" + "─" * COL_COMP + "┴" + "─" * COL_HASH + "┴" + "─" * COL_OPS + "┘"

            lines.append(hline)
            header_txt = (f"│ {'Name':<{COL_NAME-2}} │ {'Size':^{COL_SIZE-2}} │ "
                          f"{'Compress':^{COL_COMP-2}} │ {'Hash':^{COL_HASH-2}} │ "
                          f"{'Ops':^{COL_OPS-2}} │")
            lines.append(header_txt)
            lines.append(mline)

            part_summaries = []
            for part in partitions:
                name = part["partition_name"]
                ops = part.get("install_operations", [])
                num_ops = len(ops)

                # Determine primary compression type from first op
                comp_name = "N/A"
                if ops:
                    first_type = ops[0].get("type", 0)
                    comp_name = OP_TYPE_NAMES.get(first_type, f"TYPE({first_type})")

                # Partition size from new_partition_info
                part_size = 0
                hash_hex = "N/A"
                new_info = part.get("new_partition_info")
                if new_info:
                    part_size = new_info.get("partition_size", 0)
                    h = new_info.get("hash", b"")
                    if h:
                        hash_hex = h[:6].hex() + ".."

                lines.append(
                    f"│ {name:<{COL_NAME-2}} │ {human_size(part_size):^{COL_SIZE-2}} │ "
                    f"{comp_name:^{COL_COMP-2}} │ {hash_hex:^{COL_HASH-2}} │ "
                    f"{num_ops:^{COL_OPS-2}} │"
                )

                part_summaries.append({
                    "name": name,
                    "size": part_size,
                    "size_human": human_size(part_size),
                    "compression": comp_name,
                    "num_operations": num_ops,
                    "hash": hash_hex,
                })

            lines.append(eline)

        # ── Verbose per-operation detail ──
        if verbose:
            lines.append("")
            lines.append("═══ DETAILED OPERATIONS ═══")
            for part in partitions:
                name = part["partition_name"]
                ops = part.get("install_operations", [])
                if not ops:
                    continue
                lines.append(f"\n  [{name}] — {len(ops)} operation(s)")
                for i, op in enumerate(ops):
                    lines.append(f"    Op #{i}: type={op['type_name']} "
                                 f"offset=0x{op.get('data_offset', 0):X} "
                                 f"len={human_size(op.get('data_length', 0))}")
                    if op.get("dst_extents"):
                        for ext in op["dst_extents"]:
                            lines.append(f"      dst: block {ext['start_block']} "
                                         f"+{ext['num_blocks']} "
                                         f"({human_size(ext['num_blocks'] * manifest['block_size'])})")
                    sha = op.get("data_sha256_hash", b"")
                    if sha:
                        lines.append(f"      sha256: {sha[:16].hex()}...")

        elapsed = time.time() - t0
        lines.append(f"\n[+] Parsed in {elapsed:.2f}s")

        output = "\n".join(lines)
        print(output)

        return {
            "success": True,
            "output": output,
            "partitions": part_summaries if partitions else [],
        }

    except Exception as exc:
        err_msg = f"[!] Error parsing payload: {exc}"
        lines.append(err_msg)
        output = "\n".join(lines)
        print(output)
        return {"success": False, "output": output, "error": str(exc)}


def get_partition_names(payload_path):
    """Quick list of partition names from a payload.bin.

    Parameters
    ----------
    payload_path : str

    Returns
    -------
    list of str
    """
    info = read_payload(payload_path)
    return [p["partition_name"] for p in info.get("partitions", [])]
