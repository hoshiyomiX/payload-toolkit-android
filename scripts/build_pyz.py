#!/usr/bin/env python3
"""
build_pyz.py — Build payload_toolkit.pyz (architecture-independent zipapp).

Produces a single-file Python application that runs on ANY platform
with Python 3.8+, including Android (Termux), Linux, macOS, and Windows.

No cross-compilation, no NDK, no Docker needed. Pure Python bytecode
in a PEP 441 zipapp format.

Usage:
    python3 scripts/build_pyz.py              # Build to dist/payload_toolkit.pyz
    python3 scripts/build_pyz.py -o out.pyz   # Custom output path

Output:
    dist/payload_toolkit.pyz  (~35 KB, compressed)
"""
import os
import sys
import zipfile
import hashlib
import tempfile
import shutil
import zipapp

# ── Configuration ────────────────────────────────────────────────────────

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.abspath(os.path.join(SCRIPT_DIR, ".."))
SRC_DIR = os.path.join(PROJECT_ROOT, "src")
DEFAULT_OUTPUT = os.path.join(PROJECT_ROOT, "dist", "payload_toolkit.pyz")
ENTRY_POINT_NAME = "__main__.py"


# ── Entry point source ──────────────────────────────────────────────────

ENTRY_POINT = '''#!/usr/bin/env python3
"""
payload_toolkit.pyz -- AOSP OTA payload.bin manipulation toolkit.
Architecture-independent zipapp. Requires Python 3.8+.
Works on Android (Termux), Linux, macOS, Windows.
"""
import sys
import os


def scan_images_dir(images_dir):
    """Scan directory for .img files -> {partition_name: full_path} dict."""
    images = {}
    if not os.path.isdir(images_dir):
        return None
    for fname in sorted(os.listdir(images_dir)):
        if fname.lower().endswith(".img"):
            name = os.path.splitext(fname)[0]
            images[name] = os.path.join(images_dir, fname)
    return images if images else None


def _print_help(version):
    print("payload_toolkit v%s -- AOSP OTA payload.bin manipulation toolkit" % version)
    print()
    print("Usage: python3 payload_toolkit.pyz <mode> [options]")
    print()
    print("Modes:")
    print("  info  -i <payload.bin>                       Parse payload.bin metadata")
    print("  dump  -i <payload.bin> -o <dir> [-p p1,p2]   Extract partition images")
    print("  gen   -i <img_dir> -o <output.bin> [-c alg]  Generate payload.bin")
    print("  zip   -i <img_dir> -o <output.zip> [-n name] Generate flashable OTA ZIP")
    print("  sign  -i <payload.bin> -k <key.pem> [-o out] Sign payload.bin")
    print()
    print("Options:")
    print("  -c, --compress <alg>  Compression: none, bzip2, gzip, xz, brotli")
    print("  -i, --input <path>    Input file or directory")
    print("  -k, --key <path>      RSA private key (PEM)")
    print("  -n, --name <string>   OTA display name")
    print("  -o, --output <path>   Output file path")
    print("  -p, --partitions <p>  Comma-separated partition names")
    print("  -v, --verbose         Verbose output")
    print("  --version             Show version")
    print("  -h, --help            Show this help")


def main():
    from payload_toolkit import __version__, main as pt_main

    args = sys.argv[1:]

    if not args or args[0] in ("-h", "--help", "help"):
        _print_help(__version__)
        sys.exit(0)

    if args[0] in ("--version", "-V"):
        print("payload_toolkit v%s" % __version__)
        sys.exit(0)

    mode = args[0].lower()
    supported = ("info", "dump", "gen", "zip", "sign")

    if mode not in supported:
        print("Error: Unknown mode \\'%s\\'. Supported: %s" % (mode, ", ".join(supported)),
              file=sys.stderr)
        _print_help(__version__)
        sys.exit(1)

    # Parse options (order-independent)
    opts = {}
    verbose = False
    i = 1
    while i < len(args):
        a = args[i]
        if a in ("-v", "--verbose"):
            verbose = True
        elif a in ("-i", "--input") and i + 1 < len(args):
            opts["input"] = args[i + 1]; i += 1
        elif a in ("-o", "--output") and i + 1 < len(args):
            opts["output"] = args[i + 1]; i += 1
        elif a in ("-p", "--partitions") and i + 1 < len(args):
            opts["partitions"] = args[i + 1]; i += 1
        elif a in ("-c", "--compress") and i + 1 < len(args):
            opts["compress"] = args[i + 1]; i += 1
        elif a in ("-n", "--name") and i + 1 < len(args):
            opts["name"] = args[i + 1]; i += 1
        elif a in ("-k", "--key") and i + 1 < len(args):
            opts["key"] = args[i + 1]; i += 1
        elif a.startswith("-"):
            print("Error: Unknown option: %s" % a, file=sys.stderr)
            sys.exit(1)
        i += 1

    # Map CLI opts -> internal API param names per mode
    #   info  -> payload_path, verbose
    #   dump  -> payload_path, output_dir, partitions
    #   gen   -> images, output_path, compress
    #   zip   -> images, output_path, compress, device
    #   sign  -> input_path, output_path, key_path
    params = {"mode": mode, "verbose": verbose}

    if mode == "info":
        if "input" not in opts:
            print("Error: -i <payload.bin> is required", file=sys.stderr)
            sys.exit(1)
        params["payload_path"] = opts["input"]

    elif mode == "dump":
        if "input" not in opts:
            print("Error: -i <payload.bin> is required", file=sys.stderr)
            sys.exit(1)
        if "output" not in opts:
            print("Error: -o <output_dir> is required", file=sys.stderr)
            sys.exit(1)
        params["payload_path"] = opts["input"]
        params["output_dir"] = opts["output"]
        if opts.get("partitions"):
            params["partitions"] = [p.strip() for p in opts["partitions"].split(",")]

    elif mode == "gen":
        if "input" not in opts:
            print("Error: -i <images_dir> is required", file=sys.stderr)
            sys.exit(1)
        images = scan_images_dir(opts["input"])
        if not images:
            print("Error: No .img files found in %s" % opts["input"], file=sys.stderr)
            sys.exit(1)
        params["images"] = images
        params["output_path"] = opts.get("output", "payload.bin")
        params["compress"] = opts.get("compress", "none")

    elif mode == "zip":
        if "input" not in opts:
            print("Error: -i <images_dir> is required", file=sys.stderr)
            sys.exit(1)
        images = scan_images_dir(opts["input"])
        if not images:
            print("Error: No .img files found in %s" % opts["input"], file=sys.stderr)
            sys.exit(1)
        params["images"] = images
        params["output_path"] = opts.get("output", "flashable.zip")
        params["compress"] = opts.get("compress", "gzip")
        if opts.get("name"):
            params["device"] = opts["name"]

    elif mode == "sign":
        if "input" not in opts:
            print("Error: -i <payload.bin> is required", file=sys.stderr)
            sys.exit(1)
        if "key" not in opts:
            print("Error: -k <private_key.pem> is required", file=sys.stderr)
            sys.exit(1)
        params["input_path"] = opts["input"]
        params["key_path"] = opts["key"]
        params["output_path"] = opts.get("output", "signed.bin")

    result = pt_main(params)

    if result.get("success"):
        sys.exit(0)
    else:
        error = result.get("error", "Unknown error")
        print("Error: %s" % error, file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
'''


