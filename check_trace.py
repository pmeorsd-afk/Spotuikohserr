import re

with open('trace_01', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

for block in text.split('\n\n'):
    lines = [l.strip() for l in block.split('\n') if l.strip()]
    if not lines: continue
    hdr = lines[0]
    if hdr.startswith('"') and any(k in hdr for k in ['main', 'ExoPlayer', 'Playback', 'Audio']):
        print('=== THREAD ===')
        for l in lines[:20]:
            print(l)
