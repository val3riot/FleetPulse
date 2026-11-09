#!/usr/bin/env python3
"""Combine GitHub job results without accepting missing or skipped gates."""
import json
import os
from pathlib import Path
import sys

REQUIRED = ('backend', 'frontend', 'tooling', 'integration')
STATUSES = {'success', 'failure', 'cancelled', 'skipped'}


def summarize(needs):
    if not isinstance(needs, dict) or set(needs) != set(REQUIRED):
        raise ValueError('The required job inventory is incomplete or unexpected')
    rows = []
    for job in REQUIRED:
        item = needs[job]
        status = item.get('result') if isinstance(item, dict) else None
        if status not in STATUSES:
            raise ValueError('Invalid job result: ' + job)
        rows.append((job, status))
    passed = all(status == 'success' for _, status in rows)
    text = '# Quality gate\n\n| Job | Result |\n|---|---|\n'
    text += ''.join('| ' + job + ' | ' + status + ' |\n' for job, status in rows)
    text += '\nEsito: **' + ('PASS' if passed else 'FAIL') + '**.\n\n'
    text += ('Il gate include test funzionali, controlli di build e audit npm. '
             'La scansione CVE delle immagini Docker è separata e non è eseguita da questo workflow. '
             'Un esito positivo non dichiara risolti i rischi upstream o approva un deployment in produzione.\n')
    return passed, text


def main():
    try:
        passed, text = summarize(json.loads(os.environ['NEEDS_JSON']))
    except (ValueError, KeyError, TypeError) as error:
        passed, text = False, '# Quality gate\n\nFAIL: ' + str(error) + '\n'
    destination = os.environ.get('GITHUB_STEP_SUMMARY')
    if destination:
        with Path(destination).open('a') as output:
            output.write(text)
    print(text)
    return 0 if passed else 1


if __name__ == '__main__':
    sys.exit(main())
