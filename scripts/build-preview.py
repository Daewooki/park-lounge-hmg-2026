#!/usr/bin/env python3
"""Bundle a file-openable UI preview. Does not enable audio or emulate a vehicle."""
import base64
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
WEB = ROOT / 'app/src/main/assets/web'
cache = {}

def module_url(path):
    path = path.resolve()
    if path in cache:
        return cache[path]
    source = path.read_text()
    def dependency(match):
        target = (path.parent / match[2]).resolve()
        if not target.is_relative_to(WEB):
            raise ValueError('Module leaves local web asset directory')
        return match[1] + module_url(target) + match[3]
    source = re.sub(r'''(\bfrom\s*['"])(\.[^'"]+)(['"])''', dependency, source)
    encoded = 'data:text/javascript;base64,' + base64.b64encode(source.encode()).decode()
    cache[path] = encoded
    return encoded

html = (WEB / 'index.html').read_text()
def inline_image(match):
    path = (WEB / match[1]).resolve()
    if not path.is_relative_to(WEB) or not path.is_file():
        raise ValueError('Preview image must be a packaged local asset')
    mime = 'image/svg+xml' if path.suffix.lower() == '.svg' else 'image/png'
    return 'src="data:' + mime + ';base64,' + base64.b64encode(path.read_bytes()).decode() + '"'
html = re.sub(r'src="(brand/[^\"]+)"', inline_image, html)
html = re.sub(r'<meta http-equiv="Content-Security-Policy"[^>]+>', '', html)
html = html.replace('<link rel="stylesheet" href="styles.css">', '<style>'+ (WEB/'styles.css').read_text() +'</style>')
app = (WEB/'app.js').read_text().replace("new URLSearchParams(location.search).get('preview') === '1'", 'true')
# Keep Android production assets untouched; only this development artifact opts in.
html = html.replace('<script src="app.js" defer></script>', '')
html = html.replace('<script src="scene-view.js" type="module"></script>', '')
html = html.replace('</body>', '<script>'+app+'</script><script type="module" src="'+module_url(WEB/'scene-view.js')+'"></script></body>')
output = ROOT/'dist/park-lounge-preview.html'
output.parent.mkdir(exist_ok=True)
output.write_text(html)
print('Browser-only preview (no audio, no vehicle validation):', output)
