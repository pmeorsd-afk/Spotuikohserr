with open('trace_01', 'r', encoding='utf-8', errors='ignore') as f:
    text = f.read()

for block in text.split('\n\n'):
    lines = [l.strip() for l in block.split('\n') if l.strip()]
    if not lines: continue
    hdr = lines[0]
    if hdr.startswith('"'):
        managed = [l for l in lines if l.startswith('at ')]
        print(hdr)
        for m in managed[:3]:
            print('  ', m)
