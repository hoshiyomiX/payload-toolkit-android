"""
modes/sign.py — Sign payload.bin with RSA key (minimal pure-Python implementation).

Implements PKCS#1 v1.5 RSA signing using only Python stdlib.  No external
dependencies (no 'cryptography', no 'rsa' package).

Called from Kotlin via Chaquopy:
    payload_toolkit.modes.sign.run(
        input_path="/path/to/unsigned.bin",
        output_path="/path/to/signed.bin",
        key_path="/path/to/private_key.pem",
        cert_path="/path/to/cert.pem"
    )

The signing process:
    1. Read the unsigned payload.bin (header + manifest + data)
    2. Compute SHA-256 of the manifest
    3. Build PKCS#1 v1.5 DigestInfo + padding
    4. RSA sign: signature = padded_digest^d mod n  (Python pow with 3 args)
    5. Build Signatures protobuf
    6. Pad file to 4096-byte alignment, append signature block
    7. Update PayloadHeader metadata_signature_len

Returns dict: {'success': True/False, 'output': str}
"""

import base64
import hashlib
import os
import struct
import time

from .. import _report_progress
from ..payload import read_payload, human_size
from ..protobuf import (
    encode_payload_header,
    encode_signature,
    encode_signatures,
    decode_payload_header,
    decode_message,
)


def _parse_args(args, kwargs):
    """Normalise Chaquopy dict-arg vs direct keyword-arg calling convention."""
    if args and isinstance(args[0], dict):
        return args[0]
    return kwargs


# ══════════════════════════════════════════════════════════════════════
#  Minimal ASN.1 DER parser (for RSA key extraction)
# ══════════════════════════════════════════════════════════════════════

def _asn1_read_tag(data, offset):
    """Read an ASN.1 tag byte.  Returns (tag_byte, new_offset)."""
    if offset >= len(data):
        raise ValueError("Truncated ASN.1: no tag byte")
    tag = data[offset]
    return tag, offset + 1


def _asn1_read_length(data, offset):
    """Read an ASN.1 length field.  Returns (length, new_offset)."""
    if offset >= len(data):
        raise ValueError("Truncated ASN.1: no length byte")
    first = data[offset]
    offset += 1
    if first < 0x80:
        return first, offset
    num_bytes = first & 0x7F
    if num_bytes == 0:
        raise ValueError("ASN.1 indefinite-length encoding not supported")
    if offset + num_bytes > len(data):
        raise ValueError("Truncated ASN.1: length field incomplete")
    length = 0
    for i in range(num_bytes):
        length = (length << 8) | data[offset]
        offset += 1
    return length, offset


def _asn1_read_tlv(data, offset):
    """Read one ASN.1 TLV (Tag-Length-Value).

    Returns (tag, value_bytes, new_offset).
    """
    tag, offset = _asn1_read_tag(data, offset)
    length, offset = _asn1_read_length(data, offset)
    if offset + length > len(data):
        raise ValueError(f"Truncated ASN.1 value: need {length} bytes at offset {offset}")
    value = data[offset : offset + length]
    return tag, value, offset + length


def _asn1_read_integer(data):
    """Interpret DER-encoded INTEGER bytes as a Python int."""
    # DER integers are big-endian, two's complement with a leading zero
    # byte if the MSB is set (to distinguish from negative).
    if len(data) == 0:
        return 0
    return int.from_bytes(data, byteorder="big", signed=False)


def _parse_rsa_private_key_pem(pem_text):
    """Extract RSA (n, d, e, key_size_bytes) from a PEM private key.

    Supports both PKCS#1  (BEGIN RSA PRIVATE KEY) and PKCS#8
    (BEGIN PRIVATE KEY) formats.

    Returns
    -------
    tuple (n, d, e, key_size_bytes)
        n : int  — modulus
        d : int  — private exponent
        e : int  — public exponent (usually 65537)
        key_size_bytes : int  — key length in bytes (e.g. 256 for 2048-bit)
    """
    # Strip PEM headers and decode base64
    lines = pem_text.strip().split("\n")
    b64_data = ""
    in_body = False
    for line in lines:
        line = line.strip()
        if line.startswith("-----BEGIN"):
            in_body = True
            continue
        if line.startswith("-----END"):
            break
        if in_body:
            b64_data += line

    der = base64.b64decode(b64_data)

    # Check format
    if b"BEGIN RSA PRIVATE KEY" in pem_text.encode("utf-8"):
        # PKCS#1 format — direct RSA key
        return _parse_pkcs1_rsa_key(der)
    elif b"BEGIN PRIVATE KEY" in pem_text.encode("utf-8"):
        # PKCS#8 format — wrapped RSA key
        return _parse_pkcs8_rsa_key(der)
    else:
        # Try PKCS#1 as fallback
        try:
            return _parse_pkcs1_rsa_key(der)
        except Exception:
            return _parse_pkcs8_rsa_key(der)


