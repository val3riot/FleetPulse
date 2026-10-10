#!/usr/bin/env python3
import argparse
from pathlib import Path
from markdown_it import MarkdownIt
import subprocess, re, html, urllib.parse, json

root = Path.cwd()
parser = MarkdownIt()
paths = [
    root / p
    for p in subprocess.check_output(
        ["git", "ls-files", "*.md"], text=True
    ).splitlines()
]
anchors = {}
count = 0
broken = []
external = []
for p in paths:
    tokens = parser.parse(p.read_text())
    ids = []
    seen = {}
    for i, t in enumerate(tokens):
        if t.type == "heading_open":
            text = "".join(
                c.content
                for c in tokens[i + 1].children or []
                if c.type in ("text", "code_inline")
            )
            slug = re.sub(
                r"[^\w\- ]", "", html.unescape(text).lower(), flags=re.UNICODE
            ).replace(" ", "-")
            n = seen.get(slug, 0)
            seen[slug] = n + 1
            ids.append(slug + ("-" + str(n) if n else ""))
    anchors[p.resolve()] = ids
for p in paths:
    for t in parser.parse(p.read_text()):
        for c in t.children or []:
            if c.type not in ("link_open", "image"):
                continue
            link = c.attrGet("href" if c.type == "link_open" else "src")
            count += 1
            u = urllib.parse.urlsplit(link)
            if u.scheme or u.netloc:
                external.append(link)
                continue
            target = (
                (
                    (root if u.path.startswith("/") else p.parent)
                    / urllib.parse.unquote(u.path).lstrip("/")
                ).resolve()
                if u.path
                else p.resolve()
            )
            reason = (
                "missing file"
                if not target.exists()
                else (
                    "missing anchor"
                    if u.fragment
                    and target.suffix == ".md"
                    and urllib.parse.unquote(u.fragment) not in anchors.get(target, [])
                    else None
                )
            )
            if reason:
                broken.append(
                    dict(source=str(p.relative_to(root)), link=link, reason=reason)
                )
result = dict(
    markdownFiles=len(paths),
    links=count,
    externalLinks=sorted(set(external)),
    broken=broken,
)
print(json.dumps(result, indent=2))
raise SystemExit(bool(broken))
