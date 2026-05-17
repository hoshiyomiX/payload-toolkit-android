"""
ota_metadata.py — OTA ZIP metadata generation for AOSP OTA format.

Generates the supporting files needed for a flashable OTA ZIP:

    payload_properties.txt
        FILE_HASH=<base64(sha256(payload.bin))>
        FILE_SIZE=<size>
        METADATA_HASH=<base64(sha256(manifest))>
        METADATA_SIZE=<size>

    metadata  (text file inside the OTA ZIP)
        post-build=<fingerprint>
        post-build-incremental=<build_number>
        post-sdk-level=<sdk_level>
        pre-device=<device>
        serialno=0

    metadata.pb  (protobuf, BuildInfo / DeviceState)
        Encoded DeviceState protobuf with pre-device and post-build fields.

    care_map.pb  (stub protobuf)
    payload_metadata.bin  (manifest protobuf, duplicated for OTA updater)
"""

import base64
import hashlib
import os
import struct
import zipfile

from . import _report_progress
from .payload import compute_file_sha256, human_size
from .protobuf import (
    decode_message,
    encode_message,
    encode_length_delimited,
    encode_varint_field,
)


# ══════════════════════════════════════════════════════════════════════
#  payload_properties.txt
# ══════════════════════════════════════════════════════════════════════

def generate_payload_properties(payload_path, manifest_bytes=None):
    """Generate the content of ``payload_properties.txt``.

    Parameters
    ----------
    payload_path : str
        Path to payload.bin.
    manifest_bytes : bytes or None
        Pre-read manifest protobuf bytes.  If None, will be read from
        the payload.bin automatically.

    Returns
    -------
    dict
        'content' : str  (text to write to payload_properties.txt)
        'file_hash' : str  (base64-encoded SHA-256 of payload.bin)
        'file_size' : int
        'metadata_hash' : str  (base64-encoded SHA-256 of manifest)
        'metadata_size' : int
    """
    file_size = os.path.getsize(payload_path)
    file_hash = compute_file_sha256(payload_path)

    if manifest_bytes is None:
        # Read manifest from payload.bin
        manifest_bytes = _read_manifest_from_payload(payload_path)

    metadata_hash = hashlib.sha256(manifest_bytes).digest()
    metadata_size = len(manifest_bytes)

    content = (
        f"FILE_HASH={base64.b64encode(file_hash).decode('ascii')}\n"
        f"FILE_SIZE={file_size}\n"
        f"METADATA_HASH={base64.b64encode(metadata_hash).decode('ascii')}\n"
        f"METADATA_SIZE={metadata_size}\n"
    )

    return {
        "content": content,
        "file_hash": base64.b64encode(file_hash).decode("ascii"),
        "file_size": file_size,
        "metadata_hash": base64.b64encode(metadata_hash).decode("ascii"),
        "metadata_size": metadata_size,
    }


# ══════════════════════════════════════════════════════════════════════
#  metadata / metadata.pb
# ══════════════════════════════════════════════════════════════════════