def _parse_pkcs1_rsa_key(der):
    """Parse PKCS#1 RSAPrivateKey DER structure."""
    tag, seq, _ = _asn1_read_tlv(der, 0)
    if tag != 0x30:
        raise ValueError(f"Expected SEQUENCE (0x30), got 0x{tag:02X}")

    offset = 0
    # version
    _, version_val, offset = _asn1_read_tlv(seq, offset)
    # n (modulus)
    _, n_bytes, offset = _asn1_read_tlv(seq, offset)
    n = _asn1_read_integer(n_bytes)
    # e (public exponent)
    _, e_bytes, offset = _asn1_read_tlv(seq, offset)
    e = _asn1_read_integer(e_bytes)
    # d (private exponent)
    _, d_bytes, offset = _asn1_read_tlv(seq, offset)
    d = _asn1_read_integer(d_bytes)

    key_size_bytes = (n.bit_length() + 7) // 8
    return n, d, e, key_size_bytes


def _parse_pkcs8_rsa_key(der):
    """Parse PKCS#8 PrivateKeyInfo DER structure to extract RSA key."""
    tag, outer_seq, _ = _asn1_read_tlv(der, 0)
    if tag != 0x30:
        raise ValueError(f"Expected SEQUENCE (0x30), got 0x{tag:02X}")

    offset = 0
    # version
    _, version_val, offset = _asn1_read_tlv(outer_seq, offset)
    # algorithmIdentifier SEQUENCE
    _, algo_seq, offset = _asn1_read_tlv(outer_seq, offset)
    # privateKey OCTET STRING
    priv_tag, priv_octets, offset = _asn1_read_tlv(outer_seq, offset)

    if priv_tag != 0x04:
        raise ValueError(f"Expected OCTET STRING (0x04), got 0x{priv_tag:02X}")

    # The octets contain the PKCS#1 RSA key DER
    return _parse_pkcs1_rsa_key(priv_octets)


# ══════════════════════════════════════════════════════════════════════
#  PKCS#1 v1.5 signing
# ══════════════════════════════════════════════════════════════════════

# DigestInfo DER prefix for SHA-256
# SEQUENCE { SEQUENCE { OID sha256, NULL }, OCTET STRING { 32 bytes } }
_SHA256_DIGEST_INFO_PREFIX = bytes([
    0x30, 0x31,                          # SEQUENCE (49 bytes)
    0x30, 0x0d,                          # SEQUENCE (13 bytes)
    0x06, 0x09,                          # OID (9 bytes)
    0x60, 0x86, 0x48, 0x01, 0x65,       # iso(1).member-body(2).us(840)
    0x03, 0x04, 0x02, 0x01,             # rsadsi(113549).digestAlgorithm(3).sha256(2)
    0x05, 0x00,                          # NULL
    0x04, 0x20,                          # OCTET STRING (32 bytes)
])


def _pkcs1_v15_sign(message_bytes, n, d, key_size_bytes):
    """Sign *message_bytes* using PKCS#1 v1.5 with SHA-256.

    Parameters
    ----------
    message_bytes : bytes
        The data to sign (typically a SHA-256 hash or raw data).
    n : int   — RSA modulus
    d : int   — RSA private exponent
    key_size_bytes : int  — key size in bytes (e.g. 256)

    Returns
    -------
    bytes
        The RSA signature (key_size_bytes long).
    """
    # Step 1: Hash the message with SHA-256
    digest = hashlib.sha256(message_bytes).digest()

    # Step 2: Build DigestInfo = prefix + hash
    digest_info = _SHA256_DIGEST_INFO_PREFIX + digest
    t_len = len(digest_info)  # should be 19 + 32 = 51

    # Step 3: Build EM (Encoded Message)
    # EM = 0x00 || 0x01 || PS || 0x00 || T
    # PS = 0xFF bytes, length = k - 3 - t_len
    k = key_size_bytes
    ps_len = k - 3 - t_len
    if ps_len < 8:
        raise ValueError(
            f"RSA key too short for PKCS#1 v1.5 signing: "
            f"need at least {t_len + 11} bytes, have {k}"
        )

    em = b"\x00\x01" + b"\xff" * ps_len + b"\x00" + digest_info

    # Step 4: Convert EM to integer
    m_int = int.from_bytes(em, byteorder="big")

    # Step 5: RSA signature  s = m^d mod n
    s_int = pow(m_int, d, n)

    # Step 6: Convert back to bytes
    signature = s_int.to_bytes(k, byteorder="big")
    return signature


# ══════════════════════════════════════════════════════════════════════
#  Payload signing
# ══════════════════════════════════════════════════════════════════════

