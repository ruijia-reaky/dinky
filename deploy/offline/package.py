#!/usr/bin/env python3
"""Export prebuilt linux/amd64 images into three credential-free offline ZIPs."""
import argparse
import gzip
import hashlib
import json
import re
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent

def defaults():
    text = (ROOT / '.env.example').read_text(encoding='utf-8')
    version = re.search(r"^DINKY_VERSION='([^']+)'", text, re.M).group(1)
    agent = re.search(r"^AGENT_VERSION='([^']+)'", text, re.M).group(1)
    flink = re.search(r"^FLINK_IMAGE='([^']+)'", text, re.M).group(1)
    return version, agent, flink

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()

def main():
    version, agent, flink = defaults()
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='New, empty output directory')
    parser.add_argument('--agent-image', default='dinky-log-agent:' + agent)
    parser.add_argument('--dinky-image', default='dinky-offline:' + version + '-history')
    parser.add_argument('--flink-image', default=flink)
    args = parser.parse_args()
    output = args.output.resolve()
    if output == ROOT or ROOT in output.parents:
        parser.error('Output must be outside deploy/offline to avoid packaging generated files')
    if output.exists() and any(output.iterdir()):
        parser.error('Output directory must be empty; existing packages are never overwritten')
    images = {'agent': args.agent_image, 'dinky': args.dinky_image, 'flink': args.flink_image}
    for image in images.values():
        info = json.loads(subprocess.check_output(['docker', 'image', 'inspect', image]))[0]
        if info.get('Os') != 'linux' or info.get('Architecture') != 'amd64':
            parser.error('Expected linux/amd64 image: ' + image)
    output.mkdir(parents=True, exist_ok=True)
    checksums = []
    with tempfile.TemporaryDirectory(prefix='dinky-offline-') as temp:
        temp = Path(temp)
        for index, (component, image) in enumerate(images.items(), 1):
            stage = temp / component / ('dinky-offline-' + version)
            stage.mkdir(parents=True)
            for name in ['README.md', 'BUILD.md', '.env.example']:
                shutil.copy2(ROOT / name, stage / name)
            # Explicit allowlist: never traverse real .env, generated/ or images/.
            for directory in ['common', component]:
                source = ROOT / directory
                for path in source.rglob('*'):
                    if not path.is_file() or any(x.startswith('.') or x in ['images', '__pycache__'] for x in path.relative_to(source).parts):
                        continue
                    if path.suffix not in ['.sh', '.md', '.py', '.java', '.yml', '.properties'] and path.name != 'Dockerfile':
                        continue
                    target = stage / directory / path.relative_to(source)
                    target.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(path, target)
            archive = stage / component / 'images' / (component + '-image.tar.gz')
            archive.parent.mkdir(parents=True)
            # Stream without shell interpolation or an uncompressed multi-GB intermediate.
            with archive.open('wb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as compressed:
                process = subprocess.Popen(['docker', 'save', image], stdout=subprocess.PIPE)
                try:
                    shutil.copyfileobj(process.stdout, compressed, 1024 * 1024)
                finally:
                    process.stdout.close()
                if process.wait() != 0:
                    raise RuntimeError('docker save failed: ' + image)
            (archive.parent / 'SHA256SUMS').write_text(digest(archive) + '  ' + archive.name + '\n', encoding='utf-8')
            suffix = {'agent': 'log-agent', 'dinky': 'dinky-k8s', 'flink': 'flink-run-batches'}[component]
            filename = 'Dinky-' + version + '-' + str(index).zfill(2) + '-' + suffix + '.zip'
            destination = output / filename
            with zipfile.ZipFile(destination, 'w', compression=zipfile.ZIP_STORED, allowZip64=True) as bundle:
                for path in sorted(stage.rglob('*')):
                    if path.is_file():
                        bundle.write(path, path.relative_to(stage.parent).as_posix())
            with zipfile.ZipFile(destination) as bundle:
                bad = bundle.testzip()
                if bad:
                    raise RuntimeError('ZIP CRC failed: ' + bad)
            checksums.append(digest(destination) + '  ' + filename)
            print('Created ' + filename)
            shutil.rmtree(stage)
    (output / 'SHA256SUMS.txt').write_text('\n'.join(checksums) + '\n', encoding='utf-8')
    print('Complete. Real .env and deployment credentials were not read or packaged.')

if __name__ == '__main__':
    main()
