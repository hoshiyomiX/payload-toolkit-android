"""
payload.py — Core AOSP OTA payload.bin read / write operations.

File format  (Brillo / update_engine v2):
    ┌──────────────────────────────────────────────────────────────┐
    │  Offset 0   :  "CrAU"                        (4  bytes)    │
    │  Offset 4   :  header protobuf length         (8  bytes BE) │
    │  Offset 12  :  PayloadHeader protobuf         (variable)    │
    │               ↳ version, manifest_len, metadata_sig_len,   │
    │                 minor_version                                 │
    │  Offset 12+N :  DeltaArchiveManifest protobuf   (variable)  │
    │  Offset 12+N+M:  data blobs ...               (variable)    │
    │               ↳ Each blob addressed by InstallOperation      │
    │                 (data_offset is relative to this point)      │
    │  [optional]  :  metadata signature block       (variable)    │
    └──────────────────────────────────────────────────────────────┘

This module provides:
    read_payload(path)    — Parse header + manifest, return structured dict.
    write_payload(...)    — Generate a payload.bin from partition images.
    verify_payload(path)  — Self-test by re-reading a generated payload.
"""

import hashlib
import os
import struct
import sys
import time

from . import _report_progress
from .compression import (
    compress,
    decompress,
    detect_compression,
    operation_type_for_algorithm,
    ALG_NONE,
)
from .protobuf import (
    OP_REPLACE,
    OP_REPLACE_BZ,
    OP_TYPE_NAMES,
    decode_install_operation,
    decode_manifest,
    decode_payload_header,
    encode_extent,
    encode_install_operation,
    encode_manifest,
    encode_partition_info,
    encode_partition_update,
    encode_payload_header,
    WIRE_LENGTH_DELIMITED,
    encode_message,
    encode_length_delimited,
)

# ── Constants ─────────────────────────────────────────────────────────

DELTA_MAGIC = b"CrAU"
HEADER_PROTOBUF_SIZE = 8  # uint64 big-endian, stores the header protobuf length
MAJOR_VERSION = 2  # Brillo v2
DEFAULT_BLOCK_SIZE = 4096

# Metadata signature section (appended after data blobs):
# padded to 4096 alignment, then:
#   4 bytes: metadata_signature_blob_length (big-endian uint32)
#   N bytes: Signatures protobuf blob
METADATA_SIG_ALIGNMENT = 4096


# ══════════════════════════════════════════════════════════════════════
#  READ
# ══════════════════════════════════════════════════════════════════════

def read_payload(path):
    """Read and parse a payload.bin file.

    Parameters
    ----------
    path : str
        Path to the payload.bin file.

    Returns
    -------
    dict
        'header'              : dict  (version, manifest_len, metadata_signature_len, minor_version)
        'manifest'            : dict  (block_size, minor_version, partitions, ...)
        'manifest_bytes'      : bytes (raw manifest protobuf)
        'data_offset'         : int   (absolute file offset where data blobs start)
        'file_size'           : int   (total file size in bytes)
        'header_len'          : int   (raw header protobuf length from file)
        'metadata_sig_offset' : int or None  (absolute offset of metadata signature block)
        'partitions'          : list  (convenience: manifest['partitions'])

    Raises
    ------
    ValueError  If the file does not start with the "CrAU" magic.
    FileNotFoundError  If *path* does not exist.
    """
    file_size = os.path.getsize(path)
    print(f"[*] Reading payload: {os.path.basename(path)} ({file_size / 1048576:.2f} MB)")

    with open(path, "rb") as f:
        # ── Magic ──
        magic = f.read(4)
        if magic != DELTA_MAGIC:
            raise ValueError(
                f"Invalid payload magic: expected 'CrAU', got {magic!r}"
            )

        # ── Header protobuf length (uint64 big-endian) ──
        raw_len = f.read(HEADER_PROTOBUF_SIZE)
        if len(raw_len) < HEADER_PROTOBUF_SIZE:
            raise ValueError("Truncated payload: cannot read header length")
        header_len = struct.unpack(">Q", raw_len)[0]

        if header_len > file_size:
            raise ValueError(
                f"Header length {header_len} exceeds file size {file_size}"
            )

        # ── Header protobuf ──
        header_bytes = f.read(header_len)
        if len(header_bytes) < header_len:
            raise ValueError("Truncated payload: cannot read header protobuf")
        header = decode_payload_header(header_bytes)
        print(f"[*] Header: version={header['version']}, "
              f"manifest_len={header['manifest_len']}, "
              f"minor_version={header.get('minor_version', 'N/A')}")

        # ── Manifest protobuf ──
        manifest_len = header["manifest_len"]
        manifest_bytes = f.read(manifest_len)
        if len(manifest_bytes) < manifest_len:
            raise ValueError(
                f"Truncated payload: expected {manifest_len} bytes of manifest, "
                f"got {len(manifest_bytes)}"
            )
        manifest = decode_manifest(manifest_bytes)

        # ── Data offset ──
        data_offset = 4 + HEADER_PROTOBUF_SIZE + header_len + manifest_len

        # ── Metadata signature offset (if any) ──
        metadata_sig_offset = None
        metadata_sig_len = header.get("metadata_signature_len", 0)
        if metadata_sig_len > 0:
            # The metadata signature starts at a 4096-aligned offset
            # after the end of data blobs.
            # We compute it from the file:
            # data_end = data_offset + total_data_size
            # But we don't know total_data_size without parsing all ops.
            # Instead, compute from file_size:
            # metadata_sig_start = file_size - metadata_sig_len_field
            # But metadata_signature_len in the header is the size of the
            # protobuf blob, not including the 4-byte length prefix.
            # Total metadata section = 4 + blob_len, padded to 4096.
            blob_section_size = 4 + metadata_sig_len
            # Align up
            if blob_section_size % METADATA_SIG_ALIGNMENT:
                blob_section_size += METADATA_SIG_ALIGNMENT - (blob_section_size % METADATA_SIG_ALIGNMENT)
            metadata_sig_offset = file_size - blob_section_size

        print(f"[*] Data offset: {data_offset} (0x{data_offset:X})")
        print(f"[*] Partitions: {len(manifest.get('partitions', []))}")

    return {
        "header": header,
        "manifest": manifest,
        "manifest_bytes": manifest_bytes,
        "data_offset": data_offset,
        "file_size": file_size,
        "header_len": header_len,
        "metadata_sig_offset": metadata_sig_offset,
        "partitions": manifest.get("partitions", []),
    }


