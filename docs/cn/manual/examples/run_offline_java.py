"""Compile and run the no-network Java sample against a cached checkout.

Requires JDK 25 and existing Gradle SpotBugs auxclasspath files. Never downloads
dependencies or writes into the checkout. Choose a new build directory.
"""
import argparse
import os
from pathlib import Path
import subprocess

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--repo', type=Path, required=True)
p.add_argument('--build-dir', type=Path, required=True)
a = p.parse_args()
repo = a.repo.resolve()
out = a.build_dir.resolve()
if out == repo or repo in out.parents:
    raise SystemExit('Build directory must be outside the checkout.')
if out.exists() and any(out.iterdir()):
    raise SystemExit('Use a new or empty build directory; existing files are preserved.')
mods = ('core-ai-api', 'core-ai', 'core-ai-cli')
jars = []
stale_entries = 0
for mod in mods:
    aux = repo / 'build' / mod / 'spotbugs/auxclasspath/spotbugsMain'
    if not aux.is_file():
        raise SystemExit(f'Missing cached classpath: {aux}; no network fallback.')
    for line in aux.read_text().splitlines():
        jar = Path(line.strip())
        if jar.suffix != '.jar' or repo in jar.parents:
            continue
        if not jar.is_file():
            stale_entries += 1
            continue
        if str(jar) not in jars:
            jars.append(str(jar))
sources = [f for mod in mods for f in (repo/mod/'src/main/java').rglob('*.java')]
if not sources or not jars:
    raise SystemExit('No source files or cached dependencies found.')
if stale_entries:
    print(f'Ignored {stale_entries} stale auxclasspath entries; javac verifies required types.', flush=True)
out.mkdir(parents=True, exist_ok=True)
classes = out/'classes'
classes.mkdir()
argsfile = out/'sources.txt'
# javac argfile: quote path spaces and escape Windows backslashes.
argsfile.write_text('\n'.join('"'+str(f).replace('\\','\\\\').replace('"','\\"')+'"'
                              for f in sources), encoding='utf-8')
cp = os.pathsep.join(jars)
subprocess.run(['javac','--release','25','-encoding','UTF-8','-parameters',
                '-proc:none','-cp',cp,'-d',str(classes),'@'+str(argsfile)],check=True)
examples = Path(__file__).resolve().parent/'java'
sample_sources = [str(f) for f in examples.rglob('*.java')]
run_cp = os.pathsep.join([str(classes), cp,
                         str(repo/'core-ai/src/main/resources'),
                         str(repo/'core-ai-cli/src/main/resources')])
subprocess.run(['javac','--release','25','-encoding','UTF-8','-parameters',
                '-cp',run_cp,'-d',str(classes),*sample_sources],check=True)
subprocess.run(['java','-cp',run_cp,'OfflineAgentDemo'],check=True)
