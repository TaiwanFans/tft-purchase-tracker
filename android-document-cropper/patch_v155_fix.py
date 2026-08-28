from pathlib import Path
import re

build = Path('app/build.gradle')
s = build.read_text(encoding='utf-8')
s, n1 = re.subn(r'versionCode\s+\d+', 'versionCode 155', s, count=1)
s, n2 = re.subn(r"versionName\s+'[^']+'", "versionName '1.5.5'", s, count=1)
if n1 != 1 or n2 != 1:
    raise SystemExit(f'v1.5.5 version fix failed: versionCode={n1}, versionName={n2}')
build.write_text(s, encoding='utf-8')
print('v1.5.5 Gradle version fix applied')
