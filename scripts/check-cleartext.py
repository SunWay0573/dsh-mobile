#!/usr/bin/env python3
"""Read the cleartext policy out of a BUILT Android APK.

Why the built artifact rather than the source XML: the source says what was
written, the APK says what ships. The manifest merges contributions from
libraries, resource files are renamed in release builds, and neither of those is
visible in `src/main/res/xml/network_security_config.xml`.

That renaming is not hypothetical. In this project's own release APK the config
lands at `res/8G.xml`, so the file cannot be found by name -- it has to be
resolved from the manifest attribute, through the resource table, to whatever
path the packager chose.

Usage:
    check-cleartext.py <apk> <true|false>

Exit codes:
    0  base-config matches the expected value
    1  it does not, or the config is missing
    2  could not be determined (no aapt2, or the APK is unreadable)
"""

import os
import re
import subprocess
import sys


def find_aapt2() -> str | None:
    """Locate aapt2, preferring the highest build-tools version present."""
    candidates = []
    for root in (os.environ.get('ANDROID_HOME'), os.environ.get('ANDROID_SDK_ROOT'),
                 os.path.expanduser('~/Library/Android/sdk')):
        if not root:
            continue
        tools = os.path.join(root, 'build-tools')
        if not os.path.isdir(tools):
            continue
        for version in os.listdir(tools):
            binary = os.path.join(tools, version, 'aapt2')
            if os.path.isfile(binary):
                candidates.append((version, binary))
    if not candidates:
        return None
    # Sort on the numeric components so 35.0.0 beats 9.0.0, which string order
    # gets backwards.
    def key(item):
        return [int(part) for part in re.findall(r'\d+', item[0])]
    return sorted(candidates, key=key)[-1][1]


def dump(aapt2: str, *args: str) -> str:
    result = subprocess.run([aapt2, 'dump', *args], capture_output=True, text=True)
    return result.stdout


def main() -> int:
    if len(sys.argv) != 3 or sys.argv[2] not in ('true', 'false'):
        print(__doc__.strip(), file=sys.stderr)
        return 2
    apk, expected = sys.argv[1], sys.argv[2]

    if not os.path.isfile(apk):
        print(f'no APK at {apk}', file=sys.stderr)
        return 2
    aapt2 = find_aapt2()
    if aapt2 is None:
        print('no aapt2 found', file=sys.stderr)
        return 2

    # 1. The manifest attribute gives a resource id, not a path. A release build
    #    may also omit it entirely, in which case the platform default applies.
    manifest = dump(aapt2, 'xmltree', '--file', 'AndroidManifest.xml', apk)
    attribute = re.search(r'networkSecurityConfig\(0x[0-9a-f]+\)=@(0x[0-9a-f]+)', manifest)
    if not attribute:
        if re.search(r'usesCleartextTraffic\(0x[0-9a-f]+\)=\(type 0x12\)0x0\b', manifest):
            print('manifest sets usesCleartextTraffic=false (no config resource)')
            return 0 if expected == 'false' else 1
        print('no network security config and no explicit cleartext flag', file=sys.stderr)
        return 1

    resource_id = attribute.group(1)

    # 2. Resolve the id through the resource table to the packed file name.
    resources = dump(aapt2, 'resources', apk)
    if not resources.strip():
        print('aapt2 produced no resource table', file=sys.stderr)
        return 2
    entry = re.search(
        rf'resource {re.escape(resource_id)} .*?\n(.*?)(?=\n    resource |\Z)',
        resources, re.S)
    if not entry:
        print(f'resource {resource_id} not found in the table', file=sys.stderr)
        return 1
    path = re.search(r'\(file\) (\S+)', entry.group(1))
    if not path:
        print(f'resource {resource_id} has no file', file=sys.stderr)
        return 1

    # 3. Read the policy out of that file.
    config = dump(aapt2, 'xmltree', '--file', path.group(1), apk)
    base = re.search(r'E: base-config.*?(?=E: |\Z)', config, re.S)
    if not base:
        print('no base-config in the compiled config', file=sys.stderr)
        return 1
    value = re.search(r'cleartextTrafficPermitted=(\w+)', base.group(0))
    if not value:
        print('base-config does not state cleartextTrafficPermitted', file=sys.stderr)
        return 1

    actual = value.group(1)
    if actual != expected:
        print(f'base-config cleartextTrafficPermitted={actual}, expected {expected}',
              file=sys.stderr)
        return 1

    domains = re.findall(r"T: '([^']+)'", base.group(0))
    print(f'base-config cleartextTrafficPermitted={actual} ({path.group(1)})')
    return 0


if __name__ == '__main__':
    sys.exit(main())
