#!/usr/bin/env python3
"""Generate LifeBuddy's Android vector resources from the selected SVG master."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
BRAND = ROOT / "design/lifebuddy"
RES = ROOT / "app/src/main/res"
NS = "http://www.w3.org/2000/svg"
MASTER = BRAND / "lifebuddy.svg"


def rect_path(node):
    x, y = float(node.get("x", 0)), float(node.get("y", 0))
    w, h = float(node.get("width")), float(node.get("height"))
    r = min(float(node.get("rx", 0)), w / 2, h / 2)
    return (f"M{x+r:g},{y:g}H{x+w-r:g}A{r:g},{r:g} 0 0 1 {x+w:g},{y+r:g}"
            f"V{y+h-r:g}A{r:g},{r:g} 0 0 1 {x+w-r:g},{y+h:g}"
            f"H{x+r:g}A{r:g},{r:g} 0 0 1 {x:g},{y+h-r:g}"
            f"V{y+r:g}A{r:g},{r:g} 0 0 1 {x+r:g},{y:g}Z")


def vector(nodes, size):
    lines = [f'<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="{size}dp" android:height="{size}dp" android:viewportWidth="256" android:viewportHeight="256">']
    for node in nodes:
        d = node.get("d") if node.tag == f"{{{NS}}}path" else rect_path(node)
        attributes = {"pathData": d, "fillColor": "#00000000" if node.get("fill") == "none" else node.get("fill", "#000000")}
        for svg, android in [("fill-rule", "fillType"), ("stroke", "strokeColor"), ("stroke-width", "strokeWidth"), ("stroke-linecap", "strokeLineCap")]:
            if svg in node.attrib:
                attributes[android] = node.get(svg).replace("evenodd", "evenOdd")
        lines.append("    <path " + " ".join(f'android:{k}="{v}"' for k, v in attributes.items()) + " />")
    return "\n".join(lines) + "\n</vector>\n"


def svg(nodes, title):
    ET.register_namespace("", NS)
    root = ET.Element(f"{{{NS}}}svg", {"viewBox": "0 0 256 256", "role": "img", "aria-label": "LifeBuddy 小布"})
    ET.SubElement(root, f"{{{NS}}}title").text = title
    root.extend(nodes)
    ET.indent(root, space="  ")
    return ET.tostring(root, encoding="unicode") + "\n"


def main():
    source = ET.parse(MASTER).getroot()
    nodes = [n for n in source if n.tag in {f"{{{NS}}}path", f"{{{NS}}}rect"}]
    foreground = nodes[1:]
    (RES / "drawable/ic_agent.xml").write_text(vector(nodes, 48))
    (RES / "drawable/ic_lifebuddy_foreground.xml").write_text(vector(foreground, 108))
    (BRAND / "lifebuddy-mark.svg").write_text(svg(foreground, "LifeBuddy · 小布 · 透明底"))

    # The themed icon is an alpha silhouette. Eye and nose cutouts must remain
    # transparent, otherwise Android tinting would turn the face into a blob.
    eye_paths = " ".join(rect_path(n) for n in foreground if n.tag == f"{{{NS}}}rect")
    nose_with_stem = "M120 147Q128 143 136 147Q140 153 130 158V164A2 2 0 0 1 126 164V158Q116 153 120 147Z"
    mono = [ET.Element(f"{{{NS}}}path", {"fill": "#74533D", "fill-rule": "evenodd", "d": foreground[0].get("d") + " " + eye_paths + " " + nose_with_stem})]
    mono.extend(ET.Element(f"{{{NS}}}path", {"fill": "#74533D", "d": n.get("d")}) for n in foreground[1:3])
    (RES / "drawable/ic_lifebuddy_monochrome.xml").write_text(vector(mono, 108))
    (BRAND / "lifebuddy-monochrome.svg").write_text(svg(mono, "LifeBuddy · 小布 · 单色透明底"))
    print("Updated LifeBuddy SVG variants and three Android vector resources.")


if __name__ == "__main__":
    main()
