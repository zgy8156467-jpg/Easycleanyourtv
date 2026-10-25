#!/usr/bin/env python3
"""Build the device compatibility sheet from the upstream README table.

The upstream table (cleanyourtv/README.md, "The full list") is 158 TVs that
have actually been on someone's bench. It tops out at Android 5.1.1 for the
oldest unit, so anything below 6.0 in this sheet is an extrapolation, not a
tested result -- the sheet says so in the header.

Writes docs/机型兼容清单.xlsx and docs/机型兼容清单.md.

    python3 docs/make-compat-sheet.py
"""
import re
import sys
from collections import Counter
from pathlib import Path

from openpyxl import Workbook
from openpyxl.styles import Alignment, Font, PatternFill
from openpyxl.utils import get_column_letter

HERE = Path(__file__).resolve().parent
README = HERE.parent.parent / "cleanyourtv" / "README.md"
OUT_XLSX = HERE / "机型兼容清单.xlsx"
OUT_MD = HERE / "机型兼容清单.md"

COLS = ["品牌", "型号", "代号", "系统版本", "代际", "建议渠道", "备注"]

# First token is the selling brand for almost every row. These few start with a
# model name instead; the brand only appears inside the parentheses.
BRAND_FIX = {
    "AiPro2K": "Changhong",
    "AIWA": "AIWA",
    "CHiQ": "Changhong",
}


# Upstream rows that our own bench run has corrected. Matched on the code-name
# cell, so a README update that renames the model still lands here.
# Columns are 系统版本 / 代际 / 建议渠道 / 备注 (品牌, 型号, 代号 stay as upstream says).
PATCHES = {
    "MiTV-MFFU0": {
        3: "Android 14 (HyperOS 3, CN build)",
        4: "新",
        5: "idun 定制版",
        6: "实测固件 OS4.0.11.0.UFFMATV，4 GB RAM；国内版；HOME 只认 com.thunderstone.idun.dev "
           "包名；应用内更新装不了，只能 U 盘 + 电视管家或 adb install；显示页、选图、直播电视已单独适配",
    },
}


def parse_table():
    lines = README.read_text(encoding="utf-8").splitlines()
    start = next(i for i, l in enumerate(lines) if l.startswith("| TV | OS |"))
    rows = []
    for line in lines[start + 2:]:
        if not line.startswith("|"):
            break
        cells = [c.strip() for c in line.strip("|").split("|")]
        if len(cells) == 2:
            rows.append(cells)
    return rows


def android_version(os_text):
    """'Fire OS 8.1.8.5 / Android 11' -> (8, 1); 'Google TV 10' -> (10, 0)."""
    m = re.search(r"Android\s+(\d+)(?:\.(\d+))?", os_text)
    if m:
        return int(m.group(1)), int(m.group(2) or 0)
    m = re.search(r"(?:Google TV|Android TV)\s+(\d+)(?:\.(\d+))?", os_text)
    if m:
        return int(m.group(1)), int(m.group(2) or 0)
    return None


def split_name(tv):
    """Model name, the parenthesised code names and the text after them.

    Region notes usually sit after the closing paren: '... ), US'.
    """
    m = re.match(r"^(.*?)\s*\((.*)\)\s*(?:,\s*(.*))?$", tv)
    if not m:
        return tv, "", ""
    return m.group(1).strip(), m.group(2).strip(), (m.group(3) or "").strip()


def brand_of(tv, model, inner):
    first = re.split(r"[\s/,]", tv, 1)[0]
    if first in BRAND_FIX:
        return BRAND_FIX[first]
    if not re.search(r"\d", first):
        return first
    # Model code as the first token: the brand is usually named in the parens.
    for known in ("Changhong", "Skyworth", "TCL", "Hisense", "Sony", "Philips",
                  "Panasonic", "Xiaomi", "Amazon", "Google", "Amlogic", "MediaTek"):
        if known.lower() in inner.lower():
            return known
    return first


def _fmt_v(v):
    if not v:
        return "未知"
    return "Android %d" % v[0] if v[1] == 0 else "Android %d.%d" % v


def generation(v):
    if v is None:
        return "未知"
    major = v[0]
    if major >= 12:
        return "新"
    if major >= 9:
        return "中"
    if major >= 6:
        return "老"
    return "古董"


def channel(v):
    if v is None:
        return "未定"
    # Android 6.0 is the modern build's floor; anything below needs the legacy APK.
    return "现代版" if (v[0], v[1]) >= (6, 0) else "老设备版"


