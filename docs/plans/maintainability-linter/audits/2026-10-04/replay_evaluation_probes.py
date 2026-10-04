#!/usr/bin/env python3
"""Probe project-level splits and the PMD JSON adapter without downloading PMD."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile

parser = argparse.ArgumentParser()
parser.add_argument('--checkout', type=Path, default=Path.cwd())
parser.add_argument('--out', type=Path, required=True)
args = parser.parse_args()
checkout = args.checkout.resolve()
spec = importlib.util.spec_from_file_location('audited_evaluation', checkout / 'evaluation/run.py')
evaluation = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = evaluation
spec.loader.exec_module(evaluation)

cases = [{'id': 'a', 'split': 'tuning', 'provenance': {'origin': 'same-project'},
          'changes': [{'path': 'A.java', 'content': 'class A {}'}]},
         {'id': 'b', 'split': 'holdout', 'provenance': {'origin': 'same-project'},
          'changes': [{'path': 'B.java', 'content': 'class B {}'}]}]
try:
    evaluation._reject_split_leakage(cases)
    split_result = 'accepted'
except Exception as error:
    split_result = type(error).__name__ + ': ' + str(error)

with tempfile.TemporaryDirectory(prefix='metricstree-pmd-adapter-audit-') as directory:
    root = Path(directory)
    pmd = root / 'pmd-fixture'
    report = {'formatVersion': 0, 'files': [], 'processingErrors': [], 'configurationErrors': []}
    pmd.write_text('#!/usr/bin/env python3\nimport sys\n'
                  + 'if "--version" in sys.argv: print("PMD fixture")\n'
                  + 'else: print(' + repr(json.dumps(report)) + ')\n')
    pmd.chmod(0o755)
    try:
        pmd_result = evaluation._run_pmd(root, pmd, None)
    except Exception as error:
        pmd_result = {'exception': type(error).__name__, 'message': str(error)}
result = {'revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=checkout,
                                              text=True).strip(),
          'sameProjectCases': cases, 'splitValidation': split_result,
          'pmdFixtureReport': report, 'pmdAdapterResult': pmd_result,
          'realPmdExecuted': False}
args.out.parent.mkdir(parents=True, exist_ok=True)
args.out.write_text(json.dumps(result, indent=2) + '\n')
print(json.dumps(result, indent=2))