def generate_ota_metadata(manifest, device, fingerprint):
    """Generate OTA metadata text and protobuf.

    Parameters
    ----------
    manifest : dict
        The parsed manifest dict (from read_payload).
    device : str
        Device identifier (e.g. "S666LN" or "S666LN,itel-S666LN").
    fingerprint : str
        Build fingerprint (e.g.
        "itel/S666LN/S666LN:12/SP1A.210812.016/V023:user/release-keys").

    Returns
    -------
    dict
        'metadata_text' : str    (text content for the 'metadata' file in the ZIP)
        'metadata_pb'   : bytes  (protobuf content for 'metadata.pb')
    """
    # Extract build number from fingerprint
    # Format: brand/product/device:version/build_number/...
    build_number = "0"
    sdk_level = "31"
    if fingerprint:
        parts = fingerprint.split("/")
        if len(parts) >= 4:
            version_part = parts[3]  # e.g. "12" or "SP1A.210812.016/V023:user/release-keys"
            slash_idx = version_part.find("/")
            if slash_idx >= 0:
                build_number = version_part[:slash_idx]
            else:
                build_number = version_part

        # Try to extract SDK level from version
        version_str = parts[3].split("/")[0] if len(parts) >= 4 else ""
        # Map Android version to SDK level
        version_to_sdk = {
            "12": "31", "13": "33", "14": "34", "15": "35",
            "11": "30", "10": "29", "9": "28", "8": "26",
            "8.1.0": "27", "7.1.2": "25", "7.0": "24",
        }
        # The build fingerprint often has the SDK codename like "SP1A.210812.016"
        # instead of version number, so default to 31 (Android 12)
        if version_str in version_to_sdk:
            sdk_level = version_to_sdk[version_str]

    metadata_text = (
        f"post-build={fingerprint}\n"
        f"post-build-incremental={build_number}\n"
        f"post-sdk-level={sdk_level}\n"
        f"pre-device={device}\n"
        f"serialno=0\n"
    )

    # Build metadata.pb as a DeviceState protobuf
    # The AOSP OTA metadata protobuf (ota_metadata.proto) is complex;
    # we produce a minimal but valid one.
    # Key fields (field numbers from AOSP ota_metadata.proto):
    #   DeviceState:
    #     1: repeated DeviceInfo    device_infos
    #     2: string                 meta_build_info (deprecated)
    #     3: int64                  timestamp
    #   DeviceInfo:
    #     1: string                 build
    #     2: string                 build_incremental
    #     3: string                 device
    #     4: int32                  sdk_level
    #     5: repeated string        board
    #     6: repeated string        partition
    #     7: repeated string        group
    #     8: bool                   is_ab
    #     9: bool                   is_device_locked
    #     10: int64                 boot_id
    #     11: string                product
    #     12: repeated string       oem
    #     13: repeated string       property
    #     14: int32                 security_patch_level

    # Encode DeviceInfo
    device_info = encode_message([
        encode_length_delimited(1, fingerprint),         # build
        encode_length_delimited(2, build_number),         # build_incremental
        encode_length_delimited(3, device),               # device
        encode_varint_field(4, int(sdk_level)),           # sdk_level
    ])

    # Encode DeviceState
    import time
    metadata_pb = encode_message([
        encode_length_delimited(1, device_info),          # device_infos (repeated)
        encode_varint_field(3, int(time.time())),          # timestamp
    ])

    return {
        "metadata_text": metadata_text,
        "metadata_pb": metadata_pb,
    }


# ══════════════════════════════════════════════════════════════════════
#  care_map.pb (stub)
# ══════════════════════════════════════════════════════════════════════

def generate_care_map_pb():
    """Generate a minimal stub care_map.pb.

    The care_map protobuf (from boot_control/recovery.h / care_map.proto):
        message CareMap {
            repeated string partitions = 1;
            repeated string ranges    = 2;
        }

    An empty care_map is valid — it means no partitions have special
    care-about ranges.
    """
    # Empty CareMap: just an empty serialized protobuf message
    # (no fields set)
    return b""


# ══════════════════════════════════════════════════════════════════════
#  payload_metadata.bin
# ══════════════════════════════════════════════════════════════════════

def generate_payload_metadata_bin(manifest_bytes):
    """Generate the payload_metadata.bin content.

    This is simply the raw DeltaArchiveManifest protobuf, used by the
    recovery/updater to quickly read metadata without parsing the full
    payload.bin.

    Parameters
    ----------
    manifest_bytes : bytes
        The serialized DeltaArchiveManifest protobuf.

    Returns
    -------
    bytes
    """
    return manifest_bytes


# ══════════════════════════════════════════════════════════════════════
#  Build complete OTA ZIP
# ══════════════════════════════════════════════════════════════════════

