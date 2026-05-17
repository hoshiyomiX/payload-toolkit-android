"""
protobuf.py — Minimal protobuf wire-format encoder / decoder for AOSP
update_engine payload.bin.

This module implements only the subset of protobuf needed to parse and
construct the messages used by AOSP's Brillo update payload format.  It
does NOT depend on Google's protobuf library.

Supported wire types
--------------------
    0  Varint               (int32, int64, uint32, uint64, bool, enum)
    1  64-bit fixed         (fixed64, sfixed64, double)
    2  Length-delimited     (string, bytes, embedded messages, packed fields)
    5  32-bit fixed         (fixed32, sfixed32, float)

AOSP message schemas (field numbers only — types are implied by usage):
    DeltaArchiveManifest
        1  Signatures              signatures
        2  repeated InstallOperation install_operations
        3  uint64                  block_size
        4  uint32                  minor_version
        5  repeated PartitionUpdate partitions
        6  uint64                  max_timestamp
        7  DynamicPartitionMetadata dynamic_partition_metadata
        8  bool                    minor_version_applied
        9  repeated DynamicPartitionGroup groups
       11  bool                    has_source_metadata

    PartitionUpdate
        1  string                  partition_name
        2  bool                    run_postinstall
        3  repeated InstallOperation install_operations
        4  PartitionInfo           old_partition_info
        5  PartitionInfo           new_partition_info
        6  PostInstallHook         postinstall_hook
        7  repeated Extent         old_partition_info.extents (v1 compat)

    InstallOperation
        1  uint32 (enum)           type
        2  uint64                  data_offset
        3  uint64                  data_length
        5  uint32                  src_length
        6  repeated Extent         src_extents
        7  repeated Extent         dst_extents
        8  uint32                  dst_length
        9  bytes                   data_sha256_hash

    Extent
        1  uint64                  start_block
        2  uint64                  num_blocks

    PartitionInfo
        1  uint64                  partition_size
        2  bytes                   hash

    Signatures
        1  repeated Signature      signatures

    Signature
        1  uint32                  version
        2  bytes                   data
        3  uint32                  unpadded_data_size

    PayloadHeader  (outer file-level header, NOT DeltaArchiveManifest)
        1  uint64                  version
        2  uint64                  manifest_len
        3  uint64                  metadata_signature_len
        4  uint32                  minor_version

Reference: system/update_engine/update_metadata.proto
"""

import struct

# ── Wire-type constants ────────────────────────────────────────────────

WIRE_VARINT = 0
WIRE_64BIT = 1
WIRE_LENGTH_DELIMITED = 2
WIRE_32BIT = 5

# ── AOSP InstallOperation type enum ────────────────────────────────────

OP_REPLACE = 0
OP_REPLACE_BZ = 12
OP_REPLACE_XZ = 8
OP_MOVE = 1
OP_SOURCE_COPY = 3
OP_SOURCE_BSDIFF = 4
OP_REPLACE_V2 = 14       # a.k.a. PUIGZIP
OP_PUIGZIP = OP_REPLACE_V2  # Alias
OP_BROTLI_BSDIFF = 23
OP_BROTLI_BZ = OP_BROTLI_BSDIFF   # Alias used in payload.py / task spec
OP_ZERO = 21
OP_DISCARD = 22
OP_REPLACE_BROT = 13

OP_TYPE_NAMES = {
    OP_REPLACE: "REPLACE",
    OP_REPLACE_BZ: "REPLACE_BZ",
    OP_REPLACE_XZ: "REPLACE_XZ",
    OP_MOVE: "MOVE",
    OP_SOURCE_COPY: "SOURCE_COPY",
    OP_SOURCE_BSDIFF: "SOURCE_BSDIFF",
    OP_REPLACE_V2: "PUIGZIP",
    OP_BROTLI_BSDIFF: "BROTLI_BZ",
    OP_BROTLI_BZ: "BROTLI_BZ",
    OP_ZERO: "ZERO",
    OP_DISCARD: "DISCARD",
    OP_REPLACE_BROT: "REPLACE_BROT",
}


