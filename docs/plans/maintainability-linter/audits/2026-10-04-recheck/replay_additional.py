#!/usr/bin/env python3
"""Replay uncovered acceptance cases against the remediation revision in disposable consumers."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--cli', type=Path, required=True)
parser.add_argument('--checkout', type=Path, default=Path.cwd())
parser.add_argument('--out', type=Path, required=True)
args = parser.parse_args()
cli = args.cli.resolve()
observations = []

def git(repo, *argv):
    return subprocess.check_output(['git', '-c', 'user.name=Audit',
                                   '-c', 'user.email=audit@example.invalid', *argv],
                                  cwd=repo, text=True).strip()

def source(branches=0):
    return 'class Demo {\n    void run(int x) {\n' + ''.join(
        f'        if (x == {i}) x++;\n' for i in range(branches)) + '    }\n}\n'

def init(root, name, branches=0):
    repo = root/name
    (repo/'src/main/java').mkdir(parents=True)
    (repo/'src/main/java/Demo.java').write_text(source(branches))
    git(repo, 'init', '-q')
    git(repo, 'add', '.')
    git(repo, 'commit', '-qm', 'base')
    return repo

def config(repo, suppressed=False, scope=None):
    policy = {'enabledRules': ['MT-M001'], 'rules': {'MT-M001': {'mode': 'error'}}}
    if suppressed:
        policy['suppressions'] = [{'ruleId': 'MT-M001',
                                  'entity': {'path': 'src/main/java/Demo.java', 'class': 'Demo',
                                             'signature': 'run(int)'},
                                  'reason': 'explicit reviewed exception'}]
    document = {'maintainability': policy}
    if scope:
        document['gate'] = {'analysis': {'scope': scope}}
    (repo/'audit.json').write_text(json.dumps(document))

def run(repo, id, flags=(), *, detect=False, root='src', expected):
    out = repo/'report.json'
    out.unlink(missing_ok=True)
    command = ([str(cli), 'detect', '-s', root] if detect else
               [str(cli), 'gate', '--base', 'HEAD'])
    command += ['--policy', 'maintainability', '--config', 'audit.json', '-o', 'report.json', *flags]
    result = subprocess.run(command, cwd=repo, capture_output=True, text=True, timeout=60)
    raw = out.read_text() if out.exists() else ''
    try:
        report = json.loads(raw)
    except ValueError:
        report = None
    observations.append({'id': id, 'expected': expected, 'exit': result.returncode,
                         'stderr': result.stderr.replace(str(repo), '<fixture>'),
                         'report': report, 'textPrefix': raw[:500] if report is None else None})
    print(id, 'exit=', result.returncode, 'status=', report.get('status') if report else None,
          flush=True)
    return report

with tempfile.TemporaryDirectory(prefix='metricstree-remediation-recheck-') as directory:
    root = Path(directory)
    repo = init(root, 'detect-enforce')
    config(repo)
    (repo/'src/main/java/Demo.java').write_text(source(17))
    first = run(repo, 'detect-error-current', ['--enforcement', 'enforce'], detect=True,
                expected='exit 1: error mode blocks complete CURRENT match under enforce')
    second = run(repo, 'detect-alternate-root', detect=True, root='src/main/java',
                 expected='same repository-relative entity identity as detect -s src')
    observations.append({'id': 'detect-root-identity',
                         'expected': 'same file/rule/signature keeps its fingerprint',
                         'sameFingerprint': first['findings'][0]['fingerprint']
                             == second['findings'][0]['fingerprint']})
    for fmt in ['sarif', 'html', 'agent-md']:
        run(repo, 'gate-format-'+fmt, ['--format', fmt],
            expected='advisory active finding appears in selected format')
        run(repo, 'detect-format-'+fmt, ['--format', fmt], detect=True,
            expected='current advisory finding appears in selected format')
    local = run(repo, 'policy-scope-local', ['--analysis-scope', 'local'],
                expected='digest describes local scope')
    project = run(repo, 'policy-scope-project', ['--analysis-scope', 'project'],
                  expected='digest changes with analysis scope')
    observations.append({'id': 'scope-policy-digest', 'expected': 'different digests',
                         'sameDigest': local['policyDigest'] == project['policyDigest']})

    repo = init(root, 'staged-move', 17)
    config(repo)
    git(repo, 'mv', 'src/main/java/Demo.java', 'src/main/java/Moved.java')
    # The index contains an exact move; its working copy was subsequently simplified.
    (repo/'src/main/java/Moved.java').write_text(source(0))
    run(repo, 'staged-move-dirty-worktree', ['--mode', 'staged', '--enforcement', 'enforce'],
        expected='exit 0, EXISTING with previous fingerprint: index is an unchanged exact move')

    repo = init(root, 'staged-unchanged-index', 17)
    config(repo)
    (repo/'src/main/java/Demo.java').write_text(source(18))
    run(repo, 'staged-unchanged-index', ['--mode', 'staged', '--enforcement', 'enforce'],
        expected='empty comparison: an unstaged edit must not become a staged change')

    repo = init(root, 'compact-debt', 17)
    config(repo)
    (repo/'src/main/java/Other.java').write_text('class Other { void run() {} }')
    git(repo, 'add', 'src/main/java/Other.java')
    git(repo, 'commit', '-qm', 'neighbor')
    (repo/'src/main/java/Other.java').write_text('class Other { void run() { int x=1; } }')
    run(repo, 'compact-unchanged-debt', ['--format', 'agent-md', '--analysis-scope', 'project'],
        expected='unchanged existing debt is excluded from the default compact finding list')

    repo = init(root, 'unavailable-base-error')
    config(repo)
    (repo/'src/main/java/Demo.java').write_text('class Demo { void run(int x) {')
    git(repo, 'add', 'src/main/java/Demo.java')
    git(repo, 'commit', '-qm', 'unparseable base')
    (repo/'src/main/java/Demo.java').write_text(source(17))
    run(repo, 'unavailable-base-error', ['--enforcement', 'enforce'],
        expected='exit 2, COMPARISON_UNAVAILABLE: failed base is not proof of new code')

    repo = init(root, 'suppression-baseline')
    config(repo, suppressed=True)
    (repo/'src/main/java/Demo.java').write_text(source(15))
    run(repo, 'suppressed-baseline-export', ['--write-findings-baseline', 'baseline.json'],
        expected='baseline export retains complete matched debt with the reviewed exception')
    observations[-1]['baseline'] = json.loads((repo/'baseline.json').read_text())
    git(repo, 'add', 'src/main/java/Demo.java')
    git(repo, 'commit', '-qm', 'accept CC 16')
    for branches in [17, 19]:
        (repo/'src/main/java/Demo.java').write_text(source(branches))
        git(repo, 'add', 'src/main/java/Demo.java')
        git(repo, 'commit', '-qm', f'CC {branches+1}')
    (repo/'src/main/java/Demo.java').write_text(source(21))
    run(repo, 'suppressed-baseline-regression', ['--findings-baseline', 'baseline.json',
                                               '--enforcement', 'enforce'],
        expected='exit 0, SUPPRESSED: baseline must not remove an explicit narrow exception')

args.out.parent.mkdir(parents=True, exist_ok=True)
args.out.write_text(json.dumps({'revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'],
                                      cwd=args.checkout, text=True).strip(),
                                'observations': observations}, indent=2)+'\n')
