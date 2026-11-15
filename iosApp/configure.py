import pathlib, tomllib
root = pathlib.Path(__file__).resolve().parents[1]
v = tomllib.loads((root / 'gradle/libs.versions.toml').read_text())['versions']
(root / 'iosApp/Version.xcconfig').write_text('PRISM_VERSION = ' + v['prism-versionName'] + '\nPRISM_BUILD = ' + v['prism-versionCode'] + '\n')