# ── Build logic ──────────────────────────────────────────────────────────

def collect_source_files(src_dir):
    """Collect all .py files from payload_toolkit package."""
    pkg_dir = os.path.join(src_dir, "payload_toolkit")
    if not os.path.isdir(pkg_dir):
        print("Error: payload_toolkit package not found at %s" % pkg_dir)
        sys.exit(1)

    files = []
    for root, _dirs, filenames in os.walk(pkg_dir):
        for fname in sorted(filenames):
            if fname.endswith(".py"):
                full = os.path.join(root, fname)
                rel = os.path.relpath(full, src_dir)
                files.append((full, rel))

    return files


def build_pyz(output_path=DEFAULT_OUTPUT):
    """Build the .pyz zipapp."""
    source_files = collect_source_files(SRC_DIR)
    print("Collected %d Python source files" % len(source_files))

    os.makedirs(os.path.dirname(output_path), exist_ok=True)

    with tempfile.TemporaryDirectory() as tmpdir:
        # Copy package sources preserving structure
        for full_path, rel_path in source_files:
            dest = os.path.join(tmpdir, rel_path)
            os.makedirs(os.path.dirname(dest), exist_ok=True)
            shutil.copy2(full_path, dest)

        # Write the CLI entry point
        main_path = os.path.join(tmpdir, ENTRY_POINT_NAME)
        with open(main_path, "w", encoding="utf-8") as f:
            f.write(ENTRY_POINT)

        # Create the zipapp (PEP 441)
        zipapp.create_archive(
            source=tmpdir,
            target=output_path,
            interpreter="/usr/bin/env python3",
            compressed=True,
        )

    # Verify and report
    if not os.path.exists(output_path):
        print("ERROR: Failed to create %s" % output_path)
        sys.exit(1)

    size = os.path.getsize(output_path)
    sha = hashlib.sha256(open(output_path, "rb").read()).hexdigest()

    # Validate zip structure
    with zipfile.ZipFile(output_path, "r") as zf:
        names = zf.namelist()

    print()
    print("  Built:   %s" % output_path)
    print("  Size:    %d bytes (%.1f KB)" % (size, size / 1024))
    print("  SHA256:  %s" % sha)
    print("  Entries: %d" % len(names))
    print("  Runs on: any platform with Python 3.8+")
    print()
    print("  Deploy to Android:")
    print("    adb push %s /data/local/tmp/" % output_path)
    print("    adb shell python3 /data/local/tmp/payload_toolkit.pyz info -i /sdcard/payload.bin")


def main():
    # Parse build script arguments
    output = DEFAULT_OUTPUT
    for arg in sys.argv[1:]:
        if arg in ("-o", "--output") and sys.argv.index(arg) + 1 < len(sys.argv):
            idx = sys.argv.index(arg)
            output = sys.argv[idx + 1]
        elif arg in ("-h", "--help"):
            print(__doc__)
            sys.exit(0)

    print("=" * 60)
    print("  payload_toolkit.pyz builder")
    print("=" * 60)
    print()

    build_pyz(output)


if __name__ == "__main__":
    main()
