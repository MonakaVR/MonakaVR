"""Build provenance recorded by each repository-owned explicit build command."""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone
import uuid

SCHEMA = "monaka.runtime.v1"
PRODUCTS = ("PicoMotionTrackerBridge", "MonakaBridge", "MonakaVR")


class ContractError(RuntimeError):
    pass


def utc():
    return datetime.now(timezone.utc).isoformat()


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def read_json(path):
    try:
        return json.loads(Path(path).read_text(encoding="utf-8-sig"))
    except (OSError, ValueError) as error:
        raise ContractError(f"RUNTIME_PROVENANCE_MISMATCH: {path}: {error}") from error


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    stage = path.with_name(path.name + ".pending-" + uuid.uuid4().hex)
    try:
        with stage.open("x", encoding="utf-8", newline="\n") as stream:
            json.dump(value, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(stage, path)
    finally:
        stage.unlink(missing_ok=True)


def inside(root, relative):
    root = Path(root).resolve()
    rel = Path(relative)
    if rel.is_absolute() or ".." in rel.parts:
        raise ContractError(f"Unsafe payload path: {relative}")
    result = root.joinpath(rel).resolve()
    if not result.is_relative_to(root) or result == root:
        raise ContractError(f"Payload escapes its root: {relative}")
    return result


@contextlib.contextmanager
def lock(path):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor = None
    for _ in range(50):
        try:
            descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
            break
        except FileExistsError:
            time.sleep(0.02)
    if descriptor is None:
        raise ContractError(f"RUNTIME_PROVENANCE_MISMATCH: operation in progress: {path}")
    try:
        os.write(descriptor, str(os.getpid()).encode("ascii"))
        os.close(descriptor)
        yield
    finally:
        path.unlink(missing_ok=True)


def git(repo, *args):
    result = subprocess.run(["git", "-C", str(repo), *args], capture_output=True, text=True)
    if result.returncode:
        raise ContractError(result.stderr.strip() or "Git source identity unavailable")
    return result.stdout.strip()


def source_info(repo):
    repo = Path(repo).resolve()
    if git(repo, "status", "--porcelain=v1", "--untracked-files=normal", "--ignore-submodules=none"):
        raise ContractError("Build requires a preserved, clean selected source; commit or explicitly preserve changes first")
    branch = git(repo, "symbolic-ref", "--quiet", "--short", "HEAD")
    return {
        "repository": repo.name,
        "branch": branch,
        "commit": git(repo, "rev-parse", "HEAD"),
        "tree": git(repo, "rev-parse", "HEAD^{tree}"),
        "dirty": False,
        "patch_sha256": None,
    }


def begin_build(repo, channel="windows", command=None):
    repo = Path(repo).resolve()
    if repo.name not in PRODUCTS or channel not in ("windows", "android"):
        raise ContractError("Unknown product/channel")
    source = source_info(repo)
    return {
        "schema": SCHEMA,
        "product": repo.name,
        "source": source,
        "build_candidate_id": uuid.uuid4().hex,
        "channel": channel,
        "build_started_utc": utc(),
        "build_command": command or list(sys.argv),
    }


def manifest_path(repo, channel):
    return Path(repo) / "Build" / "provenance" / (channel + ".json")


def catalog_path(workspace):
    return Path(workspace) / "runtime" / "selection.json"


def load_catalog(workspace):
    path = catalog_path(workspace)
    value = read_json(path)
    if value.get("schema") != SCHEMA or not isinstance(value.get("products"), dict):
        raise ContractError("RUNTIME_PROVENANCE_MISMATCH: invalid selection catalog")
    return value


def finish_build(repo, snapshot, mappings):
    """Record the explicit build's source and mapped output files, without selecting a runtime."""
    repo = Path(repo).resolve()
    if source_info(repo) != snapshot["source"]:
        raise ContractError("Source changed during build; candidate is not promotable")
    payload = []
    for relative, destination in mappings:
        origin = inside(repo, relative)
        if not origin.exists():
            raise ContractError(f"Missing build payload: {relative}")
        paths = sorted(origin.rglob("*")) if origin.is_dir() else [origin]
        for item in paths:
            if item.is_symlink() or (hasattr(item, "is_junction") and item.is_junction()):
                raise ContractError(f"Reparse point in build payload: {item}")
            if not item.is_file():
                continue
            if item.suffix.lower() in (".obj", ".lib", ".exp", ".ilk", ".pdb", ".tlog"):
                continue
            suffix = item.relative_to(origin) if origin.is_dir() else Path()
            target = (Path(destination) / suffix).as_posix()
            inside(repo / "Build", target)  # Validate a relative, contained destination.
            payload.append({"path": target, "source_path": item.relative_to(repo).as_posix(),
                            "bytes": item.stat().st_size, "sha256": digest(item)})
    if not payload or len({row["path"] for row in payload}) != len(payload):
        raise ContractError("Empty or overlapping payload")
    result = dict(snapshot, build_completed_utc=utc(), payload=payload)
    write_json(manifest_path(repo, result["channel"]), result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("snapshot", "complete"))
    parser.add_argument("repo", type=Path)
    parser.add_argument("--channel", choices=("windows", "android"), default="windows")
    parser.add_argument("--mapping", action="append", default=[])
    args = parser.parse_args()
    try:
        if args.action == "snapshot":
            print(json.dumps(begin_build(args.repo, args.channel), ensure_ascii=False))
        else:
            snapshot = json.load(sys.stdin)
            if snapshot.get("channel") != args.channel:
                raise ContractError("Build channel changed")
            mappings = [tuple(value.split("=", 1)) for value in args.mapping]
            if not mappings or any(len(pair) != 2 for pair in mappings):
                raise ContractError("Each payload mapping must be source=destination")
            result = finish_build(args.repo, snapshot, mappings)
            print(json.dumps({"build_candidate_id": result["build_candidate_id"], "channel": result["channel"]}))
        return 0
    except (ContractError, OSError, ValueError, KeyError) as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
