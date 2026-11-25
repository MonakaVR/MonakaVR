"""Explicit selection, focused acceptance, promotion and stable-runtime verification."""
from __future__ import annotations
import argparse
import contextlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import uuid
from build_provenance import (SCHEMA, PRODUCTS, ContractError, utc, digest, read_json,
                              write_json, inside, lock, source_info, manifest_path,
                              catalog_path, load_catalog)

def select_source(repo, channels):
    repo = Path(repo).resolve()
    source = source_info(repo)
    if repo.name not in PRODUCTS or not channels or set(channels) - {"windows", "android"}:
        raise ContractError("Invalid selected product/channels")
    path = catalog_path(repo.parent)
    with lock(path.with_suffix(".lock")):
        catalog = load_catalog(repo.parent) if path.exists() else {"schema": SCHEMA, "products": {}}
        old = catalog["products"].get(repo.name, {})
        catalog["products"][repo.name] = {
            "selected_source": source, "channels": channels, "latest_candidates": {},
            "promoted_generation": old.get("promoted_generation"),
            "selected_utc": utc(),
        }
        write_json(path, catalog)


def candidates(repo):
    repo = Path(repo).resolve()
    catalog = load_catalog(repo.parent)
    selected = catalog["products"].get(repo.name)
    if not selected:
        raise ContractError("RUNTIME_PROVENANCE_MISMATCH: source has not been selected")
    source = source_info(repo)
    if source != selected["selected_source"]:
        raise ContractError("STALE_RUNTIME: selected source differs from current source")
    builds = [read_json(manifest_path(repo, c)) for c in selected["channels"]]
    for build in builds:
        if (build.get("schema") != SCHEMA or build.get("source") != source or
                build.get("product") != repo.name or
                selected["latest_candidates"].get(build.get("channel")) != build.get("build_candidate_id")):
            raise ContractError("RUNTIME_PROVENANCE_MISMATCH: candidate/source/selection differ")
    return selected, builds


def identities(builds):
    return {b["channel"]: b["build_candidate_id"] for b in builds}


def run_check(repo, command):
    repo = Path(repo).resolve()
    selected, builds = candidates(repo)
    if not command:
        raise ContractError("A focused test command is required")
    started = utc()
    result = subprocess.run(command, cwd=repo, check=False)
    _, after = candidates(repo)
    if identities(builds) != identities(after):
        raise ContractError("Build changed during focused check")
    path = repo / "Build" / "provenance" / "focused.json"
    previous = read_json(path) if path.exists() else {}
    checks = previous.get("checks", []) if previous.get("builds") == identities(builds) else []
    checks.append({"command": command, "started_utc": started, "completed_utc": utc(), "exit_code": result.returncode})
    write_json(path, {"schema": SCHEMA, "source": selected["selected_source"], "builds": identities(builds), "checks": checks})
    return result.returncode


def verify_payload(root, payload):
    for row in payload:
        path = inside(root, row["path"])
        if not path.is_file() or path.stat().st_size != row["bytes"] or digest(path) != row["sha256"]:
            raise ContractError(f"RUNTIME_PROVENANCE_MISMATCH: {row['path']}")


def verify_stable(workspace, product, _lock_owned=False):
    workspace = Path(workspace).resolve()
    if product not in PRODUCTS:
        raise ContractError("Unknown product")
    if not _lock_owned and (workspace / "runtime" / (".promotion-" + product + ".lock")).exists():
        raise ContractError("RUNTIME_PROVENANCE_MISMATCH: promotion is in progress")
    catalog = load_catalog(workspace)
    selected = catalog["products"].get(product)
    root = workspace / "runtime" / "stable" / product
    manifest = read_json(root / "runtime-manifest.json")
    if not selected or manifest.get("schema") != SCHEMA or manifest.get("product") != product:
        raise ContractError("RUNTIME_PROVENANCE_MISMATCH: stable product identity")
    if (manifest.get("generation") != selected.get("promoted_generation") or
            manifest.get("builds") != selected.get("latest_candidates") or
            manifest.get("source") != selected.get("selected_source")):
        raise ContractError("STALE_RUNTIME: stable generation is not the selected build")
    if digest(root / "runtime-manifest.json") != selected.get("promoted_manifest_sha256"):
        raise ContractError("RUNTIME_PROVENANCE_MISMATCH: promoted manifest changed")
    source = manifest["source"]
    if (not source.get("repository") or not source.get("branch") or not source.get("commit") or
            not source.get("tree") or source.get("dirty") is not False or
            not manifest.get("promotion_utc") or not manifest.get("promotion_source")):
        raise ContractError("RUNTIME_PROVENANCE_MISMATCH: incomplete provenance")
    verify_payload(root, manifest["payload"])
    return root, manifest