def build_ota_zip(payload_path, output_path, device, fingerprint,
                  manifest_bytes=None, cert_path=None):
    """Build a complete flashable OTA ZIP.

    ZIP layout:
        1. payload.bin
        2. payload_properties.txt
        3. payload_metadata.bin
        4. care_map.pb
        5. metadata   (text)
        6. metadata.pb (protobuf)

    Parameters
    ----------
    payload_path : str
        Path to the generated payload.bin.
    output_path : str
        Path for the output OTA ZIP.
    device : str
        Device identifier.
    fingerprint : str
        Build fingerprint string.
    manifest_bytes : bytes or None
        Pre-read manifest protobuf.  If None, read from payload.bin.
    cert_path : str or None
        Path to OTA certificate (.pem or .x509) to include as 'otacert'.

    Returns
    -------
    dict
        'success' : bool
        'output'  : str
        'zip_path': str
        'zip_size': int
    """
    lines = []
    try:
        if manifest_bytes is None:
            manifest_bytes = _read_manifest_from_payload(payload_path)

        # 1. payload_properties.txt
        lines.append("[*] Generating payload_properties.txt")
        props = generate_payload_properties(payload_path, manifest_bytes)

        # 2. metadata and metadata.pb
        # We need a minimal manifest dict; decode it.
        from .protobuf import decode_manifest
        manifest = decode_manifest(manifest_bytes)

        lines.append("[*] Generating metadata")
        meta = generate_ota_metadata(manifest, device, fingerprint)

        # 3. Build the ZIP
        lines.append(f"[*] Building OTA ZIP: {output_path}")
        os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)

        with zipfile.ZipFile(output_path, "w", compression=zipfile.ZIP_DEFLATED) as zf:
            # payload.bin (stored, not compressed — large blob)
            zf.write(payload_path, arcname="payload.bin",
                     compress_type=zipfile.ZIP_STORED)

            # payload_properties.txt
            zf.writestr("payload_properties.txt", props["content"])

            # payload_metadata.bin
            zf.writestr("payload_metadata.bin",
                        generate_payload_metadata_bin(manifest_bytes))

            # care_map.pb
            zf.writestr("care_map.pb", generate_care_map_pb())

            # metadata (text)
            zf.writestr("metadata", meta["metadata_text"])

            # metadata.pb
            zf.writestr("metadata.pb", meta["metadata_pb"])

            # otacert (optional)
            if cert_path and os.path.isfile(cert_path):
                with open(cert_path, "rb") as cf:
                    cert_data = cf.read()
                zf.writestr("otacert", cert_data)
                lines.append(f"[*] Included OTA certificate: {os.path.basename(cert_path)}")

        zip_size = os.path.getsize(output_path)
        payload_size = os.path.getsize(payload_path)

        lines.append(f"[+] OTA ZIP created: {output_path}")
        lines.append(f"    ZIP size:     {human_size(zip_size)}")
        lines.append(f"    Payload size: {human_size(payload_size)}")
        lines.append(f"    Device:       {device}")
        lines.append(f"    Fingerprint:  {fingerprint}")

        return {
            "success": True,
            "output": "\n".join(lines),
            "zip_path": output_path,
            "zip_size": zip_size,
        }

    except Exception as exc:
        lines.append(f"[!] Error building OTA ZIP: {exc}")
        return {
            "success": False,
            "output": "\n".join(lines),
            "error": str(exc),
        }


# ══════════════════════════════════════════════════════════════════════
#  Internal helpers
# ══════════════════════════════════════════════════════════════════════

def _read_manifest_from_payload(payload_path):
    """Read just the manifest protobuf bytes from a payload.bin.

    Returns bytes.
    """
    import struct as _struct

    with open(payload_path, "rb") as f:
        magic = f.read(4)
        if magic != b"CrAU":
            raise ValueError(f"Not a valid payload.bin: magic={magic!r}")

        raw_len = f.read(8)
        header_len = _struct.unpack(">Q", raw_len)[0]
        f.read(header_len)  # skip header protobuf

        from .protobuf import decode_payload_header
        f.seek(4 + 8)
        header_bytes = f.read(header_len)
        header = decode_payload_header(header_bytes)
        manifest_len = header["manifest_len"]

        manifest_bytes = f.read(manifest_len)

    return manifest_bytes
