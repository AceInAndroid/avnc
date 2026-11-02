#!/usr/bin/env python3
"""Package committed source, recursive submodules and exact dependency sources.

No working-tree, ignored files, signing keys or local configuration are copied.
"""
# SPDX-License-Identifier: GPL-3.0-or-later
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
NATIVE = {
    'libjpeg-turbo-libjpeg-turbo-3.2.0.tar.gz':
        '1ce063e9e126d55019385da3a6ff4521a9a3958edeab15e2465ae3435026dac6e598277eea066173ada47da37159d0f7812b2227869c10dd141dcfe2ddeab720',
    'libressl-4.2.1.tar.gz':
        '988e580b137d9b847288c6a12fc09c4b24113905521aa4e938c964f7845080463e6f2ca3b58d800512ba5c790a06e4e41b31d187ef09c3018f2321b22ecab267',
}


def git(repo, *args):
    return subprocess.check_output(['git', '-C', str(repo), *args])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--revision', default='HEAD')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--native-downloads', type=Path, default=ROOT / 'extern/vcpkg/downloads')
    parser.add_argument('--dependency-sources', type=Path, default=ROOT / 'build/release-dependency-sources')
    args = parser.parse_args()
    revision = git(ROOT, 'rev-parse', args.revision + '^{commit}').decode().strip()
    stamp = int(git(ROOT, 'show', '-s', '--format=%ct', revision))
    prefix = 'FastVNC-source/'
    manifest = {'revision': revision, 'submodules': [], 'files': {}}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tarfile.open(args.output, 'w:gz', format=tarfile.PAX_FORMAT) as out:
        def add_bytes(name, data, mode=0o644):
            info = tarfile.TarInfo(prefix + name)
            info.size = len(data)
            info.mode = mode
            info.mtime = stamp
            out.addfile(info, io.BytesIO(data))
            manifest['files'][name] = hashlib.sha256(data).hexdigest()

        def add_repo(repo, ref, location=''):
            archive = git(repo, 'archive', '--format=tar', ref)
            with tarfile.open(fileobj=io.BytesIO(archive)) as source:
                for member in source:
                    if member.isfile():
                        add_bytes(location + member.name, source.extractfile(member).read(), member.mode)
                    elif member.issym():
                        member.name = prefix + location + member.name
                        out.addfile(member)
            tree = git(repo, 'ls-tree', '-rz', ref)
            for entry in tree.split(b'\0'):
                if not entry:
                    continue
                header, path = entry.split(b'\t', 1)
                mode, _, sha = header.decode().split()
                if mode == '160000':
                    subpath = path.decode()
                    manifest['submodules'].append({'path': location + subpath, 'revision': sha})
                    add_repo(repo / subpath, sha, location + subpath + '/')

        add_repo(ROOT, revision)
        for name, sha512 in NATIVE.items():
            data = (args.native_downloads / name).read_bytes()
            if hashlib.sha512(data).hexdigest() != sha512:
                raise ValueError('Native source hash mismatch: ' + name)
            add_bytes('dependency-sources/native/' + name, data)
        dependencies = json.loads((args.dependency_sources / 'dependencies.json').read_text())
        if not dependencies:
            raise ValueError('Runtime dependency sources have not been collected')
        for dependency in dependencies:
            name = dependency['source']
            if Path(name).name != name:
                raise ValueError('Invalid source archive filename')
            add_bytes('dependency-sources/jvm/' + name, (args.dependency_sources / name).read_bytes())
        add_bytes('dependency-sources/jvm/dependencies.json',
                  (args.dependency_sources / 'dependencies.json').read_bytes())
        add_bytes('SOURCE-ARCHIVE.md', f'''# FastVNC corresponding source

Revision: {revision}

This archive contains the application and build scripts, all recursive Git
submodules, original libjpeg-turbo 3.2.0 and LibreSSL 4.2.1 source archives, and
source JARs for all resolved release runtime dependencies. Original licenses
and copyright notices remain in their respective sources. FastVNC is licensed
under GPL-3.0-or-later; third-party components retain their own licenses.

See docs/RELEASING.md for SDK/NDK/JDK versions and build instructions.
Submodules are already populated: no git submodule command is needed after
extracting. Copy dependency-sources/native/*.tar.gz into extern/vcpkg/downloads/
before configuring to reuse the included, hash-verified native source archives.
Native dependency patches and build recipes are in extern/vcpkg/ports/;
LibVNCClient patches are in app/src/main/cpp/patches/.

Gradle, the Android toolchain and Maven dependencies are downloaded by their
standard build tools. No private release signing material is necessary to build
or run a modified version: use assembleDebug or your own release signing key.
Uninstall the official package before installing a differently signed build of
the same application ID, or use the separate debug ID.
'''.encode())
        add_bytes('SOURCE-MANIFEST.json', (json.dumps(manifest, indent=2, sort_keys=True) + '\n').encode())
    print(f'Packaged {len(manifest["files"])} files, {len(manifest["submodules"])} submodules: {args.output}')


if __name__ == '__main__':
    main()
