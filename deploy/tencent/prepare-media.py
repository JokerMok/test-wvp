#!/usr/bin/env python3
"""Install the pinned Linux amd64 ZLMediaKit runtime without loading the full image."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import tempfile
from urllib.request import Request, urlopen
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

REGISTRY = 'https://mirror.ccs.tencentyun.com/v2/zlmediakit/zlmediakit'
MANIFEST = 'sha256:f0022a9ebe3085f2710a5a9312975564f22a8c8449be88217b55ce7e3cf9539b'
BINARY_LAYER = 'sha256:066efdcd5f91d780e006f8eab6942b073e2c9166356e7e48c569407ab41b8bf0'
PYTHON_LAYER = 'sha256:14ef7e212bd4f4d7239017f3fe02278bfb4837e4f516a051781865e49be3b7d8'
RUNTIME = Path('/srv/apps/test-wvp/runtime/zlm')


def download_layer(stage, digest):
    path = stage / 'layer.tar.gz'
    with urlopen(f'{REGISTRY}/blobs/{digest}', timeout=30) as response, path.open('wb') as output:
        shutil.copyfileobj(response, output, length=1024 * 1024)
    with path.open('rb') as stream:
        assert 'sha256:' + hashlib.file_digest(stream, 'sha256').hexdigest() == digest
    return path


def install():
    assert os.geteuid() == 0, 'Run as root'
    assert subprocess.run(['systemctl', 'is-active', '--quiet', 'test-wvp-media.service']).returncode != 0
    assert os.uname().machine == 'x86_64'
    request = Request(f'{REGISTRY}/manifests/{MANIFEST}',
                      headers={'Accept': 'application/vnd.oci.image.manifest.v1+json'})
    with urlopen(request, timeout=20) as response:
        manifest = response.read()
    assert 'sha256:' + hashlib.sha256(manifest).hexdigest() == MANIFEST
    digests = {layer['digest'] for layer in json.loads(manifest)['layers']}
    assert {BINARY_LAYER, PYTHON_LAYER} <= digests

    with tempfile.TemporaryDirectory(prefix='test-wvp-zlm-', dir='/dev/shm') as temporary:
        stage = Path(temporary)
        binary = download_layer(stage, BINARY_LAYER)
        with tarfile.open(binary, 'r:gz') as source:
            with (stage / 'MediaServer').open('wb') as output:
                member = next(entry for entry in source if entry.name.removeprefix('./') == 'opt/media/bin/MediaServer')
                shutil.copyfileobj(source.extractfile(member), output)
        python = download_layer(stage, PYTHON_LAYER)
        package = stage / 'python312.zip'
        with tarfile.open(python, 'r|gz') as source, ZipFile(package, 'w', ZIP_DEFLATED) as zip_file:
            for member in source:
                path = member.name.removeprefix('./')
                if not member.isfile():
                    continue
                if path in ('usr/lib/x86_64-linux-gnu/libpython3.12.so.1.0',
                            'lib/x86_64-linux-gnu/libpython3.12.so.1.0'):
                    with (stage / 'libpython3.12.so.1.0').open('wb') as output:
                        shutil.copyfileobj(source.extractfile(member), output)
                elif path.startswith('usr/lib/python3.12/') and path.endswith('.py'):
                    relative = path.removeprefix('usr/lib/python3.12/')
                    if relative.split('/')[0] not in ('dist-packages', 'site-packages', 'test', 'idlelib', 'tkinter'):
                        zip_file.writestr(ZipInfo(relative), source.extractfile(member).read(), compress_type=ZIP_DEFLATED)
            assert 'encodings/__init__.py' in zip_file.namelist()

        subprocess.run(['install', '-d', '-m', '0755', RUNTIME / 'python/lib'], check=True)
        subprocess.run(['install', '-m', '0755', stage / 'MediaServer', RUNTIME], check=True)
        subprocess.run(['install', '-m', '0644', stage / 'libpython3.12.so.1.0', RUNTIME], check=True)
        subprocess.run(['install', '-m', '0644', package, RUNTIME / 'python/lib'], check=True)
    subprocess.run(['sha256sum', RUNTIME / 'MediaServer', RUNTIME / 'libpython3.12.so.1.0',
                    RUNTIME / 'python/lib/python312.zip'], check=True)


if __name__ == '__main__':
    install()