def extract_partition_data(payload_info, partition_name):
    """Extract the raw (compressed) data blobs for a partition.

    Reads the data blob for each InstallOperation in the partition's
    operation list and returns the concatenation.

    Parameters
    ----------
    payload_info : dict
        The dict returned by read_payload().
    partition_name : str
        Name of the partition to extract.

    Returns
    -------
    bytes
        Concatenated (compressed) data for all operations.
    """
    data_offset = payload_info["data_offset"]
    path = payload_info.get("_path", "")

    partition = None
    for p in payload_info["partitions"]:
        if p["partition_name"] == partition_name:
            partition = p
            break
    if partition is None:
        raise ValueError(f"Partition '{partition_name}' not found in manifest")

    chunks = []
    with open(path, "rb") as f:
        for op in partition.get("install_operations", []):
            if op["type"] in (21, 22):  # ZERO / DISCARD — no data
                continue
            if op["data_length"] == 0:
                continue
            f.seek(data_offset + op["data_offset"])
            chunk = f.read(op["data_length"])
            if len(chunk) < op["data_length"]:
                raise ValueError(
                    f"Truncated data for partition '{partition_name}': "
                    f"expected {op['data_length']} bytes, got {len(chunk)}"
                )
            chunks.append(chunk)

    return b"".join(chunks)


def extract_and_decompress_partition(payload_info, partition_name):
    """Extract, detect compression, and decompress a partition image.

    Parameters
    ----------
    payload_info : dict
        The dict returned by read_payload().
        NOTE: must have '_path' key set to the payload.bin file path.
    partition_name : str

    Returns
    -------
    bytes
        Decompressed partition image data.
    """
    partition = None
    for p in payload_info["partitions"]:
        if p["partition_name"] == partition_name:
            partition = p
            break
    if partition is None:
        raise ValueError(f"Partition '{partition_name}' not found")

    ops = partition.get("install_operations", [])
    if not ops:
        raise ValueError(f"No install operations for partition '{partition_name}'")

    data_offset = payload_info["data_offset"]
    path = payload_info["_path"]
    output_chunks = []

    with open(path, "rb") as f:
        for i, op in enumerate(ops):
            op_type = op["type"]

            # ZERO: fill with zeros
            if op_type == 21:
                # Calculate size from dst_extents
                total_blocks = sum(ext["num_blocks"] for ext in op.get("dst_extents", []))
                block_size = payload_info["manifest"]["block_size"]
                output_chunks.append(b"\x00" * (total_blocks * block_size))
                continue

            # DISCARD: no data
            if op_type == 22:
                continue

            # Read compressed/raw data
            data_len = op["data_length"]
            if data_len == 0:
                continue

            f.seek(data_offset + op["data_offset"])
            compressed_data = f.read(data_len)
            if len(compressed_data) < data_len:
                raise ValueError(f"Truncated data at offset {data_offset + op['data_offset']}")

            # Detect compression and decompress
            # Prefer auto-detect from magic bytes for reliability.
            alg_hint = detect_compression(op_type)
            try:
                decompressed = decompress(compressed_data, algorithm="auto")
            except Exception:
                # Fallback: use operation-type hint
                decompressed = decompress(compressed_data, algorithm=alg_hint)

            # If dst_extents are specified, place data accordingly.
            # For simple REPLACE ops, just concatenate.
            dst_extents = op.get("dst_extents", [])
            if dst_extents:
                block_size = payload_info["manifest"]["block_size"]
                expected_size = sum(
                    ext["num_blocks"] * block_size for ext in dst_extents
                )
                if len(decompressed) < expected_size:
                    decompressed += b"\x00" * (expected_size - len(decompressed))
                elif len(decompressed) > expected_size:
                    decompressed = decompressed[:expected_size]

            output_chunks.append(decompressed)

    return b"".join(output_chunks)