def _build_metadata_signature_block(signatures_blob):
    """Build the metadata signature block for appending to payload.bin.

    Layout (after data blobs):
        [padding to 4096-byte alignment from start of file]
        4 bytes: metadata_signature_length (big-endian uint32)
        N bytes: Signatures protobuf

    Parameters
    ----------
    signatures_blob : bytes
        The serialized Signatures protobuf message.

    Returns
    -------
    bytes
        The complete block: padding + length_prefix + blob
    block_total_size : int
        Total size of padding + length prefix + blob
    """
    # 4-byte length prefix (big-endian uint32) + blob
    blob_section = struct.pack(">I", len(signatures_blob)) + signatures_blob

    return blob_section, len(blob_section)


def _update_header_with_sig_size(header_bytes, metadata_sig_len):
    """Re-encode a PayloadHeader protobuf with an updated metadata_signature_len.

    Parses the existing header, updates field 3, and re-serializes.

    Parameters
    ----------
    header_bytes : bytes
        Original serialized PayloadHeader.
    metadata_sig_len : int
        New value for the metadata_signature_len field.

    Returns
    -------
    bytes
        New serialized PayloadHeader.
    """
    from ..protobuf import decode_message, encode_varint_field, encode_message

    raw = decode_message(header_bytes)
    version = raw.get(1, 2)
    manifest_len = raw.get(2, 0)
    minor_version = raw.get(4)

    parts = [
        encode_varint_field(1, version),
        encode_varint_field(2, manifest_len),
        encode_varint_field(3, metadata_sig_len),
    ]
    if minor_version is not None:
        parts.append(encode_varint_field(4, minor_version))

    return encode_message(parts)


# ══════════════════════════════════════════════════════════════════════
#  Main entry point
# ══════════════════════════════════════════════════════════════════════

