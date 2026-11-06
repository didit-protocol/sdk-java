"""Turns spec descriptions (Markdown) into safe Javadoc text."""

from __future__ import annotations

import html
import re

_CODE = re.compile(r"`([^`]+)`")
_LINK = re.compile(r"\[([^\]]+)\]\((https?://[^)\s]+)\)")
_BOLD = re.compile(r"\*\*([^*]+)\*\*")


def escape(text: str) -> str:
    """Escapes text for a Javadoc comment: HTML, ``*/``, ``@`` and unicode escapes."""
    out = html.escape(text, quote=False)
    out = out.replace("*/", "*&#47;").replace("@", "&#64;").replace("\\u", "&#92;u")
    return out


def render(text: str) -> list[str]:
    """Renders Markdown-ish text as Javadoc lines (without the leading `` * ``)."""
    text = text.strip()
    if not text:
        return []
    paragraphs = re.split(r"\n\s*\n", text)
    lines: list[str] = []
    for i, para in enumerate(paragraphs):
        para = para.strip()
        if not para:
            continue
        rendered = _inline(para)
        if i > 0:
            lines.append("")
            lines.append("<p>" + rendered[0] if rendered else "<p>")
            lines.extend(rendered[1:])
        else:
            lines.extend(rendered)
    return lines


def _inline(para: str) -> list[str]:
    parts: list[str] = []
    for raw_line in para.splitlines():
        line = raw_line.rstrip()
        if re.match(r"^\s*[-*] ", line):
            line = "- " + line.split(" ", 1)[1].lstrip() if " " in line.strip() else line
        segments: list[str] = []
        pos = 0
        for match in _CODE.finditer(line):
            segments.append(_plain(line[pos : match.start()]))
            segments.append("<code>" + escape(match.group(1)) + "</code>")
            pos = match.end()
        segments.append(_plain(line[pos:]))
        parts.append("".join(segments))
    return parts


def _plain(text: str) -> str:
    out = []
    pos = 0
    for match in _LINK.finditer(text):
        out.append(_bold(escape(text[pos : match.start()])))
        out.append(f'<a href="{html.escape(match.group(2), quote=True)}">{escape(match.group(1))}</a>')
        pos = match.end()
    out.append(_bold(escape(text[pos:])))
    return "".join(out)


def _bold(text: str) -> str:
    return _BOLD.sub(lambda m: "<b>" + m.group(1) + "</b>", text)


def block(lines: list[str], indent: str) -> str:
    """A ``/** ... */`` block, or an empty string when there is nothing to say."""
    lines = [line for line in lines]
    while lines and not lines[-1].strip():
        lines.pop()
    if not lines:
        return ""
    if len(lines) == 1 and len(lines[0]) < 90:
        return f"{indent}/** {lines[0]} */\n"
    body = "".join(f"{indent} *{(' ' + line) if line else ''}\n" for line in lines)
    return f"{indent}/**\n{body}{indent} */\n"