def build_rows():
    out = []
    for tv, os_text in parse_table():
        model, inner, extra = split_name(tv)
        v = android_version(os_text)
        note_bits = []
        if extra:
            note_bits.append(extra)
        if "CN build" in os_text:
            note_bits.append("国内版")
        if "not Android TV" in os_text:
            note_bits.append("非 Android TV")
        out.append([
            brand_of(tv, model, inner),
            model,
            inner,
            os_text,
            generation(v),
            channel(v),
            "；".join(note_bits),
        ])
    for row in out:
        for code, patch in PATCHES.items():
            if code in row[2]:
                for col, value in patch.items():
                    row[col] = value
    return out


def write_xlsx(rows):
    wb = Workbook()
    ws = wb.active
    ws.title = "机型清单"
    head_fill = PatternFill("solid", fgColor="F2E3DA")
    head_font = Font(bold=True)
    ws.append([f"共 {len(rows)} 台实测机型。数据来自上游 cleanyourtv/README.md；"
               "4.0–5.1 无样本，老设备版的兼容性靠群友实测，不代表已验证。"])
    ws.merge_cells(start_row=1, start_column=1, end_row=1, end_column=len(COLS))
    ws.cell(1, 1).alignment = Alignment(wrap_text=True)
    ws.append(COLS)
    for c in ws[2]:
        c.fill = head_fill
        c.font = head_font
    for r in rows:
        ws.append(r)
    widths = [14, 46, 50, 26, 8, 12, 34]
    for i, w in enumerate(widths, 1):
        ws.column_dimensions[get_column_letter(i)].width = w
    ws.freeze_panes = "A3"
    ws.auto_filter.ref = "A2:%s%d" % (get_column_letter(len(COLS)), len(rows) + 2)

    # Stats sheet: the whole point is knowing how much of the list needs the legacy build.
    st = wb.create_sheet("统计")
    dist = Counter(android_version(r[3]) for r in rows)
    st.append(["系统版本", "机型数"])
    for v, n in sorted(dist.items(), key=lambda kv: (kv[0] is None, kv[0] or (0, 0))):
        st.append([_fmt_v(v), n])
    st.append([])
    st.append(["建议渠道", "机型数"])
    for k, n in Counter(r[5] for r in rows).items():
        st.append([k, n])
    st.append([])
    st.append(["代际", "机型数"])
    for k, n in Counter(r[4] for r in rows).items():
        st.append([k, n])
    st.column_dimensions["A"].width = 20
    st.column_dimensions["B"].width = 12

    wb.save(OUT_XLSX)


def write_md(rows):
    lines = [
        "# 机型兼容清单",
        "",
        "共 %d 台实测机型。数据来自上游 `cleanyourtv/README.md` 的 “The full list”。"
        "上游最低样本是 Android 5.1.1，**4.0–5.1 没有实测样本**，老设备版在这段区间靠群友装完后的"
        "反馈和崩溃上报验证，不代表已验证。" % len(rows),
        "",
        "| " + " | ".join(COLS) + " |",
        "|" + "|".join(["---"] * len(COLS)) + "|",
    ]
    for r in rows:
        lines.append("| " + " | ".join(x.replace("|", "/") for x in r) + " |")
    lines += [
        "",
        "## 统计",
        "",
        "系统分布：" + "，".join(
            "%s×%d" % (_fmt_v(v), n)
            for v, n in sorted(Counter(android_version(r[3]) for r in rows).items(),
                               key=lambda kv: (kv[0] is None, kv[0] or (0, 0)))),
        "",
        "建议渠道：" + "，".join("%s×%d" % kv for kv in Counter(r[5] for r in rows).items()),
        "",
        "代际：" + "，".join("%s×%d" % kv for kv in Counter(r[4] for r in rows).items()),
        "",
    ]
    OUT_MD.write_text("\n".join(lines), encoding="utf-8")


def main():
    if not README.exists():
        sys.exit("找不到上游 README：%s" % README)
    rows = build_rows()
    write_xlsx(rows)
    write_md(rows)
    print("写入 %s（%d 行）" % (OUT_XLSX.name, len(rows)))
    print("写入 %s" % OUT_MD.name)
    print("渠道分布：", dict(Counter(r[5] for r in rows)))
    print("系统分布：", dict(Counter(r[3] for r in rows)))


if __name__ == "__main__":
    main()
