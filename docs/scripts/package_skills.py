from argparse import ArgumentParser
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile

SKIP_DIRS = {"__pycache__"}


def package(skills_dir: Path, dist_dir: Path):
    target_dir = dist_dir / "skills"
    packaged = []
    for skill in sorted(skills_dir.iterdir()):
        if not skill.is_dir() or not (skill / "SKILL.md").is_file():
            continue
        archive = target_dir / f"{skill.name}.zip"
        target_dir.mkdir(parents=True, exist_ok=True)
        files = 0
        with ZipFile(archive, "w", ZIP_DEFLATED) as zip_file:
            for path in sorted(skill.rglob("*")):
                if not path.is_file():
                    continue
                relative = path.relative_to(skill.parent).as_posix()
                if any(part.startswith(".") or part in SKIP_DIRS for part in relative.split("/")):
                    continue
                zip_file.write(path, relative)
                files += 1
        packaged.append((skill.name, archive, files))
    return packaged


def main():
    parser = ArgumentParser(description="Package each docs/skills/<name> as a downloadable <name>.zip in the built site.")
    parser.add_argument("--skills", type=Path, default=Path(__file__).resolve().parents[1] / "skills")
    parser.add_argument("--dist", type=Path, default=Path(__file__).resolve().parents[1] / ".vitepress/dist")
    arguments = parser.parse_args()
    packaged = package(arguments.skills, arguments.dist)
    for name, archive, files in packaged:
        print(f"packaged {name} -> skills/{archive.name} ({files} files, {archive.stat().st_size} bytes)")
    if not packaged:
        print(f"no skill with SKILL.md found under {arguments.skills}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