# ══════════════════════════════════════════════════════════════════════
#  Low-level encoding helpers
# ══════════════════════════════════════════════════════════════════════

def encode_varint(value):
    """Encode a non-negative integer as a protobuf base-128 varint."""
    if value < 0:
        # Protobuf treats negative values as unsigned 64-bit.
        value = value & 0xFFFFFFFFFFFFFFFF
    buf = bytearray()
    while value > 0x7F:
        buf.append((value & 0x7F) | 0x80)
        value >>= 7
    buf.append(value & 0x7F)
    return bytes(buf)


def decode_varint(data, offset):
    """Decode a varint starting at *offset*.

    Returns (value, new_offset).
    """
    result = 0
    shift = 0
    while True:
        if offset >= len(data):
            raise ValueError("Truncated varint at offset %d" % offset)
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if not (byte & 0x80):
            break
        shift += 7
        if shift >= 64:
            raise ValueError("Varint too long (>10 bytes)")
    return result, offset


def make_tag(field_number, wire_type):
    """Build a field tag = (field_number << 3) | wire_type."""
    return (field_number << 3) | wire_type


def parse_tag(tag_value):
    """Extract (field_number, wire_type) from a tag."""
    return tag_value >> 3, tag_value & 0x07


# ── Field-level encoders ──────────────────────────────────────────────

def encode_field(field_number, wire_type, data):
    """Encode a single protobuf field (tag + value).

    Parameters
    ----------
    field_number : int
    wire_type : int  (WIRE_VARINT / WIRE_64BIT / WIRE_LENGTH_DELIMITED / WIRE_32BIT)
    data : bytes | int | bytearray | str
        The raw value.  For WIRE_VARINT pass an int; for others pass bytes.
    """
    tag_bytes = encode_varint(make_tag(field_number, wire_type))
    if wire_type == WIRE_VARINT:
        if isinstance(data, bool):
            data = 1 if data else 0
        return tag_bytes + encode_varint(int(data))
    if wire_type == WIRE_64BIT:
        return tag_bytes + struct.pack("<Q", int(data))
    if wire_type == WIRE_32BIT:
        return tag_bytes + struct.pack("<I", int(data))
    if wire_type == WIRE_LENGTH_DELIMITED:
        if isinstance(data, (str,)):
            data = data.encode("utf-8")
        elif not isinstance(data, (bytes, bytearray)):
            raise TypeError(
                f"WIRE_LENGTH_DELIMITED requires bytes/str, got {type(data)}"
            )
        return tag_bytes + encode_varint(len(data)) + bytes(data)
    raise ValueError(f"Unsupported wire type: {wire_type}")


def encode_length_delimited(field_number, data):
    """Shorthand for a length-delimited field."""
    return encode_field(field_number, WIRE_LENGTH_DELIMITED, data)


def encode_varint_field(field_number, value):
    """Shorthand for a varint field."""
    return encode_field(field_number, WIRE_VARINT, value)


def encode_fixed64_field(field_number, value):
    """Shorthand for a 64-bit fixed field."""
    return encode_field(field_number, WIRE_64BIT, value)


def encode_fixed32_field(field_number, value):
    """Shorthand for a 32-bit fixed field."""
    return encode_field(field_number, WIRE_32BIT, value)


# ══════════════════════════════════════════════════════════════════════
#  Message-level encode / decode
# ══════════════════════════════════════════════════════════════════════

def encode_message(encoded_fields):
    """Concatenate pre-encoded field blobs into a single protobuf message.

    *encoded_fields* is an iterable of bytes-like objects (the return value
    of encode_field, encode_varint_field, etc.).
    """
    parts = []
    for f in encoded_fields:
        if f:
            parts.append(bytes(f))
    return b"".join(parts)


