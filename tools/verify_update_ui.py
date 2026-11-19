"""Exercise update prompts and the Android 4.x installer using a low-version probe."""
import argparse
import re
import subprocess
import time
import xml.etree.ElementTree as ET
import json
import urllib.request
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', default='D:/Android/Sdk/platform-tools/adb.exe')
parser.add_argument('--serial', default='emulator-5556')
parser.add_argument('--package', default='com.netease.cloudmusic')
parser.add_argument('--install', action='store_true')
args = parser.parse_args()
adb = [args.adb, '-s', args.serial]
work = Path('build')
with urllib.request.urlopen('https://nomusic.losertree.de/updates/latest.json', timeout=12) as response:
    expected = json.load(response)['packages'][args.package]['versionCode']


def shell(command):
    result = subprocess.run(adb + ['shell', command], capture_output=True, timeout=20)
    if result.returncode:
        raise RuntimeError('Device operation failed')
    return result.stdout


def dump():
    # Pull the XML directly: older Windows adb recodes shell cat output to the OEM code page.
    shell('uiautomator dump /data/local/tmp/nomusic-update-ui.xml')
    subprocess.run(adb + ['pull', '/data/local/tmp/nomusic-update-ui.xml', str(work / 'nomusic-update-ui.xml')], capture_output=True, check=True)
    return ET.parse(work / 'nomusic-update-ui.xml').getroot()


def locate(tree, words):
    return next((node for node in tree.iter('node') if words in (node.get('text', ''), node.get('content-desc', ''))), None)


def tap(node):
    assert node is not None, 'Control missing'
    x, y, right, bottom = map(int, re.findall(r'\d+', node.get('bounds', '')))
    shell(f'input tap {(x+right)//2} {(y+bottom)//2}')


shell(f'am start -n {args.package}/com.nomusic.app.MainActivity')
time.sleep(1)
tree = dump()
if locate(tree, '下载更新') is not None:
    shell('input keyevent 4')
    tree = dump()
close = locate(tree, '退出全屏播放')
if close is not None:
    tap(close)
    tree = dump()
tap(locate(tree, '设置'))
tree = dump()
check = next((node for node in tree.iter('node') if node.get('text', '').startswith('检查更新')), None)
if check is None:
    shell('input swipe 700 560 700 280 400')
    tree = dump()
    check = next((node for node in tree.iter('node') if node.get('text', '').startswith('检查更新')), None)
tap(check)
time.sleep(1.5)
tap(locate(dump(), '下载更新'))
time.sleep(3)
tap(locate(dump(), '安装'))
time.sleep(.8)
tree = dump()
blocked = locate(tree, '设置')
unknown = any('未知来源' in node.get('text', '') for node in tree.iter('node'))
if unknown and blocked is not None:
    tap(blocked)
    tree = dump()
    source = next((node for node in tree.iter('node') if '未知来源' in node.get('text', '')), None)
    tap(source)
    tree = dump()
    yes = locate(tree, '确定')
    if yes is not None:
        tap(yes)
    shell('input keyevent 4')
    shell(f'am start -n {args.package}/com.nomusic.app.MainActivity')
    time.sleep(.5)
    tree = dump()
    check = next((node for node in tree.iter('node') if node.get('text', '').startswith('检查更新')), None)
    tap(check)
    tap(locate(dump(), '安装'))
    time.sleep(.7)
    tree = dump()
assert locate(tree, '安装') is not None or locate(tree, 'Install') is not None, 'Installer was not displayed'
assert not any('解析程序包时出现问题' in node.get('text', '') for node in tree.iter('node')), 'Installer could not parse APK'
shell('screencap -p /data/local/tmp/nomusic-installer.png')
subprocess.run(adb + ['pull', '/data/local/tmp/nomusic-installer.png', str(work / 'nomusic-installer.png')], check=True, capture_output=True)
print('PASS Update prompt, download, validation and package installer handoff')
if args.install:
    tap(locate(tree, '安装') if locate(tree, '安装') is not None else locate(tree, 'Install'))
    time.sleep(3)
    tree = dump()
    installed = shell(f'dumpsys package {args.package}').decode('ascii', errors='ignore')
    assert re.search(r'versionCode=' + str(expected) + r'\b', installed), 'System upgrade did not complete'
    assert 'DEBUGGABLE' not in installed, 'Probe was not replaced by a release APK'
    print('PASS System upgrade completed')
    done = locate(tree, '完成')
    if done is None:
        done = locate(tree, 'Done')
    if done is not None:
        tap(done)
