#!/usr/bin/env python3
"""Local source/Workshop packaging. Does not build binaries or publish anything."""
from __future__ import annotations

import argparse
from pathlib import Path

from distribution.bundle import workshop, write_zip
from distribution.snapshot import Snapshot, git


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=("manifest", "sources", "workshop"))
    parser.add_argument("output", type=Path)
    parser.add_argument("--revision", default="HEAD")
    parser.add_argument("--agent", type=Path)
    parser.add_argument("--loader", type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    snapshot = Snapshot(root, args.revision)
    if args.kind == "manifest":
        args.output.parent.mkdir(parents=True, exist_ok=True)
        dirty = snapshot.commit == git(root, "rev-parse", "HEAD") and bool(git(root, "status", "--porcelain"))
        args.output.write_bytes(snapshot.manifest(dirty))
    elif args.kind == "sources":
        write_zip(args.output, snapshot.sources())
    else:
        if not args.agent or not args.loader:
            parser.error("workshop requires --agent and --loader")
        workshop(snapshot, args.agent, args.loader, args.output)
    print(args.output)


if __name__ == "__main__":
    main()
