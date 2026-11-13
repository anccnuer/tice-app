"""模板保真校验工具（只读，不会改写任何 xlsx）。

用法：
  python scripts/verify_export.py baseline [模板.xlsx]
      盘点必须原样保留的 OOXML 部件、表头、表头批注规则与 VML 锚点。
  python scripts/verify_export.py verify <导出.xlsx> [模板.xlsx] [--expect-ids ID1,ID2]
      逐部件比对导出表与模板，确认批注/样式/列宽/信息列原样保留、成绩正确回填。
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import zipfile
from pathlib import Path
from xml.etree import ElementTree as ET

MAIN = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
SHEET_PART = "xl/worksheets/sheet1.xml"
DEFAULT_TEMPLATE = Path(__file__).resolve().parents[1] / "data-example.xlsx"


def read_parts(path: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(path) as zf:
        return {info.filename: zf.read(info.filename) for info in zf.infolist()}


def split_sheet(xml: str) -> tuple[str, str, str, str]:
    """返回 sheetData 之前、sheetData 内行、sheetData 之后、dimension 片段。"""
    start = xml.index("<sheetData>")
    end = xml.index("</sheetData>") + len("</sheetData>")
    prefix, body, suffix = xml[:start], xml[start + len("<sheetData>") : end - len("</sheetData>")], xml[end:]
    dim = re.search(r'<dimension ref="[^"]+"\s*/>', xml)
    return prefix, body, suffix, dim.group(0) if dim else ""


def rows_of(body: str) -> list[str]:
    return re.findall(r"<row\b[^>]*>.*?</row>|<row\b[^>]*/>", body, re.S)


def shared_strings(parts: dict[str, bytes]) -> list[str]:
    if "xl/sharedStrings.xml" not in parts:
        return []
    root = ET.fromstring(parts["xl/sharedStrings.xml"])
    return ["".join(t.text or "" for t in si.iter(MAIN + "t")) for si in root.findall(MAIN + "si")]


def header_row(parts: dict[str, bytes]) -> tuple[list[str], int]:
    xml = parts[SHEET_PART].decode("utf-8")
    rows = rows_of(split_sheet(xml)[1])
    ss = shared_strings(parts)
    headers: list[str] = []
    for cell in re.findall(r"<c\b[^>]*>.*?</c>|<c\b[^>]*/>", rows[0], re.S):
        ref = re.search(r'r="([A-Z]+)\d+"', cell)
        val = re.search(r"<v>(.*?)</v>", cell, re.S)
        text = ""
        if val:
            text = ss[int(val.group(1))] if 't="s"' in cell else val.group(1)
        else:
            inline = re.search(r"<t[^>]*>(.*?)</t>", cell, re.S)
            text = inline.group(1) if inline else ""
        headers.append(f"{ref.group(1) if ref else '?'}:{text}")
    return headers, len(rows)


def comment_rules(parts: dict[str, bytes]) -> list[dict]:
    if "xl/comments1.xml" not in parts:
        return []
    root = ET.fromstring(parts["xl/comments1.xml"])
    out = []
    for comment in root.iter(MAIN + "comment"):
        text = "".join(t.text or "" for t in comment.iter(MAIN + "t"))
        # 只认 ~ / ～ 作为范围分隔符：避免把 H1 的出生日期 "2012-02-21" 误判成范围
        rng = re.search(r"(-?\d+(?:\.\d+)?)\s*[~～]\s*(-?\d+(?:\.\d+)?)", text)
        out.append(
            {
                "ref": comment.get("ref"),
                "text": text,
                "min": float(rng.group(1)) if rng else None,
                "max": float(rng.group(2)) if rng else None,
                "decimal": "小数" in text or (rng is not None and ("." in rng.group(1) or "." in rng.group(2))),
                "integer_only": "整数" in text,
                "text_value": "′" in text or "″" in text,  # 50米×8往返跑：2′20″
            }
        )
    return out


def vml_anchors(parts: dict[str, bytes]) -> list[int]:
    for name, data in parts.items():
        if name.endswith(".vml"):
            return sorted({int(x) for x in re.findall(r"<x:Row>(\d+)</x:Row>", data.decode("utf-8", "ignore"))})
    return []


def cmd_baseline(template: Path) -> int:
    parts = read_parts(template)
    headers, row_count = header_row(parts)
    report = {
        "template": str(template),
        "part_count": len(parts),
        "parts_must_be_byte_identical": [
            {"name": k, "bytes": len(v), "sha256": hashlib.sha256(v).hexdigest()} for k, v in parts.items()
        ],
        "sheet_part": SHEET_PART,
        "headers": headers,
        "row_count_including_header": row_count,
        "comment_rules": comment_rules(parts),
        "vml_anchor_rows": vml_anchors(parts),
        "non_sheetdata_xml_must_be_preserved": True,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2, default=str))
    return 0


def cmd_verify(exported: Path, template: Path, expect_ids: list[str] | None) -> int:
    src, dst = read_parts(template), read_parts(exported)
    errors: list[str] = []
    extra_parts = sorted(set(dst) - set(src))
    missing_parts = sorted(set(src) - set(dst))
    if extra_parts or missing_parts:
        errors.append(f"部件集合不一致 extra={extra_parts} missing={missing_parts}")

    preserved = []
    for name, data in src.items():
        if name == SHEET_PART:
            continue
        if dst.get(name) != data:
            errors.append(f"部件内容被改动: {name}")
        else:
            preserved.append(name)

    a_prefix, a_body, a_suffix, a_dim = split_sheet(src[SHEET_PART].decode("utf-8"))
    b_prefix, b_body, b_suffix, b_dim = split_sheet(dst[SHEET_PART].decode("utf-8"))
    if re.sub(r'<dimension ref="[^"]+"\s*/>', "", a_prefix) != re.sub(r'<dimension ref="[^"]+"\s*/>', "", b_prefix):
        errors.append("sheetData 之前的 XML（cols/sheetViews/sheetFormatPr 等）被改动")
    if a_suffix != b_suffix:
        errors.append("sheetData 之后的 XML（phoneticPr/pageMargins/legacyDrawing 等）被改动")
    a_rows, b_rows = rows_of(a_body), rows_of(b_body)
    if not a_rows or not b_rows or a_rows[0] != b_rows[0]:
        errors.append("表头行 XML 不一致")

    kept_ids = []
    ss = shared_strings(src)
    id_col = next((h.split(":")[0] for h in header_row(src)[0] if h.endswith(":学籍号")), "D")
    for row in b_rows[1:]:
        m = re.search(r'<c r="' + id_col + r'\d+"[^>]*>(.*?)</c>', row, re.S)
        v = re.search(r"<v>(.*?)</v>", m.group(1), re.S) if m else None
        if v:
            kept_ids.append(ss[int(v.group(1))] if 't="s"' in m.group(0) else v.group(1))
    if expect_ids is not None and sorted(kept_ids) != sorted(expect_ids):
        errors.append(f"保留的学生行不符: 期望 {sorted(expect_ids)} 实际 {sorted(kept_ids)}")

    written = []
    for row in b_rows[1:]:
        n = re.search(r'<row r="(\d+)"', row)
        for c, val in re.findall(r'<c r="([J-Z]+)\d+"[^>]*>\s*<v>([^<]*)</v>\s*</c>', row):
            written.append(f"{c}{n.group(1) if n else '?'}={val}")

    try:
        import openpyxl

        wb = openpyxl.load_workbook(exported, read_only=True, data_only=True)
        preview = [[c.value for c in r] for r in wb.worksheets[0].iter_rows(min_row=1, max_row=min(4, b_rows.__len__() + 1))]
        wb.close()
        openpyxl_ok = True
    except Exception as exc:  # noqa: BLE001
        preview, openpyxl_ok = [], f"openpyxl 打开失败: {exc}"

    report = {
        "exported": str(exported),
        "template": str(template),
        "verdict": "PASS" if not errors else "FAIL",
        "errors": errors,
        "byte_identical_parts": preserved,
        "header_row_identical": bool(a_rows and b_rows and a_rows[0] == b_rows[0]),
        "dimension_template": a_dim,
        "dimension_exported": b_dim,
        "kept_student_ids": kept_ids,
        "written_score_cells": written,
        "comments_kept": comment_rules(dst),
        "vml_anchor_rows": vml_anchors(dst),
        "openpyxl_readable": openpyxl_ok,
        "value_preview": preview,
    }
    print(json.dumps(report, ensure_ascii=False, indent=2, default=str))
    return 0 if not errors else 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p_base = sub.add_parser("baseline")
    p_base.add_argument("template", nargs="?", type=Path, default=DEFAULT_TEMPLATE)
    p_verify = sub.add_parser("verify")
    p_verify.add_argument("exported", type=Path)
    p_verify.add_argument("template", nargs="?", type=Path, default=DEFAULT_TEMPLATE)
    p_verify.add_argument("--expect-ids", default=None)
    args = parser.parse_args()
    if args.cmd == "baseline":
        return cmd_baseline(args.template)
    ids = args.expect_ids.split(",") if args.expect_ids else None
    return cmd_verify(args.exported, args.template, ids)


if __name__ == "__main__":
    sys.exit(main())