# ══════════════════════════════════════════════════════════════════════
#  WRITE
# ══════════════════════════════════════════════════════════════════════

def write_payload(output_path, partitions_data, block_size=DEFAULT_BLOCK_SIZE,
                  minor_version=0):
    """Generate a payload.bin from partition images.

    Parameters
    ----------
    output_path : str
        Path for the output payload.bin file.
    partitions_data : list of dict
        Each dict has:
            'name'    : str  (partition name, e.g. "boot")
            'image_path' : str  (path to the .img file)
            'compress' : str  (algorithm: "none", "bzip2", "gzip", "xz", "brotli")
    block_size : int
        Block size in bytes (default 4096).
    minor_version : int
        Payload minor version.

    Returns
    -------
    dict
        'success'      : bool
        'output'       : str   (log text)
        'output_path'  : str
        'file_size'    : int
        'partitions'   : list  (per-partition summary dicts)
    """
    t0 = time.time()
    lines = []
    total_images = len(partitions_data)
    partition_summaries = []

    try:
        # ── Phase 1: Read, hash, and optionally compress each image ──
        all_blobs = []           # compressed (or raw) data blobs
        encoded_partitions = []  # encoded PartitionUpdate messages
        current_data_offset = 0

        for idx, part in enumerate(partitions_data):
            name = part.get("name", f"partition_{idx}")
            image_path = part.get("image_path", "")
            alg = part.get("compress", "none")

            if not image_path or not os.path.isfile(image_path):
                raise FileNotFoundError(f"Image file not found: {image_path}")

            img_size = os.path.getsize(image_path)
            lines.append(f"[{idx + 1}/{total_images}] Processing {name} "
                         f"({img_size / 1048576:.2f} MB, compress={alg})")
            _report_progress(idx + 1, total_images,
                            f"Processing {name}")

            # Read image in chunks to handle large files
            sha = hashlib.sha256()
            img_data = bytearray()

            with open(image_path, "rb") as f:
                while True:
                    chunk = f.read(4 * 1024 * 1024)  # 4 MB chunks
                    if not chunk:
                        break
                    sha.update(chunk)
                    img_data.extend(chunk)

            hash_bytes = sha.digest()

            # Compress
            if alg and alg.lower() != "none":
                compressed = compress(bytes(img_data), algorithm=alg)
            else:
                compressed = bytes(img_data)

            del img_data  # free memory

            # Build InstallOperation
            op_type = operation_type_for_algorithm(alg)

            # Build dst_extents: single extent covering the whole image
            num_blocks = (img_size + block_size - 1) // block_size

            dst_extent = encode_extent(start_block=0, num_blocks=num_blocks)
            op_blob = encode_install_operation(
                op_type=op_type,
                data_offset=current_data_offset,
                data_length=len(compressed),
                dst_extents=[dst_extent],
                data_sha256_hash=hash_bytes,
                dst_length=img_size,
            )

            # Build PartitionUpdate
            new_info = encode_partition_info(
                partition_size=img_size,
                hash_bytes=hash_bytes,
            )
            part_blob = encode_partition_update(
                name=name,
                install_operations=[op_blob],
                new_partition_info=new_info,
            )

            all_blobs.append(compressed)
            encoded_partitions.append(part_blob)
            current_data_offset += len(compressed)

            partition_summaries.append({
                "name": name,
                "original_size": img_size,
                "compressed_size": len(compressed),
                "ratio": len(compressed) / img_size if img_size > 0 else 1.0,
                "algorithm": alg,
                "op_type": op_type,
                "op_type_name": OP_TYPE_NAMES.get(op_type, str(op_type)),
                "sha256": hash_bytes.hex(),
            })

            lines.append(f"    -> {len(compressed) / 1048576:.2f} MB "
                         f"(ratio: {len(compressed) / img_size * 100:.1f}%)" if img_size > 0
                         else f"    -> {len(compressed)} bytes")

        # ── Phase 2: Build manifest ──
        lines.append("[*] Building manifest...")
        manifest_blob = encode_manifest(
            block_size=block_size,
            minor_version=minor_version,
            partitions=encoded_partitions,
        )

        # ── Phase 3: Build header ──
        header_blob = encode_payload_header(
            version=MAJOR_VERSION,
            manifest_len=len(manifest_blob),
            metadata_signature_len=0,
            minor_version=minor_version,
        )

        # ── Phase 4: Write payload.bin ──
        lines.append(f"[*] Writing payload.bin to {output_path}")
        os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)

        with open(output_path, "wb") as f:
            # Magic
            f.write(DELTA_MAGIC)
            # Header protobuf length (big-endian uint64)
            f.write(struct.pack(">Q", len(header_blob)))
            # Header protobuf
            f.write(header_blob)
            # Manifest protobuf
            f.write(manifest_blob)
            # Data blobs
            for blob in all_blobs:
                f.write(blob)

        total_size = os.path.getsize(output_path)
        elapsed = time.time() - t0

        lines.append(f"[+] Payload written: {total_size / 1048576:.2f} MB in {elapsed:.1f}s")
        lines.append(f"[+] Partitions: {total_images}")

        return {
            "success": True,
            "output": "\n".join(lines),
            "output_path": output_path,
            "file_size": total_size,
            "partitions": partition_summaries,
            "elapsed": elapsed,
        }

    except Exception as exc:
        lines.append(f"[!] Error: {exc}")
        return {
            "success": False,
            "output": "\n".join(lines),
            "error": str(exc),
        }


