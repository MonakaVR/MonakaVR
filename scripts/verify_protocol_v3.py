"""Verify the exact standalone C3 consumer kit and installed JVM members."""

from email.parser import BytesHeaderParser
from hashlib import sha256
from io import BytesIO
import json
from pathlib import Path, PurePosixPath
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[1]
KIT_MEMBERS = (
    "jvm/libs/monaka-input-protocol-v3.jar",
    "fixtures/v3/mtp-pose.json",
    "fixtures/v3/tracker-state.json",
    "NOTICE",
    "licenses/gson-LICENSE",
    "licenses/kotlin-LICENSE.txt",
    "licenses/kotlin-NOTICE.txt",
)


def require(condition, message):
    if not condition:
        raise SystemExit(message)


def digest(data):
    return sha256(data).hexdigest()


def verify():
    pin = json.loads((ROOT / "dependencies/monaka-protocol-v3.0.lock.json").read_bytes())
    archive_path = ROOT / pin["artifact_path"]
    archive_bytes = archive_path.read_bytes()
    manifest_bytes = (ROOT / pin["manifest_path"]).read_bytes()
    require(digest(archive_bytes) == pin["sha256"], "C3 ZIP hash mismatch")
    require(digest(manifest_bytes) == pin["manifest_sha256"], "C3 handoff manifest hash mismatch")
    manifest = json.loads(manifest_bytes)
    require(manifest["artifact"] == archive_path.name, "C3 handoff artifact mismatch")
    for field in ("sha256", "source_commit", "source_content_sha256", "wire_version", "jar_sha256"):
        require(pin[field] == manifest[field], f"C3 handoff mismatch: {field}")
    require(manifest["source_tree_clean"] is True, "C3 kit must come from clean committed source")

    with ZipFile(BytesIO(archive_bytes)) as archive:
        names = archive.namelist()
        require(len(names) == len({name.casefold() for name in names}), "Duplicate C3 ZIP path")
        require(archive.testzip() is None, "C3 ZIP CRC mismatch")
        for name in names:
            logical = PurePosixPath(name)
            require(not logical.is_absolute() and ".." not in logical.parts and ":" not in name and "\\" not in name,
                    f"Unsafe C3 ZIP path: {name}")
        metadata = json.loads(archive.read("kit.lock.json"))
        require(metadata == {k: v for k, v in manifest.items() if k not in ("artifact", "sha256")},
                "C3 kit lock and handoff differ")
        receipt = metadata["verification"]
        require(receipt["status"] == "PASS" and receipt["source_tree_clean"] is True, "C3 kit lacks clean-source PASS")
        for field in ("source_commit", "source_content_sha256", "jar_sha256"):
            require(receipt[field] == pin[field], f"C3 focused receipt mismatch: {field}")
        sums = dict(line.split("  ", 1)[::-1] for line in archive.read("SHA256SUMS").decode().splitlines())
        require(set(sums) == set(names) - {"SHA256SUMS"}, "C3 kit member list mismatch")
        require(metadata["files_sha256"] == {k: v for k, v in sums.items() if k != "kit.lock.json"},
                "C3 kit member hashes mismatch")
        for member, expected in sums.items():
            require(digest(archive.read(member)) == expected, f"C3 kit member modified: {member}")

        installed = ROOT / "third_party/monaka-protocol-v3"
        for member in KIT_MEMBERS:
            path = installed / member
            require(not path.is_symlink() and path.read_bytes() == archive.read(member),
                    f"C3 consumer dependency modified: {member}")
        # C2 and C3 use the same pinned Gson. There is one copy on the runtime classpath.
        gson = "jvm/libs/gson-2.11.0.jar"
        require((ROOT / "third_party/monaka-protocol-v2" / gson).read_bytes() == archive.read(gson),
                "C3 Gson and the frozen C2 runtime dependency differ")
        jar = archive.read(KIT_MEMBERS[0])
        require(digest(jar) == pin["jar_sha256"], "C3 JAR hash mismatch")
        with ZipFile(BytesIO(jar)) as classes:
            entries = [name for name in classes.namelist() if name.endswith(".class")]
            require(entries and all(name.startswith("dev/monaka/protocol/v3/") for name in entries),
                    "C3 JAR includes classes outside its versioned package")
            header = BytesHeaderParser().parsebytes(classes.read("META-INF/MANIFEST.MF"))
            def value(name):
                return str(header[name]).replace("\r\n ", "").replace("\n ", "")
            require(value("MVIP-Source-Commit") == pin["source_commit"] and
                    value("MVIP-Source-Content-SHA256") == pin["source_content_sha256"] and
                    value("MVIP-Source-Clean") == "true" and value("MVIP-Contract") == "monaka-wire-3.0",
                    "C3 JAR provenance mismatch")
    print("PASS exact C3 source/ZIP/JAR/content pin and v2 classpath coexistence")


if __name__ == "__main__":
    verify()
