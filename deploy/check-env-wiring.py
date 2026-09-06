#!/usr/bin/env python3
"""Every `${VAR}` placeholder of application.yml must reach the containers.

Compose passes only the keys a service lists, and the Helm chart only what its deployment
template names, so a variable added to application.yml is unreachable on those deployments
until it is added to each of them by hand. This script lists the placeholders of one or more
prefixes and reports the targets that do not mention them.

    python deploy/check-env-wiring.py                       # the AI / MCP prefixes, CE targets
    python deploy/check-env-wiring.py --ee ../openfilz-enterprise/docker/dokploy-compose-ee.yml
    python deploy/check-env-wiring.py --prefix OPENFILZ_    # any prefix
    python deploy/check-env-wiring.py --all                 # every placeholder (many are dev-only)

Exit code 1 when a checked variable is missing from a target. Run it from the openfilz-core root.
"""
import argparse
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
APPLICATION_YML = ROOT / 'openfilz-api/src/main/resources/application.yml'
DEFAULT_PREFIXES = ('OPENFILZ_AI_', 'OPENFILZ_MCP_', 'TRANSFORMERS_', 'OLLAMA_', 'OPENAI_', 'ANTHROPIC_', 'GOOGLE_', 'AI_')
TARGETS = {
    'ce compose (base + AI overlay)': [ROOT / 'deploy/docker-compose/docker-compose.yml',
                                       ROOT / 'deploy/docker-compose/docker-compose.ai.yml'],
    'ce dokploy compose': [ROOT / 'deploy/docker-compose/dokploy/compose.yaml'],
    'helm openfilz-api': [ROOT / 'deploy/helm/openfilz-api/templates/deployment.yaml'],
}
# Advertised, not consumed: harmless to leave at the default
IGNORED = {'OPENFILZ_MCP_SERVER_VERSION'}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--prefix', action='append', help='placeholder prefix to check (repeatable)')
    parser.add_argument('--all', action='store_true', help='check every placeholder')
    parser.add_argument('--ee', help='path of the enterprise compose file to check as well')
    args = parser.parse_args()

    placeholders = sorted(set(re.findall(r'\$\{([A-Z][A-Z0-9_]+)', APPLICATION_YML.read_text(encoding='utf-8'))))
    prefixes = tuple(args.prefix) if args.prefix else DEFAULT_PREFIXES
    checked = [v for v in placeholders if (args.all or v.startswith(prefixes)) and v not in IGNORED]

    targets = dict(TARGETS)
    if args.ee:
        targets['ee compose'] = [pathlib.Path(args.ee)]
    texts = {name: '\n'.join(p.read_text(encoding='utf-8') for p in paths) for name, paths in targets.items()}

    missing = 0
    for var in checked:
        gaps = [name for name, text in texts.items() if var not in text]
        if gaps:
            missing += 1
            print(f'MISSING {var}: {", ".join(gaps)}')
    print(f'{len(checked)} variable(s) checked against {len(targets)} target(s): '
          + ('all wired' if missing == 0 else f'{missing} missing'))
    return 1 if missing else 0


if __name__ == '__main__':
    sys.exit(main())