def encode_message_from_dict(fields):
    """Encode a protobuf message from a dict {field_number: value}.

    Heuristics:
        int / bool  →  varint
        bytes / str  →  length-delimited
        list         →  repeated (each element encoded with the same heuristic)
    """
    parts = []
    for fn, val in fields.items():
        if isinstance(val, list):
            for item in val:
                parts.append(_encode_value(fn, item))
        else:
            parts.append(_encode_value(fn, val))
    return b"".join(parts)


def _encode_value(field_number, value):
    """Encode a single value using type-based heuristic."""
    if isinstance(value, bool):
        return encode_varint_field(field_number, 1 if value else 0)
    if isinstance(value, int):
        return encode_varint_field(field_number, value)
    if isinstance(value, (bytes, bytearray)):
        return encode_length_delimited(field_number, value)
    if isinstance(value, str):
        return encode_length_delimited(field_number, value)
    if isinstance(value, float):
        return encode_fixed64_field(field_number, struct.pack("<d", value))
    raise TypeError(
        f"Cannot encode field {field_number}: unsupported type {type(value)}"
    )


# ── Decoder ───────────────────────────────────────────────────────────

def decode_message(data):
    """Decode a protobuf message from raw bytes.

    Returns a dict mapping field numbers to values:
        - varint  →  int
        - 64-bit  →  bytes (raw 8 bytes, little-endian)
        - length-delimited  →  bytes (raw content)
        - 32-bit  →  bytes (raw 4 bytes, little-endian)

    Repeated fields appear as a list.
    """
    if not data:
        return {}
    if not isinstance(data, (bytes, bytearray)):
        raise TypeError("decode_message expects bytes, got %s" % type(data))

    fields = {}
    offset = 0
    length = len(data)

    while offset < length:
        # ── tag ──
        tag_val, offset = decode_varint(data, offset)
        field_number, wire_type = parse_tag(tag_val)

        if field_number == 0:
            # field_number 0 is reserved / invalid; stop gracefully.
            break

        # ── value ──
        if wire_type == WIRE_VARINT:
            value, offset = decode_varint(data, offset)

        elif wire_type == WIRE_64BIT:
            if offset + 8 > length:
                raise ValueError(
                    f"Truncated 64-bit field {field_number} at offset {offset}"
                )
            value = data[offset : offset + 8]
            offset += 8

        elif wire_type == WIRE_LENGTH_DELIMITED:
            inner_len, offset = decode_varint(data, offset)
            if offset + inner_len > length:
                raise ValueError(
                    f"Truncated length-delimited field {field_number}: "
                    f"need {inner_len} bytes at offset {offset}, have {length - offset}"
                )
            value = data[offset : offset + inner_len]
            offset += inner_len

        elif wire_type == WIRE_32BIT:
            if offset + 4 > length:
                raise ValueError(
                    f"Truncated 32-bit field {field_number} at offset {offset}"
                )
            value = data[offset : offset + 4]
            offset += 4

        else:
            raise ValueError(
                f"Unknown wire type {wire_type} for field {field_number} "
                f"at offset {offset - len(encode_varint(tag_val))}"
            )

        # ── accumulate (handle repeated fields) ──
        _add_field(fields, field_number, value)

    return fields


def _add_field(fields, field_number, value):
    """Insert *value* into *fields*, coalescing repeated occurrences."""
    if field_number in fields:
        existing = fields[field_number]
        if isinstance(existing, list):
            existing.append(value)
        else:
            fields[field_number] = [existing, value]
    else:
        fields[field_number] = value


# ══════════════════════════════════════════════════════════════════════
#  AOSP-specific decode helpers
# ══════════════════════════════════════════════════════════════════════

def _as_list(val):
    """Normalise a value that might be singular or a list into a list."""
    if val is None:
        return []
    if isinstance(val, list):
        return val
    return [val]


def decode_payload_header(data):
    """Decode a PayloadHeader protobuf message.

    Returns a dict with keys:
        version (int), manifest_len (int), metadata_signature_len (int),
        minor_version (int or None).
    """
    raw = decode_message(data)
    return {
        "version": raw.get(1, 0),
        "manifest_len": raw.get(2, 0),
        "metadata_signature_len": raw.get(3, 0),
        "minor_version": raw.get(4),
    }


