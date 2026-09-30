import xml.etree.ElementTree as ET

tree = ET.parse('dump.xml')
for elem in tree.iter():
    t = elem.attrib.get('text')
    c = elem.attrib.get('clickable')
    b = elem.attrib.get('bounds')
    d = elem.attrib.get('content-desc')
    if c == 'true' or t:
        print(f"{b} clickable={c} text='{t}' desc='{d}'")
