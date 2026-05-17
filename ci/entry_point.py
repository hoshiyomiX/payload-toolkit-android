#!/usr/bin/env python3
"""
payload_toolkit — AOSP OTA payload.bin manipulation toolkit.

Standalone CLI entry point for cross-compiled aarch64 binary.
Uses only Python stdlib — no external dependencies.

Usage:
    payload_toolkit info  --input payload.bin [-v]
    payload_toolkit dump  --input payload.bin --output /sdcard/output/ [-p system,vendor]
    payload_toolkit gen   --images /sdcard/imgs/ --output payload.bin [-m manifest.json]
    payload_toolkit zip   --images /sdcard/imgs/ --output flashable.zip [-n "OTA Name"]
    payload_toolkit sign  --input payload.bin --key private.pem --output signed.bin
"""
import sys
import os
import argparse
import json

# Ensure payload_toolkit is importable from the same directory
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from payload_toolkit import __version__, main as pt_main


def parse_args():
    parser = argparse.ArgumentParser(
        prog="payload_toolkit",
        description="AOSP OTA payload.bin manipulation toolkit v" + __version__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
Examples:
  %(prog)s info  -i payload.bin
  %(prog)s dump  -i payload.bin -o /sdcard/out/ -p system,vendor,odm_dlkm
  %(prog)s gen   -i /sdcard/imgs/ -o payload.bin
  %(prog)s zip   -i /sdcard/imgs/ -o flashable.zip -n "Custom ROM OTA"
  %(prog)s sign  -i payload.bin -k key.pem -o signed.bin
""",
    )

    sub = parser.add_subparsers(dest="mode", help="Operation mode")

    # info
    p_info = sub.add_parser("info", help="Parse and display payload.bin metadata")
    p_info.add_argument("-i", "--input", required=True, help="Path to payload.bin")

    # dump
    p_dump = sub.add_parser("dump", help="Extract partition images from payload.bin")
    p_dump.add_argument("-i", "--input", required=True, help="Path to payload.bin")
    p_dump.add_argument("-o", "--output", required=True, help="Output directory")
    p_dump.add_argument("-p", "--partitions", default="", help="Comma-separated partitions to extract (default: all)")

    # gen
    p_gen = sub.add_parser("gen", help="Generate payload.bin from .img files")
    p_gen.add_argument("-i", "--input", required=True, help="Directory containing .img files")
    p_gen.add_argument("-o", "--output", default="payload.bin", help="Output payload.bin path")
    p_gen.add_argument("-m", "--manifest", default="", help="Optional OTA manifest JSON")

    # zip
    p_zip = sub.add_parser("zip", help="Generate flashable OTA ZIP from .img files")
    p_zip.add_argument("-i", "--input", required=True, help="Directory containing .img files")
    p_zip.add_argument("-o", "--output", default="flashable.zip", help="Output ZIP path")
    p_zip.add_argument("-n", "--name", default="OTA Update", help="OTA display name")

    # sign
    p_sign = sub.add_parser("sign", help="Sign payload.bin with RSA key")
    p_sign.add_argument("-i", "--input", required=True, help="Path to payload.bin")
    p_sign.add_argument("-k", "--key", required=True, help="Path to RSA private key (PEM)")
    p_sign.add_argument("-o", "--output", default="signed.bin", help="Output signed payload.bin")

    parser.add_argument("-v", "--verbose", action="store_true", help="Verbose output")
    parser.add_argument("--version", action="version", version=f"%(prog)s {__version__}")

    return parser.parse_args()


def run():
    args = parse_args()

    if not args.mode:
        print("Error: No mode specified. Use --help for usage.", file=sys.stderr)
        sys.exit(1)

    # Map CLI args to payload_toolkit main() params
    params = {"mode": args.mode, "verbose": getattr(args, "verbose", False)}

    if args.mode == "info":
        params["input_file"] = args.input

    elif args.mode == "dump":
        params["input_file"] = args.input
        params["output_dir"] = args.output
        if args.partitions:
            params["partitions"] = [p.strip() for p in args.partitions.split(",")]

    elif args.mode == "gen":
        params["images_dir"] = args.input
        params["output_file"] = args.output
        if args.manifest:
            params["manifest_file"] = args.manifest

    elif args.mode == "zip":
        params["images_dir"] = args.input
        params["output_file"] = args.output
        params["ota_name"] = args.name

    elif args.mode == "sign":
        params["input_file"] = args.input
        params["key_file"] = args.key
        params["output_file"] = args.output

    result = pt_main(params)

    if result.get("success"):
        output = result.get("output", "")
        if output:
            print(output)
        sys.exit(0)
    else:
        error = result.get("error", "Unknown error")
        print(f"Error: {error}", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    run()
