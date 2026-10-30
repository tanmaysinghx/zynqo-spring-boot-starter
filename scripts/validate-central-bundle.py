#!/usr/bin/env python3
"""Validates a Sonatype Central Portal bundle produced by central-publishing-maven-plugin.

Usage: validate-central-bundle.py <central-bundle.zip> <version> [--verify-signatures]

Checks, for every published artifact:
  * exactly the expected modules are present (test and example modules are not);
  * jar, sources jar, javadoc jar and POM exist (POM only for the parent);
  * every file has .md5, .sha1, .sha256 and .sha512 checksums that match;
  * every file has an .asc signature (verified with gpg when --verify-signatures is given);
  * every POM declares name, description, url, licenses, developers, scm and issueManagement;
  * release bundles contain no SNAPSHOT versions.
Exits non-zero and lists every problem on failure.
"""
import hashlib
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

GROUP_PATH = "io/github/tanmaysinghx"
JAR_MODULES = [
    "zynqo-core",
    "zynqo-storage-redis",
    "zynqo-spring-boot-autoconfigure",
    "zynqo-spring-boot-starter",
]
POM_MODULES = ["zynqo-parent"]
FORBIDDEN = ["zynqo-integration-tests", "zynqo-examples", "zynqo-example-api"]
REQUIRED_POM_ELEMENTS = [
    "name", "description", "url", "licenses", "developers", "scm", "issueManagement"]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
DIGESTS = {"md5": hashlib.md5, "sha1": hashlib.sha1, "sha256": hashlib.sha256, "sha512": hashlib.sha512}


def main() -> int:
    if len(sys.argv) < 3:
        print(__doc__)
        return 2
    bundle, version = Path(sys.argv[1]), sys.argv[2]
    verify_signatures = "--verify-signatures" in sys.argv[3:]
    problems: list[str] = []

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        with zipfile.ZipFile(bundle) as archive:
            archive.extractall(root)
        base = root / GROUP_PATH

        present = sorted(p.name for p in base.iterdir() if p.is_dir()) if base.exists() else []
        expected = sorted(JAR_MODULES + POM_MODULES)
        if present != expected:
            problems.append(f"modules {present} differ from expected {expected}")
        for module in FORBIDDEN:
            if (base / module).exists():
                problems.append(f"{module} must not be published")

        for module in JAR_MODULES + POM_MODULES:
            directory = base / module / version
            prefix = f"{module}-{version}"
            files = [f"{prefix}.pom"]
            if module in JAR_MODULES:
                files += [f"{prefix}.jar", f"{prefix}-sources.jar", f"{prefix}-javadoc.jar"]
            for name in files:
                path = directory / name
                if not path.is_file():
                    problems.append(f"missing {module}/{version}/{name}")
                    continue
                data = path.read_bytes()
                for ext, algorithm in DIGESTS.items():
                    checksum = directory / f"{name}.{ext}"
                    if not checksum.is_file():
                        problems.append(f"missing checksum {name}.{ext}")
                    elif checksum.read_text().strip().split()[0] != algorithm(data).hexdigest():
                        problems.append(f"checksum mismatch {name}.{ext}")
                signature = directory / f"{name}.asc"
                if not signature.is_file():
                    problems.append(f"missing signature {name}.asc")
                elif verify_signatures:
                    result = subprocess.run(
                        ["gpg", "--batch", "--verify", str(signature), str(path)],
                        capture_output=True)
                    if result.returncode != 0:
                        problems.append(f"invalid signature {name}.asc")

            pom = directory / f"{prefix}.pom"
            if pom.is_file():
                text = pom.read_text()
                tree = ET.fromstring(text)
                for element in REQUIRED_POM_ELEMENTS:
                    if tree.find(f"m:{element}", NS) is None:
                        problems.append(f"{module} POM lacks <{element}>")
                if not version.endswith("-SNAPSHOT") and "SNAPSHOT" in text:
                    problems.append(f"{module} POM references a SNAPSHOT version")

    if problems:
        print("Bundle validation FAILED:")
        for problem in problems:
            print(f"  - {problem}")
        return 1
    print(f"Bundle validation passed for {len(JAR_MODULES) + len(POM_MODULES)} modules at {version}.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
