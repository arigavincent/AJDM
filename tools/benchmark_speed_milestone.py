"""Alternate two AJDM builds against identical local fixtures; never fetch an external URL."""
import argparse
import csv
import json
import os
from pathlib import Path
import statistics
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline', type=Path, help='Unchanged ajdm-bench installDist directory')
    parser.add_argument('candidate', type=Path, help='New ajdm-bench installDist directory')
    parser.add_argument('output', type=Path, help='Fresh report directory')
    parser.add_argument('--rounds', type=int, default=6)
    parser.add_argument('--java', default=str(Path(os.environ.get('JAVA_HOME', '/usr/lib/jvm/jdk-25.0.1-oracle-x64')) / 'bin/java'))
    args = parser.parse_args()
    if args.rounds < 6 or args.rounds % 6 or args.rounds > 60:
        parser.error('Use 6..60 rounds in multiples of six to balance every run order')
    variants = {'baseline': args.baseline.resolve(), 'candidate': args.candidate.resolve()}
    for path in variants.values():
        if not (path / 'lib').is_dir():
            parser.error(f'Not an installed distribution: {path}')
    bench = list((variants['candidate'] / 'lib').glob('ajdm-bench-*.jar'))
    if len(bench) != 1:
        parser.error('Candidate must contain one benchmark jar with SpeedMilestoneBenchmark')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.mkdir()
    scenarios = ['unthrottled', 'per-response-cap', 'shared-cap', 'response-delay', 'changing-shared-rate', 'dropped-bodies', 'growth', 'laggard']
    fields = ['scenario', 'trial', 'order', 'variant', 'mode', 'bytes', 'seconds', 'received_bytes', 'payload_requests', 'peak_responses', 'sha256']
    orders = [[('baseline', 'fixed'), ('baseline', 'adaptive'), ('candidate', 'adaptive')], [('baseline', 'adaptive'), ('candidate', 'adaptive'), ('baseline', 'fixed')], [('candidate', 'adaptive'), ('baseline', 'fixed'), ('baseline', 'adaptive')], [('candidate', 'adaptive'), ('baseline', 'adaptive'), ('baseline', 'fixed')], [('baseline', 'adaptive'), ('baseline', 'fixed'), ('candidate', 'adaptive')], [('baseline', 'fixed'), ('candidate', 'adaptive'), ('baseline', 'adaptive')]]
    with (args.output / 'samples.csv').open('w') as out:
        csv.DictWriter(out, fields).writeheader()
    (args.output / 'environment.json').write_text(json.dumps({'rounds': args.rounds, 'java': args.java, 'baseline': str(variants['baseline']), 'candidate': str(variants['candidate']), 'host_learning': 'disabled', 'timing': 'cold JVM request through durable publication; fixture and engine construction and independent rehash excluded', 'conditions': 'HTTP loopback application-level pacing/delay/body faults, not real cellular TCP conditions'}, indent=2))
    samples = []
    for trial in range(1, args.rounds + 1):
        for scenario in scenarios:
            for position, (variant, mode) in enumerate(orders[(trial - 1) % 6], 1):
                dest = args.output / f'{scenario}-{trial}-{variant}-{mode}'
                classpath = str(bench[0]) + os.pathsep + str(variants[variant] / 'lib/*')
                command = [args.java, '--enable-native-access=ALL-UNNAMED', '-Xmx256m', '-cp', classpath, 'io.ajdm.bench.SpeedMilestoneBenchmark', scenario, mode, str(dest)]
                with (args.output / f'{dest.name}.log').open('w') as log:
                    subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=210)
                with (dest / 'sample.csv').open() as source:
                    row = next(csv.DictReader(source))
                row.update(trial=trial, order=position, variant=variant)
                samples.append(row)
                with (args.output / 'samples.csv').open('a') as out:
                    csv.DictWriter(out, fields).writerow(row)
                print(f'{scenario} {trial}/{args.rounds} {variant}/{mode}: {row["seconds"]} s; independently hashed', flush=True)
    report = ['# Local speed comparison', '', '| Scenario | Baseline fixed median s | Baseline adaptive median s | Candidate adaptive median s | Candidate change vs old adaptive |', '|---|---:|---:|---:|---:|']
    for scenario in scenarios:
        medians = [statistics.median(float(row['seconds']) for row in samples if row['scenario'] == scenario and row['variant'] == variant and row['mode'] == mode) for variant, mode in orders[0]]
        report.append(f'| {scenario} | {medians[0]:.3f} | {medians[1]:.3f} | {medians[2]:.3f} | {(medians[2] / medians[1] - 1) * 100:+.1f}% |')
    report += ['', f'All {len(samples)} completed files were independently SHA-256 verified. Negative time changes indicate faster completion. Raw samples and per-download decision traces are retained.', '', 'Cold processes, balanced orders and identical explicit transfer options; host learning disabled. No IDM, physical 4G/5G, TLS/media extraction or mux results. Full regression, crash and native acceptance remain separate.']
    (args.output / 'report.md').write_text('\n'.join(report) + '\n')


if __name__ == '__main__':
    main()