def encode_payload_header(version=2, manifest_len=0,
                          metadata_signature_len=0, minor_version=0):
    """Encode a PayloadHeader protobuf message.

    Returns bytes.
    """
    parts = [
        encode_varint_field(1, version),
        encode_varint_field(2, manifest_len),
        encode_varint_field(3, metadata_signature_len),
    ]
    if minor_version is not None:
        parts.append(encode_varint_field(4, minor_version))
    return encode_message(parts)


def decode_install_operation(data):
    """Decode a single InstallOperation protobuf message.

    Returns a dict with type, data_offset, data_length, src_length,
    src_extents, dst_extents, data_sha256_hash, dst_length.
    """
    raw = decode_message(data)
    op_type = raw.get(1, OP_REPLACE)
    return {
        "type": op_type,
        "type_name": OP_TYPE_NAMES.get(op_type, f"UNKNOWN({op_type})"),
        "data_offset": raw.get(2, 0),
        "data_length": raw.get(3, 0),
        "src_length": raw.get(5, 0),
        "dst_length": raw.get(8, 0),
        "src_extents": [decode_extent(e) for e in _as_list(raw.get(6, []))],
        "dst_extents": [decode_extent(e) for e in _as_list(raw.get(7, []))],
        "data_sha256_hash": raw.get(9, b""),
    }


def decode_extent(data):
    """Decode an Extent protobuf message."""
    raw = decode_message(data)
    return {
        "start_block": raw.get(1, 0),
        "num_blocks": raw.get(2, 0),
    }


def decode_partition_info(data):
    """Decode a PartitionInfo protobuf message."""
    raw = decode_message(data)
    return {
        "partition_size": raw.get(1, 0),
        "hash": raw.get(2, b""),
    }


def decode_partition_update(data):
    """Decode a PartitionUpdate protobuf message.

    Returns a dict with partition_name, run_postinstall,
    install_operations (list), old_partition_info, new_partition_info.
    """
    raw = decode_message(data)
    ops_raw = _as_list(raw.get(3, []))
    ops = [decode_install_operation(op) for op in ops_raw if op]

    old_info = None
    if 4 in raw:
        old_info = decode_partition_info(raw[4])

    new_info = None
    if 5 in raw:
        new_info = decode_partition_info(raw[5])

    return {
        "partition_name": raw.get(1, b"").decode("utf-8", errors="replace"),
        "run_postinstall": bool(raw.get(2, 0)),
        "install_operations": ops,
        "old_partition_info": old_info,
        "new_partition_info": new_info,
    }


def decode_signatures(data):
    """Decode a Signatures protobuf message.

    Returns a list of signature dicts.
    """
    raw = decode_message(data)
    sigs = []
    for sig_data in _as_list(raw.get(1, [])):
        if not sig_data:
            continue
        sig_raw = decode_message(sig_data)
        sigs.append({
            "version": sig_raw.get(1, 0),
            "data": sig_raw.get(2, b""),
            "unpadded_data_size": sig_raw.get(3, 0),
        })
    return sigs


def decode_manifest(data):
    """Decode a DeltaArchiveManifest protobuf message.

    Returns a dict with block_size, minor_version, partitions (list of
    PartitionUpdate dicts), install_operations (list), signatures, etc.
    """
    raw = decode_message(data)

    # Parse partition updates
    partitions = []
    for part_data in _as_list(raw.get(5, [])):
        if part_data:
            try:
                partitions.append(decode_partition_update(part_data))
            except Exception:
                partitions.append({"partition_name": "<parse_error>", "install_operations": []})

    # Parse top-level install_operations (full OTA sometimes uses these)
    top_ops = []
    for op_data in _as_list(raw.get(2, [])):
        if op_data:
            try:
                top_ops.append(decode_install_operation(op_data))
            except Exception:
                pass

    # Parse signatures
    signatures = []
    if 1 in raw:
        try:
            signatures = decode_signatures(raw[1])
        except Exception:
            pass

    return {
        "raw": raw,
        "block_size": raw.get(3, 4096),
        "minor_version": raw.get(4, 0),
        "partitions": partitions,
        "install_operations": top_ops,
        "signatures": signatures,
        "max_timestamp": raw.get(6, 0),
        "has_source_metadata": bool(raw.get(11, 0)),
    }


