#!/usr/bin/env python3
"""Replay maintainability acceptance checks in disposable consumer repositories."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile
import os

parser = argparse.ArgumentParser()
parser.add_argument('--cli', type=Path, required=True)
parser.add_argument('--out', type=Path, required=True)
parser.add_argument('--checkout', type=Path, default=Path.cwd())
args = parser.parse_args()
checkout = args.checkout.resolve()
cli = args.cli.resolve()
observations = []

def git(repo, *argv):
    p = subprocess.run(['git', '-c', 'user.name=Audit', '-c', 'user.email=audit@example.invalid',
                        *argv], cwd=repo, capture_output=True, text=True)
    if p.returncode:
        raise RuntimeError(p.stderr)
    return p.stdout.strip()

def put(repo, path, text):
    p = repo / path
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text)

def source(branches=0, name='Demo', method='run'):
    body = ''.join(f'        if (x == {n}) x++;\n' for n in range(branches))
    return f'class {name} {{\n\n\n    void {method}(int x) {{\n{body}    }}\n}}\n'

def init(root, name, branches=0, path='src/main/java/Demo.java'):
    repo = root / name
    repo.mkdir()
    git(repo, 'init', '-q')
    put(repo, path, source(branches))
    git(repo, 'add', '.')
    git(repo, 'commit', '-qm', 'base')
    return repo

def config(repo, settings=None, **other):
    document = {'maintainability': settings or {'enabledRules': ['MT-M001']}}
    document.update(other)
    put(repo, 'audit.json', json.dumps(document))

def run(repo, name, options=None, detect=False, expected=None):
    report_path = repo / 'report.json'
    if report_path.exists():
        report_path.unlink()
    command = ([str(cli), 'detect', '-s', 'src', '--policy', 'maintainability'] if detect else
               [str(cli), 'gate', '--base', 'HEAD', '--policy', 'maintainability'])
    command += ['--config', 'audit.json', '-o', 'report.json'] + (options or [])
    p = subprocess.run(command, cwd=repo, text=True, capture_output=True, timeout=60)
    raw = report_path.read_text() if report_path.exists() else ''
    try:
        report = json.loads(raw)
    except ValueError:
        report = {}
    observation = {'id': name, 'expected': expected, 'exit': p.returncode,
                   'stderr': p.stderr[-1800:].replace(str(repo), '<fixture>'),
                   'reportPresent': bool(raw), 'reportKind': 'json' if report else raw[:60],
                   'status': report.get('status'), 'summary': report.get('summary'),
                   'comparison': report.get('comparison'),
                   'preview': raw[:2500],
                   'findings': report.get('findings'), 'issues': report.get('issues')}
    observations.append(observation)
    print(name, 'exit=', p.returncode, 'status=', report.get('status'),
          'findings=', len(report.get('findings') or []), flush=True)
    return report

with tempfile.TemporaryDirectory(prefix='metricstree-acceptance-audit-') as directory:
    root = Path(directory)
    repo = init(root, 'profile')
    config(repo)
    run(repo, 'explicit-profile-conflict', ['--profile', 'relaxed'], expected='exit 2, explicit legacy profile conflicts with maintainability')
    put(repo, 'broken-baseline.json', 'invalid')
    run(repo, 'invalid-baseline-empty-diff', ['--findings-baseline', 'broken-baseline.json'], expected='exit 2, baseline validated before empty-diff fast path')

    repo = init(root, 'legacy-method', 17)
    config(repo)
    put(repo, 'methods.json', json.dumps([{'name': 'ComplexMethod', 'conditions': [{'metric': 'CC', 'min': 16}]}]))
    for fmt in ['json', 'agent-md', 'html', 'sarif']:
        output = repo / f'legacy.{fmt}'
        p = subprocess.run([str(cli), 'detect', '-s', 'src', '--no-config', '--method-rules',
                            'methods.json', '--format', fmt, '-o', str(output)],
                           cwd=repo, capture_output=True, text=True, timeout=60)
        raw = output.read_text() if output.exists() else ''
        observations.append({'id': f'legacy-method-{fmt}', 'expected': 'matching run(int) and ComplexMethod rendered',
                             'exit': p.returncode, 'hasMethod': 'run(int)' in raw,
                             'hasRule': 'ComplexMethod' in raw, 'stderr': p.stderr[-1800:]})

    repo = init(root, 'warn')
    config(repo)
    put(repo, 'src/main/java/Demo.java', source(17))
    run(repo, 'warn-under-enforce', ['--enforcement', 'enforce'], expected='exit 0, warn never blocks')
    config(repo, {'enabledRules': ['MT-M001'], 'rules': {'MT-M001': {'mode': 'error', 'limits': {'CC': {'min': 100}}}}})
    run(repo, 'limits-override', ['--enforcement', 'enforce'], expected='no match, CC=18 < 100')
    config(repo, {'enabledRules': ['MT-M001'], 'roles': [{'pathRegex': '.*', 'role': 'dto'}]})
    run(repo, 'dto-role', ['--enforcement', 'enforce'], expected='no match, dto not applicable')
    config(repo, {'enabledRules': ['MT-M001'], 'rules': {'MT-M001': {'roles': ['test']}}})
    run(repo, 'per-rule-role', ['--enforcement', 'enforce'], expected='no match on production role')
    config(repo, {'enabledRules': ['MT-M001'], 'rules': {'MT-M001': {'mode': 'error'}}})
    run(repo, 'range-trace-and-before', ['--enforcement', 'enforce'], expected='method starts at line 4; CC before=1, after=18, delta=17; contributions present')
    run(repo, 'gate-sarif', ['--format', 'sarif'], expected='SARIF report written')
    run(repo, 'advisory-agent-md', ['--format', 'agent-md'], expected='new advisory finding visible in compact report')
    run(repo, 'detect-html', ['--format', 'html'], detect=True, expected='HTML, lifecycle CURRENT')
    run(repo, 'detect-sarif', ['--format', 'sarif'], detect=True, expected='SARIF report written')
    config(repo, {'enabledRules': ['MT-M001'], 'suppressions': [{'ruleId': 'MT-M001',
                  'entity': {'path': 'never.java', 'class': 'Never', 'signature': 'run(int)'},
                  'reason': 'stale audit fixture'}]})
    run(repo, 'detect-stale-suppression', detect=True, expected='stale suppression status emitted')
    observations[-1]['suppressions'] = json.loads((repo/'report.json').read_text()).get('suppressions')
    config(repo, {'enabledRules': ['MT-C002'], 'suppressions': [{'ruleId': 'MT-C002',
                  'entity': {'path': 'main/java/Demo.java', 'class': 'Demo'},
                  'reason': 'exact class exception'}]})
    run(repo, 'class-suppression-config', detect=True, expected='class identity accepted without method signature')
    config(repo, {'enabledRules': ['MT-M001'], 'rules': {'MT-M001': {'mode': 'error'}}})
    put(repo, 'src/main/java/Demo.java', 'class Demo { void broken( }')
    run(repo, 'detect-parse-error', detect=True, expected='exit 1, parse diagnostic')
    put(repo, 'src/main/java/Demo.java', 'record Demo(int x) {}')
    run(repo, 'detect-record', detect=True, expected='exit 2, unsupported input diagnostic')
    put(repo, 'src/main/java/Demo.java', source(17))
    config(repo, detect={'policy': 'maintainability', 'enforcement': 'enforce'})
    p = subprocess.run([str(cli), 'detect', '-s', 'src', '--config', 'audit.json', '-o', 'configured.json'],
                       cwd=repo, text=True, capture_output=True, timeout=60)
    observations.append({'id': 'detect-policy-from-config', 'expected': 'configured maintainability policy runs',
                         'exit': p.returncode, 'stderr': p.stderr[-1800:]})

    repo = init(root, 'scope', 17)
    config(repo, {'enabledRules': ['MT-M001'], 'rules': {'MT-M001': {'mode': 'error'}}})
    put(repo, 'src/main/java/Other.java', source(0, name='Other'))
    run(repo, 'unchanged-neighbor', ['--enforcement', 'enforce'], expected='no finding on unchanged Demo')
    repo = init(root, 'removed', 17)
    config(repo)
    put(repo, 'src/main/java/Demo.java', source(0, method='replacement'))
    run(repo, 'removed-method', expected='resolved entity-removed finding for run(int)')

    repo = init(root, 'symlink')
    config(repo)
    (repo / 'Link.java').symlink_to('src/main/java/Demo.java')
    run(repo, 'java-symlink', expected='exit 2 and unsupported issue')
    repo = init(root, 'new-staged')
    config(repo)
    put(repo, 'src/main/java/New.java', source(17, name='New'))
    git(repo, 'add', 'src/main/java/New.java')
    run(repo, 'staged-new-default-worktree', ['--enforcement', 'enforce'], expected='new staged file included in worktree')

    repo = init(root, 'baseline-empty', 15)
    config(repo, {'enabledRules': ['MT-M001'], 'rules': {'MT-M001': {'mode': 'error'}}})
    run(repo, 'baseline-empty-diff-export', ['--write-findings-baseline', 'baseline.json'], expected='baseline written, containing CC=16 debt')
    observations[-1]['baselinePresent'] = (repo / 'baseline.json').exists()
    repo = init(root, 'baseline-ratchet')
    config(repo, {'enabledRules': ['MT-M001'], 'rules': {'MT-M001': {'mode': 'error'}}})
    put(repo, 'src/main/java/Demo.java', source(15))
    run(repo, 'baseline-initial-export', ['--write-findings-baseline', 'baseline.json'], expected='baseline accepted CC=16')
    git(repo, 'add', 'src/main/java/Demo.java')
    git(repo, 'commit', '-qm', 'accept CC 16')
    for branches in [17, 19]:
        put(repo, 'src/main/java/Demo.java', source(branches))
        git(repo, 'add', 'src/main/java/Demo.java')
        git(repo, 'commit', '-qm', f'CC {branches+1}')
    put(repo, 'src/main/java/Demo.java', source(21))
    run(repo, 'baseline-cumulative-growth', ['--findings-baseline', 'baseline.json', '--enforcement', 'enforce'], expected='exit 1, CC=22 is +6 vs accepted 16')
    git(repo, 'add', 'src/main/java/Demo.java')
    git(repo, 'commit', '-qm', 'CC 22')
    git(repo, 'mv', 'src/main/java/Demo.java', 'src/main/java/Moved.java')
    run(repo, 'exact-move-baseline', ['--findings-baseline', 'baseline.json', '--enforcement', 'enforce'], expected='baseline matched through previous identity, cumulative growth still blocks')
    run(repo, 'exact-move-baseline-staged', ['--mode', 'staged', '--findings-baseline', 'baseline.json', '--enforcement', 'enforce'], expected='baseline move correspondence retained')

    repo = init(root, 'base-broken')
    put(repo, 'src/main/java/Demo.java', 'class Demo { void broken( }')
    git(repo, 'add', 'src/main/java/Demo.java')
    git(repo, 'commit', '-qm', 'broken base')
    config(repo)
    put(repo, 'src/main/java/Demo.java', source(17))
    run(repo, 'unavailable-base', expected='comparison-unavailable, no invented new entity')
    repo = init(root, 'optional-semantic')
    config(repo, {'enabledRules': ['MT-C001']})
    put(repo, 'src/main/java/Demo.java', source(1))
    run(repo, 'optional-semantic-local', expected='exit 0 with optional unavailable warning')

    repo = init(root, 'parse-status')
    config(repo)
    put(repo, 'src/main/java/Demo.java', 'class Demo { void broken( }')
    run(repo, 'gate-parse-status', expected='exit 1 and JSON status FAILED')

    repo = init(root, 'sidecar-only')
    config(repo)
    put(repo, 'src/main/java/Demo.java', source(1))
    p = subprocess.run([str(cli), 'gate', '--base', 'HEAD', '--policy', 'maintainability',
                        '--config', 'audit.json', '--json-output', 'sidecar.json'],
                       cwd=repo, text=True, capture_output=True, timeout=60)
    observations.append({'id': 'sidecar-only', 'expected': 'requested sidecar written',
                         'exit': p.returncode, 'sidecarPresent': (repo/'sidecar.json').exists()})

    # Exercise the actual Action script, never a reimplementation of its argument assembly.
    repo = init(root, 'action')
    put(repo, 'src/main/java/Demo.java', source(17))
    git(repo, 'add', '.')
    git(repo, 'commit', '-qm', 'complex change')
    action_script = checkout / 'scripts/run-metrics-gate-action.sh'
    env = os.environ | {'MG_CLI': str(cli), 'MG_BASE_OVERRIDE': 'HEAD~1', 'MG_MODE': 'committed',
                        'MG_FORMAT': 'json', 'MG_REPORT': 'action.json',
                        'MG_FINDINGS': 'action-findings.json', 'GITHUB_OUTPUT': str(repo/'outputs'),
                        'GITHUB_STEP_SUMMARY': str(repo/'summary')}
    p = subprocess.run(['bash', str(action_script)], cwd=repo, env=env, capture_output=True,
                       text=True, timeout=60)
    observations.append({'id': 'action-legacy-counts', 'expected': 'exit 1 and nonzero blocking-count',
                         'exit': p.returncode, 'outputs': (repo/'outputs').read_text(),
                         'stderr': p.stderr[-1800:].replace(str(repo), '<fixture>')})
    # A wrapper records the number of actual gate invocations while forwarding every argument.
    wrapper = repo/'counting-cli'
    wrapper.write_text('#!/usr/bin/env python3\nimport subprocess,sys\nfrom pathlib import Path\n'
                       + f'Path({str(repo/"calls")!r}).open("a").write(repr(sys.argv[1:])+"\\n")\n'
                       + f'sys.exit(subprocess.call([{str(cli)!r}]+sys.argv[1:]))\n')
    wrapper.chmod(0o755)
    config(repo)
    env.update({'MG_CLI': str(wrapper), 'MG_FORMAT': 'html', 'MG_POLICY': 'maintainability',
                'MG_CONFIG': 'audit.json'})
    p = subprocess.run(['bash', str(action_script)], cwd=repo, env=env, capture_output=True,
                       text=True, timeout=60)
    calls = (repo/'calls').read_text().splitlines()
    gate_calls = [line for line in calls if "'gate'" in line]
    observations.append({'id': 'action-html-scan-count', 'expected': 'one analysis, two reports',
                         'exit': p.returncode, 'gateCalls': len(gate_calls), 'calls': calls,
                         'stderr': p.stderr[-1800:].replace(str(repo), '<fixture>')})

args.out.parent.mkdir(parents=True, exist_ok=True)
args.out.write_text(json.dumps({'revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=checkout, text=True).strip(),
                                'observations': observations}, indent=2) + '\n')