def run(*args, **kwargs):
    """Sign payload.bin with RSA key.

    Parameters (via dict or kwargs)
    -------------------------------
    input_path  : str  — Path to unsigned payload.bin
    output_path : str  — Path for signed payload.bin
    key_path    : str  — Path to RSA private key (PEM)
    cert_path   : str  — Path to public certificate (PEM)

    Returns
    -------
    dict
        success : bool
        output  : str
    """
    params = _parse_args(args, kwargs)
    input_path = str(params.get("input_path", ""))
    output_path = str(params.get("output_path", ""))
    key_path = str(params.get("key_path", ""))
    cert_path = str(params.get("cert_path", ""))

    lines = []
    t0 = time.time()

    # ── Validate inputs ──
    if not input_path:
        return {"success": False, "output": "[!] Error: input_path is required",
                "error": "input_path is required"}
    if not output_path:
        return {"success": False, "output": "[!] Error: output_path is required",
                "error": "output_path is required"}
    if not key_path:
        return {"success": False, "output": "[!] Error: key_path is required",
                "error": "key_path is required"}
    if not os.path.isfile(input_path):
        return {"success": False,
                "output": f"[!] Error: input not found: {input_path}",
                "error": f"file not found: {input_path}"}
    if not os.path.isfile(key_path):
        return {"success": False,
                "output": f"[!] Error: key not found: {key_path}",
                "error": f"file not found: {key_path}"}

    try:
        lines.append("═══ PAYLOAD SIGNING ═══")
        lines.append(f"Input:  {input_path}")
        lines.append(f"Output: {output_path}")
        lines.append(f"Key:    {key_path}")
        lines.append("")

        # ── Step 1: Read RSA private key ──
        _report_progress(1, 4, "Reading RSA key")
        lines.append("[*] Reading RSA private key...")

        with open(key_path, "r") as f:
            pem_text = f.read()

        n, d, e, key_size_bytes = _parse_rsa_private_key_pem(pem_text)
        key_bits = key_size_bytes * 8

        lines.append(f"    Key size: {key_bits} bits")
        lines.append(f"    Public exponent: {e}")

        # ── Step 2: Read payload and extract manifest ──
        _report_progress(2, 4, "Reading payload")
        lines.append("[*] Reading payload.bin...")

        info = read_payload(input_path)
        header = info["header"]
        manifest_bytes = info["manifest_bytes"]

        # Also read the raw header protobuf bytes (we need to re-encode it)
        with open(input_path, "rb") as f:
            magic = f.read(4)
            raw_header_len = f.read(8)
            orig_header_len = struct.unpack(">Q", raw_header_len)[0]
            original_header_bytes = f.read(orig_header_len)

        # The data section (manifest + blobs) that we preserve
        with open(input_path, "rb") as f:
            f.seek(4 + 8 + orig_header_len)  # skip to after header
            payload_body = f.read()  # everything after header

        # ── Step 3: Sign the manifest ──
        _report_progress(3, 4, "Signing manifest")
        lines.append("[*] Signing manifest (SHA-256 + RSA PKCS#1 v1.5)...")

        signature_bytes = _pkcs1_v15_sign(
            manifest_bytes, n, d, key_size_bytes
        )
        lines.append(f"    Signature: {len(signature_bytes)} bytes")
        lines.append(f"    SHA-256(sig): {hashlib.sha256(signature_bytes).hexdigest()[:32]}...")

        # ── Step 4: Build signatures protobuf ──
        sig_msg = encode_signature(
            version=1,
            data=signature_bytes,
            unpadded_data_size=len(signature_bytes),
        )
        signatures_blob = encode_signatures([sig_msg])

        # ── Step 5: Build metadata signature block ──
        sig_block, sig_block_size = _build_metadata_signature_block(signatures_blob)

        # ── Step 6: Pad file to 4096-byte alignment ──
        # Current size: 4 (magic) + 8 (header_len) + header_len + body_len
        current_end = 4 + 8 + orig_header_len + len(payload_body)
        alignment = 4096
        pad_needed = (alignment - (current_end % alignment)) % alignment

        # ── Step 7: Update header with new metadata_signature_len ──
        _report_progress(4, 4, "Writing signed payload")
        new_header_bytes = _update_header_with_sig_size(
            original_header_bytes, sig_block_size
        )

        # Check if header size changed (it might due to varint encoding)
        # The new header must fit in the original header_len space, or we
        # need to adjust.  Since we're only changing metadata_signature_len
        # (which is likely going from 0 to a small value), the header size
        # should be similar.  But to be safe, we'll use the new header's
        # actual length.

        new_header_len = len(new_header_bytes)

        # If the new header is smaller, pad it with zeros to maintain alignment
        if new_header_len < orig_header_len:
            new_header_bytes += b"\x00" * (orig_header_len - new_header_len)
            new_header_len = orig_header_len
        elif new_header_len > orig_header_len:
            # Very unlikely, but handle it by using the larger size
            lines.append(f"    [!] Header grew from {orig_header_len} to {new_header_len} bytes")
            # Update the padding
            current_end = 4 + 8 + new_header_len + len(payload_body)
            pad_needed = (alignment - (current_end % alignment)) % alignment

        # ── Step 8: Write the signed payload ──
        lines.append("[*] Writing signed payload.bin...")
        os.makedirs(os.path.dirname(output_path) or ".", exist_ok=True)

        with open(output_path, "wb") as f:
            # Magic
            f.write(b"CrAU")
            # Header protobuf length
            f.write(struct.pack(">Q", new_header_len))
            # Header protobuf
            f.write(new_header_bytes[:new_header_len])
            # Payload body (manifest + data blobs)
            f.write(payload_body)
            # Padding to 4096-byte alignment
            f.write(b"\x00" * pad_needed)
            # Metadata signature block
            f.write(sig_block)

        output_size = os.path.getsize(output_path)
        input_size = os.path.getsize(input_path)

        lines.append(f"[+] Signed payload written: {output_path}")
        lines.append(f"    Input size:  {human_size(input_size)}")
        lines.append(f"    Output size: {human_size(output_size)}")
        lines.append(f"    Added:       {human_size(pad_needed + sig_block_size)} "
                     f"(padding: {pad_needed}, sig: {sig_block_size})")

        # ── Verify ──
        lines.append("")
        lines.append("[*] Verifying signed payload...")
        try:
            verify_info = read_payload(output_path)
            verify_header = verify_info["header"]
            lines.append(f"    Version: {verify_header['version']}")
            lines.append(f"    Metadata sig len: {verify_header['metadata_signature_len']}")
            if verify_header["metadata_signature_len"] == sig_block_size:
                lines.append("    [+] Signature metadata length matches ✓")
            else:
                lines.append(f"    [!] Signature metadata length mismatch: "
                             f"expected {sig_block_size}, got {verify_header['metadata_signature_len']}")
        except Exception as verify_err:
            lines.append(f"    [!] Verification warning: {verify_err}")

        # Read cert if provided and include its info
        if cert_path and os.path.isfile(cert_path):
            with open(cert_path, "rb") as cf:
                cert_data = cf.read()
            lines.append(f"    Certificate: {os.path.basename(cert_path)} "
                         f"({human_size(len(cert_data))})")

        elapsed = time.time() - t0
        lines.append(f"\n[+] Signing completed in {elapsed:.1f}s")

        output = "\n".join(lines)
        print(output)

        return {
            "success": True,
            "output": output,
            "output_path": output_path,
            "file_size": output_size,
            "signature_size": sig_block_size,
            "key_bits": key_bits,
        }

    except Exception as exc:
        err_msg = f"[!] Error signing payload: {exc}"
        lines.append(err_msg)
        import traceback
        lines.append(traceback.format_exc())
        output = "\n".join(lines)
        print(output)
        return {"success": False, "output": output, "error": str(exc)}