# ══════════════════════════════════════════════════════════════════════
#  AOSP-specific encode helpers
# ══════════════════════════════════════════════════════════════════════

def encode_extent(start_block, num_blocks):
    """Encode an Extent message."""
    return encode_message([
        encode_varint_field(1, start_block),
        encode_varint_field(2, num_blocks),
    ])


def encode_partition_info(partition_size, hash_bytes=b""):
    """Encode a PartitionInfo message."""
    parts = [encode_varint_field(1, partition_size)]
    if hash_bytes:
        parts.append(encode_length_delimited(2, hash_bytes))
    return encode_message(parts)


def encode_install_operation(op_type, data_offset=0, data_length=0,
                             src_extents=None, dst_extents=None,
                             data_sha256_hash=b"", src_length=0,
                             dst_length=0):
    """Encode an InstallOperation message."""
    parts = [encode_varint_field(1, op_type)]
    if data_offset:
        parts.append(encode_varint_field(2, data_offset))
    if data_length:
        parts.append(encode_varint_field(3, data_length))
    if src_length:
        parts.append(encode_varint_field(5, src_length))
    if dst_length:
        parts.append(encode_varint_field(8, dst_length))
    for ext in (src_extents or []):
        parts.append(encode_length_delimited(6, ext))
    for ext in (dst_extents or []):
        parts.append(encode_length_delimited(7, ext))
    if data_sha256_hash:
        parts.append(encode_length_delimited(9, data_sha256_hash))
    return encode_message(parts)


def encode_partition_update(name, install_operations=None,
                            old_partition_info=None, new_partition_info=None,
                            run_postinstall=False):
    """Encode a PartitionUpdate message."""
    parts = [encode_length_delimited(1, name)]
    if run_postinstall:
        parts.append(encode_varint_field(2, 1))
    for op in (install_operations or []):
        parts.append(encode_length_delimited(3, op))
    if old_partition_info:
        parts.append(encode_length_delimited(4, old_partition_info))
    if new_partition_info:
        parts.append(encode_length_delimited(5, new_partition_info))
    return encode_message(parts)


def encode_signature(version=1, data=b"", unpadded_data_size=0):
    """Encode a single Signature message."""
    parts = [encode_varint_field(1, version)]
    if data:
        parts.append(encode_length_delimited(2, data))
    if unpadded_data_size:
        parts.append(encode_varint_field(3, unpadded_data_size))
    return encode_message(parts)


def encode_signatures(signatures):
    """Encode a Signatures message wrapping a list of Signature messages."""
    parts = []
    for sig in signatures:
        parts.append(encode_length_delimited(1, sig))
    return encode_message(parts)


def encode_manifest(block_size=4096, minor_version=0,
                    partitions=None, install_operations=None,
                    signatures_msg=None):
    """Encode a DeltaArchiveManifest message.

    Parameters
    ----------
    block_size : int
    minor_version : int
    partitions : list of encoded PartitionUpdate blobs (bytes)
    install_operations : list of encoded InstallOperation blobs (bytes)
    signatures_msg : bytes  (encoded Signatures blob, or None)

    Returns bytes.
    """
    parts = []

    # Field 1: signatures
    if signatures_msg:
        parts.append(encode_length_delimited(1, signatures_msg))

    # Field 2: top-level install_operations (used for full OTA)
    for op in (install_operations or []):
        parts.append(encode_length_delimited(2, op))

    # Field 3: block_size
    parts.append(encode_varint_field(3, block_size))

    # Field 4: minor_version
    if minor_version is not None:
        parts.append(encode_varint_field(4, minor_version))

    # Field 5: partitions
    for part in (partitions or []):
        parts.append(encode_length_delimited(5, part))

    return encode_message(parts)
