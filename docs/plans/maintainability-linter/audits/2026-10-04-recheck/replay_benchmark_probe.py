#!/usr/bin/env python3
"""Capture the benchmark's interpretation of an incomplete, nonempty report."""
import argparse
from dataclasses import asdict
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
from unittest.mock import patch

parser = argparse.ArgumentParser()
parser.add_argument('--checkout', type=Path, default=Path.cwd())
parser.add_argument('--out', type=Path, required=True)
args = parser.parse_args()
checkout = args.checkout.resolve()
spec = importlib.util.spec_from_file_location('benchmark_probe_driver', checkout/'evaluation/benchmark/run.py')
driver = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = driver
spec.loader.exec_module(driver)
document = {'status': 'INCOMPLETE', 'analysis': {'completeness': 'incomplete',
            'eligibleFiles': 1, 'analyzedFiles': 1, 'requiredGaps': 1}}
with tempfile.TemporaryDirectory(prefix='metricstree-benchmark-probe-') as directory:
    root = Path(directory)
    (root/'report.json').write_text(json.dumps(document))
    completed = subprocess.CompletedProcess(['fixture-cli'], 2, '', 'INCOMPLETE')
    with patch.object(driver.subprocess, 'run', return_value=completed):
        trial = driver.run_trial(Path('fixture-cli'), [], True, 0, root, 1)
result = {'revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=checkout,
                                              text=True).strip(),
          'expected': 'complete is false for a report with one required gap and exit 2',
          'suppliedReport': document, 'observedTrial': asdict(trial),
          'method': 'deterministic report/process fixture; no real benchmark timing'}
args.out.parent.mkdir(parents=True, exist_ok=True)
args.out.write_text(json.dumps(result, indent=2)+'\n')
print(json.dumps(result, indent=2))