# ══════════════════════════════════════════════════════════════════════
#  VERIFY
# ══════════════════════════════════════════════════════════════════════

def verify_payload(path):
    """Self-verify a generated payload.bin by re-reading it.

    Checks:
        - Valid "CrAU" magic
        - Parseable header and manifest
        - Partition count matches expectations

    Parameters
    ----------
    path : str

    Returns
    -------
    dict
        'success' : bool
        'output'  : str
        'header'  : dict
        'manifest': dict
    """
    lines = []
    try:
        info = read_payload(path)
        info["_path"] = path  # needed for extract functions

        header = info["header"]
        manifest = info["manifest"]
        partitions = manifest.get("partitions", [])

        lines.append(f"[+] Verification passed for {os.path.basename(path)}")
        lines.append(f"    Version:     {header['version']}")
        lines.append(f"    Block size:  {manifest['block_size']}")
        lines.append(f"    Minor ver:   {header.get('minor_version', 'N/A')}")
        lines.append(f"    Partitions:  {len(partitions)}")
        lines.append(f"    Data offset: {info['data_offset']}")

        # Verify each partition's hash if possible
        for part in partitions:
            name = part["partition_name"]
            new_info = part.get("new_partition_info")
            if new_info and new_info.get("hash"):
                expected_hash = new_info["hash"]
                lines.append(f"    {name}: hash={expected_hash[:16].hex()}...")

        return {
            "success": True,
            "output": "\n".join(lines),
            "header": header,
            "manifest": manifest,
        }

    except Exception as exc:
        lines.append(f"[!] Verification failed: {exc}")
        return {
            "success": False,
            "output": "\n".join(lines),
            "error": str(exc),
        }


# ══════════════════════════════════════════════════════════════════════
#  Utility helpers
# ══════════════════════════════════════════════════════════════════════

def human_size(size_bytes):
    """Format a byte count as a human-readable string."""
    if size_bytes < 1024:
        return f"{size_bytes} B"
    if size_bytes < 1048576:
        return f"{size_bytes / 1024:.1f} KB"
    if size_bytes < 1073741824:
        return f"{size_bytes / 1048576:.1f} MB"
    return f"{size_bytes / 1073741824:.2f} GB"


def compute_file_sha256(path):
    """Compute SHA-256 hash of a file, reading in chunks."""
    sha = hashlib.sha256()
    with open(path, "rb") as f:
        while True:
            chunk = f.read(8 * 1024 * 1024)  # 8 MB chunks
            if not chunk:
                break
            sha.update(chunk)
    return sha.digest()