@contextlib.contextmanager
def verified_runtime(workspace, product):
    """Exclude promotion throughout verification and initial process creation."""
    if not catalog_path(workspace).is_file():
        raise ContractError("RUNTIME_PROVENANCE_MISMATCH: stable selection is missing")
    with lock(Path(workspace) / "runtime" / (".promotion-" + product + ".lock")):
        with lock(catalog_path(workspace).with_suffix(".lock")):
            yield verify_stable(workspace, product, _lock_owned=True)


def install_launchers(repo):
    """Deploy versioned workspace entries only after their source has been promoted."""
    repo = Path(repo).resolve()
    if repo.name != "MonakaVR":
        raise ContractError("Workspace launchers are versioned in the selected MonakaVR repository")
    source = source_info(repo)
    workspace = repo.parent
    catalog = load_catalog(workspace)
    selected = catalog["products"].get("MonakaVR", {})
    stable = read_json(workspace / "runtime/stable/MonakaVR/runtime-manifest.json")
    if (stable.get("source") != source or selected.get("selected_source") != source or
            stable.get("generation") != selected.get("promoted_generation")):
        raise ContractError("Launcher source has not been promoted")
    templates = repo / "scripts/workspace_runtime/launchers"
    generation = uuid.uuid4().hex
    stage = workspace / (".launchers-staging-" + generation)
    target = workspace / "launchers"
    backup = workspace / "runtime/rollback" / ("launchers-" + generation)
    switched = False
    with lock(workspace / "runtime/.launcher-install.lock"):
        try:
            shutil.copytree(templates, stage)
            for name in ("build_provenance.py", "runtime_contract.py", "stable_launcher.py"):
                shutil.copy2(repo / "scripts/workspace_runtime" / name, stage / "_internal" / name)
            entries = [{"path": p.relative_to(stage).as_posix(), "sha256": digest(p)}
                       for p in sorted(stage.rglob("*")) if p.is_file()]
            backup.parent.mkdir(parents=True, exist_ok=True)
            if target.exists():
                os.replace(target, backup)
            try:
                os.replace(stage, target)
                switched = True
            except Exception:
                if backup.exists() and not target.exists():
                    os.replace(backup, target)
                raise
            write_json(workspace / "runtime/launcher-manifest.json",
                       {"schema": SCHEMA, "source": source, "deployed_utc": utc(),
                        "generation": generation, "files": entries, "previous": str(backup) if backup.exists() else None})
            return generation
        except Exception:
            if switched:
                os.replace(target, stage)
                if backup.exists():
                    os.replace(backup, target)
            raise
        finally:
            if stage.exists():
                if stage.resolve().parent != workspace or not stage.name.startswith(".launchers-staging-"):
                    raise ContractError("Unsafe launcher staging cleanup")
                shutil.rmtree(stage)


