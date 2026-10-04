#!/usr/bin/env python3
"""Run deterministic internal probes after ./gradlew check has built test resources."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--checkout', type=Path, default=Path.cwd())
parser.add_argument('--out', type=Path, required=True)
args = parser.parse_args()
checkout = args.checkout.resolve()
libraries = checkout / 'java-metrics-cli/build/install/java-metrics-cli/lib'
classpath = os.pathsep.join(map(str, [
    *sorted(libraries.glob('*.jar')),
    checkout / 'java-metrics-cli/build/classes/java/test',
    checkout / 'java-metrics-cli/build/resources/test',
]))
helper = Path(__file__).with_name('AcceptanceProbe.java')

with tempfile.TemporaryDirectory(prefix='metricstree-java-audit-') as directory:
    root = Path(directory)
    repo = root / 'consumer'
    repo.mkdir()
    def git(*argv):
        subprocess.run(['git', '-c', 'user.name=Audit', '-c', 'user.email=audit@example.invalid',
                        *argv], cwd=repo, check=True, capture_output=True)
    git('init', '-q')
    (repo / 'Demo.java').write_text('class Demo { void run(int x) {} }')
    git('add', '.')
    git('commit', '-qm', 'base')
    branches = ''.join(f'if(x=={i}) x++;' for i in range(17))
    (repo / 'Demo.java').write_text('class Demo { void run(int x) { ' + branches + ' } }')
    classes = root / 'classes'
    classes.mkdir()
    subprocess.run(['javac', '-cp', classpath, '-d', str(classes), str(helper)], check=True)
    completed = subprocess.run(['java', '-cp', str(classes) + os.pathsep + classpath,
                                'org.b333vv.metric.cli.AcceptanceProbe', str(repo)],
                               check=True, capture_output=True, text=True, timeout=60)
    result = {'revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=checkout,
                                                  text=True).strip(),
              'stdout': completed.stdout, 'stderr': completed.stderr}
args.out.parent.mkdir(parents=True, exist_ok=True)
args.out.write_text(json.dumps(result, indent=2) + '\n')
print(completed.stdout, end='')
