from argparse import ArgumentParser
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

SKIP_DIRS = {"__pycache__"}
CATALOG_KEYS = ("title", "description", "version", "source", "license", "capability", "default", "docsUrl")


def included_files(skill_dir: Path):
    """Skill files in stable order; dotfiles and Python build artifacts stay out of the zip."""
    for path in sorted(skill_dir.rglob("*")):
        if not path.is_file():
            continue
        relative = path.relative_to(skill_dir.parent).as_posix()
        if any(part.startswith(".") or part in SKIP_DIRS for part in relative.split("/")):
            continue
        yield path, relative


def tree_digest(files) -> str:
    """Stable content digest: relative path + file bytes, independent of zip timestamps."""
    digest = hashlib.sha256()
    for path, relative in files:
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(path.read_bytes())
        digest.update(b"\0")
    return "sha256:" + digest.hexdigest()


def load_catalog(skills_dir: Path) -> dict:
    catalog_path = skills_dir / "catalog.json"
    if not catalog_path.is_file():
        return {}
    try:
        data = json.loads(catalog_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        print(f"warning: cannot read {catalog_path}: {error}")
        return {}
    entries = data.get("skills", []) if isinstance(data, dict) else []
    return {entry["name"]: entry for entry in entries
            if isinstance(entry, dict) and isinstance(entry.get("name"), str)}


def package(skills_dir: Path, dist_dir: Path):
    target_dir = dist_dir / "skills"
    catalog = load_catalog(skills_dir)
    packaged = []
    for skill in sorted(skills_dir.iterdir()):
        if not skill.is_dir() or not (skill / "SKILL.md").is_file():
            continue
        archive = target_dir / f"{skill.name}.zip"
        target_dir.mkdir(parents=True, exist_ok=True)
        files = list(included_files(skill))
        with ZipFile(archive, "w", ZIP_DEFLATED) as zip_file:
            for path, relative in files:
                zip_file.write(path, relative)
        entry = {
            "name": skill.name,
            "zip": archive.name,
            "digest": tree_digest(files),
            "files": len(files),
        }
        metadata = catalog.get(skill.name, {})
        for key in CATALOG_KEYS:
            if key in metadata:
                entry[key] = metadata[key]
        packaged.append((skill.name, archive, files, entry))
    if packaged:
        manifest = {
            "schema": 1,
            "generatedAt": datetime.now(timezone.utc).isoformat(),
            "skills": [entry for _, _, _, entry in packaged],
        }
        (target_dir / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return packaged


def main():
    parser = ArgumentParser(description="Package each docs/skills/<name> as a downloadable <name>.zip in the built site.")
    parser.add_argument("--skills", type=Path, default=Path(__file__).resolve().parents[1] / "skills")
    parser.add_argument("--dist", type=Path, default=Path(__file__).resolve().parents[1] / ".vitepress/dist")
    arguments = parser.parse_args()
    packaged = package(arguments.skills, arguments.dist)
    for name, archive, files, _ in packaged:
        print(f"packaged {name} -> skills/{archive.name} ({len(files)} files, {archive.stat().st_size} bytes)")
    if packaged:
        print(f"wrote skills/manifest.json ({len(packaged)} skills)")
    else:
        print(f"no skill with SKILL.md found under {arguments.skills}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