def promote(repo, initial_rollback=None):
    repo = Path(repo).resolve()
    selected, builds = candidates(repo)
    check = read_json(repo / "Build" / "provenance" / "focused.json")
    if (check.get("source") != selected["selected_source"] or check.get("builds") != identities(builds)
            or not check.get("checks") or any(c.get("exit_code") != 0 for c in check["checks"])):
        raise ContractError("Focused checks for this exact source/build have not passed")
    workspace = repo.parent
    runtime = workspace / "runtime"
    stable = runtime / "stable" / repo.name
    initial_reference = None
    if not stable.exists():
        if initial_rollback is None:
            raise ContractError("First promotion requires an explicit preserved rollback manifest")
        baseline = Path(initial_rollback).resolve()
        if not baseline.is_relative_to(workspace) or not baseline.is_file():
            raise ContractError("Rollback manifest must be an existing preserved workspace file")
        read_json(baseline)  # Check readability, without rehashing or resealing the baseline.
        initial_reference = {"kind": "preexisting-baseline", "manifest": baseline.relative_to(workspace).as_posix(),
                             "generation": None, "source": None}
    generation = uuid.uuid4().hex
    stage = runtime / "stable" / (".staging-" + repo.name + "-" + generation)
    previous = runtime / "rollback" / (repo.name + "-" + generation)
    payload = [row for build in builds for row in build["payload"]]
    if len({r["path"] for r in payload}) != len(payload):
        raise ContractError("Overlapping channel payloads")
    with lock(runtime / (".promotion-" + repo.name + ".lock")):
        stage.mkdir(parents=True, exist_ok=False)
        swapped = False
        had_previous = False
        try:
            for row in payload:
                source = inside(repo, row["source_path"])
                if not source.is_file() or digest(source) != row["sha256"]:
                    raise ContractError("Candidate binary changed since build")
                target = inside(stage, row["path"])
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
            verify_payload(stage, payload)
            prior = read_json(stable / "runtime-manifest.json") if stable.exists() else None
            manifest = {"schema": SCHEMA, "product": repo.name, "generation": generation,
                        "source": selected["selected_source"], "builds": identities(builds),
                        "build_timestamps": {b["channel"]: b["build_completed_utc"] for b in builds},
                        "payload": payload, "focused": check, "promotion_utc": utc(),
                        "promotion_source": [str(manifest_path(repo, b["channel"])) for b in builds],
                        "rollback_source": {"generation": prior["generation"], "source": prior["source"]} if prior else initial_reference}
            write_json(stage / "runtime-manifest.json", manifest)
            manifest_digest = digest(stage / "runtime-manifest.json")
            catalog_file = catalog_path(workspace)
            with lock(catalog_file.with_suffix(".lock")):
                catalog = load_catalog(workspace)
                current = catalog["products"].get(repo.name)
                if current != selected:
                    raise ContractError("Selection changed during promotion")
                if stable.exists():
                    previous.parent.mkdir(parents=True, exist_ok=True)
                    os.replace(stable, previous)
                    had_previous = True
                os.replace(stage, stable)
                swapped = True
                current["promoted_generation"] = generation
                current["promoted_manifest_sha256"] = manifest_digest
                write_json(catalog_file, catalog)
            return manifest
        except Exception:
            # If selection has not been published, restore the previous complete tree.
            published = False
            with contextlib.suppress(Exception):
                published = load_catalog(workspace)["products"][repo.name].get("promoted_generation") == generation
            if swapped and not published:
                os.replace(stable, stage)
            if had_previous and not published and not stable.exists():
                os.replace(previous, stable)
            raise
        finally:
            if stage.exists():
                if stage.resolve().parent != (runtime / "stable").resolve() or not stage.name.startswith(".staging-"):
                    raise ContractError("Unsafe staging cleanup")
                shutil.rmtree(stage)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    select = sub.add_parser("select")
    select.add_argument("repo", type=Path)
    select.add_argument("--channels", nargs="+", default=["windows"])
    check = sub.add_parser("check")
    check.add_argument("repo", type=Path)
    check.add_argument("command", nargs=argparse.REMAINDER)
    promotion = sub.add_parser("promote")
    promotion.add_argument("repo", type=Path)
    promotion.add_argument("--rollback-manifest", type=Path, help="Preserved baseline manifest required for first promotion")
    deploy = sub.add_parser("install-launchers")
    deploy.add_argument("repo", type=Path)
    verify = sub.add_parser("verify")
    verify.add_argument("workspace", type=Path)
    verify.add_argument("product", choices=PRODUCTS)
    args = parser.parse_args()
    try:
        if args.action == "select":
            select_source(args.repo, args.channels)
        elif args.action == "check":
            command = args.command[1:] if args.command[:1] == ["--"] else args.command
            return run_check(args.repo, command)
        elif args.action == "promote":
            manifest = promote(args.repo, args.rollback_manifest)
            print(json.dumps({"status": "PROMOTED", "product": manifest["product"],
                              "generation": manifest["generation"], "source": manifest["source"]}, ensure_ascii=False))
        elif args.action == "install-launchers":
            print(json.dumps({"status": "DEPLOYED", "generation": install_launchers(args.repo)}))
        else:
            root, manifest = verify_stable(args.workspace, args.product)
            print(json.dumps({"status": "VERIFIED", "runtime": str(root), "generation": manifest["generation"]}))
        return 0
    except (ContractError, OSError, ValueError, KeyError) as error:
        print(str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
