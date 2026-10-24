#!/usr/bin/env python3
"""生成《安装与恢复教程》docx。改完 安装与恢复教程.md 后跑一次：python3 docs/make-tutorial-docx.py"""
import os
from docx import Document
from docx.shared import Pt, Cm, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.oxml.ns import qn
from docx.oxml import OxmlElement

EAST = "微软雅黑"
INK = (0x23, 0x20, 0x1C)


def style_run(run, size=11, bold=False, east=EAST, ascii_font="Calibri", color=INK):
    run.font.size = Pt(size)
    run.font.bold = bold
    run.font.name = ascii_font
    run._element.rPr.rFonts.set(qn("w:eastAsia"), east)
    run.font.color.rgb = RGBColor(*color)


def para(doc, text="", size=11, bold=False, space_before=0, space_after=6,
         indent=0, color=INK):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(space_before)
    p.paragraph_format.space_after = Pt(space_after)
    if indent:
        p.paragraph_format.left_indent = Cm(indent)
    if text:
        style_run(p.add_run(text), size, bold, color=color)
    return p


def h1(doc, text):
    return para(doc, text, size=15, bold=True, space_before=16, space_after=8)


def bullet(doc, text):
    p = doc.add_paragraph(style="List Bullet")
    p.paragraph_format.space_after = Pt(4)
    style_run(p.add_run(text), 11)


def shade_paragraph(p, fill):
    pPr = p._p.get_or_add_pPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:fill"), fill)
    pPr.append(shd)


def code(doc, lines):
    for line in lines:
        p = para(doc, indent=0.6, space_after=0)
        p.paragraph_format.space_before = Pt(0)
        style_run(p.add_run(line), 10, east="Consolas", ascii_font="Consolas")
        shade_paragraph(p, "F2F2F2")
    para(doc, "", space_after=4)


def note_box(doc, lines):
    table = doc.add_table(rows=1, cols=1)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.autofit = True
    cell = table.cell(0, 0)
    tcPr = cell._tc.get_or_add_tcPr()
    shd = OxmlElement("w:shd")
    shd.set(qn("w:val"), "clear")
    shd.set(qn("w:fill"), "FDF3EE")
    tcPr.append(shd)
    cell.paragraphs[0].text = ""
    for i, line in enumerate(lines):
        p = cell.paragraphs[0] if i == 0 else cell.add_paragraph()
        p.paragraph_format.space_after = Pt(2)
        style_run(p.add_run(line), 10.5, bold=(i == 0))


def main():
    doc = Document()
    sec = doc.sections[0]
    sec.page_width, sec.page_height = Cm(21), Cm(29.7)
    sec.top_margin = sec.bottom_margin = Cm(2.2)
    sec.left_margin = sec.right_margin = Cm(2.4)

    normal = doc.styles["Normal"]
    normal.font.name = "Calibri"
    normal.font.size = Pt(11)
    normal.element.rPr.rFonts.set(qn("w:eastAsia"), EAST)

    para(doc, "电视桌面 2.0.2 安装教程", size=20, bold=True, space_after=4)
    para(doc, "适用于小米、创维等品牌的安卓电视（Android 6.0 及以上）。装它不改系统、不删原厂应用，"
              "原厂桌面还在，只是被盖住；不想用了随时恢复。安卓 4.0–5.1 的老电视装「老设备版」，"
              "见第七节。", size=10.5, color=(0x55, 0x50, 0x4B))

    note_box(doc, [
        "发群里的简版",
        "电视桌面，U 盘装，不用 root。小米、创维等安卓电视都能用。",
        "① 装 apk ② 设置里开无障碍「Home 键回顶」和使用情况访问「电视桌面」③ 完事",
        "想恢复：设置里关掉这两个权限，再卸载。详细步骤和恢复脚本在这份文档里。",
    ])
    para(doc, "", space_after=2)

    h1(doc, "一、准备")
    bullet(doc, "U 盘一个，FAT32 格式，把 tvhome-zh-2.0.2.apk 拷到根目录。老电视用 tvhome-zh-2.0.2-legacy.apk。")
    bullet(doc, "电视能上网。热播封面和片段需要联网，第一次打开会慢一点。")

    h1(doc, "二、安装")
    bullet(doc, "U 盘插到电视 USB 口。")
    bullet(doc, "电视上打开「应用 → 媒体中心」（有的固件叫「高清播放器」）。")
    bullet(doc, "找到 apk，按确定安装。")
    bullet(doc, "提示未知来源时：设置 → 账号与安全 → 允许安装未知来源应用。")

    h1(doc, "三、开两个权限（关键）")
    para(doc, "不装这两个，桌面能打开，但按 Home 还会回原厂桌面。")
    para(doc, "1. 无障碍", bold=True, space_before=6, space_after=2)
    para(doc, "设置 → 无障碍（有的固件叫辅助功能）→ 找到「Home 键回顶」→ 打开。")
    para(doc, "2. 使用情况访问", bold=True, space_before=6, space_after=2)
    para(doc, "设置 → 使用情况访问 → 找到「电视桌面」→ 允许。"
              "有的固件在：设置 → 应用 → 特殊权限 → 使用情况访问。")
    para(doc, "这两个页面藏得比较深，找不到就用电脑开，见第五节。")

    h1(doc, "四、怎么用")
    bullet(doc, "打开「电视桌面」。之后按 Home 就回这个桌面；已经在桌面时按 Home，回到第一屏。")
    bullet(doc, "海报下面有一排点，按上键进去，左右切换节目；停留 3 秒自动播该节目的片段。")
    bullet(doc, "选项/菜单键打开控制中心：网络、蓝牙、音量、亮度。")
    bullet(doc, "右上角三个按钮：桌面（壁纸和外观）、常用（挑常用应用）、控制中心。")
    bullet(doc, "每行图标数在「桌面」面板里改：自动 / 4 / 6 / 8，点一下图标小一档，"
                "只动下面的应用区，顶上时间和右上角按钮不变。")
    bullet(doc, "长按应用图标：加入常用、移出常用、卸载应用。")
    bullet(doc, "顶栏显示当地天气，IP 自动定位，30 分钟刷新一次。天气动态壁纸在「桌面」面板里开。")
    bullet(doc, "控制中心 → 输入源：可以直接切 HDMI1/2/3、TV、AV（接游戏机、机顶盒用）。")
    bullet(doc, "开机时会先闪一下原厂桌面，几秒内自动盖回来，正常现象。")
    bullet(doc, "以后有新版本，电视启动时会提示更新；控制中心里有「发送反馈」，"
                "遇到问题可以直接报上来。")

    h1(doc, "五、用电脑开权限（设置里找不到时）")
    bullet(doc, "电视：设置 → 账号与安全 → ADB 调试，打开。记下电视 IP（设置 → 关于 → 网络信息）。")
    bullet(doc, "电脑装好 adb（platform-tools），执行：")
    code(doc, [
        "adb connect 电视IP:5555",
        "adb shell appops set com.gathin.tvhome GET_USAGE_STATS allow",
        "adb shell settings put secure enabled_accessibility_services "
        "com.gathin.tvhome/com.gathin.tvhome.HomeKeyService",
        "adb shell settings put secure accessibility_enabled 1",
    ])

    h1(doc, "六、恢复原厂")
    para(doc, "桌面里一键（推荐）", bold=True, space_after=2)
    para(doc, "桌面顶部最右边「桌面」→ 面板最下面「卸载桌面（恢复原厂）」→ 系统弹卸载确认 → 确定。"
              "卸载后原厂桌面自动回来。")
    para(doc, "不连电脑（三步）", bold=True, space_before=8, space_after=2)
    bullet(doc, "设置 → 无障碍 → 关掉「Home 键回顶」")
    bullet(doc, "设置 → 使用情况访问 → 关掉「电视桌面」")
    bullet(doc, "设置 → 应用 → 应用管理 → 电视桌面 → 卸载")
    para(doc, "原厂桌面自动回来，遥控器一切照旧。")
    para(doc, "电脑一键恢复", bold=True, space_before=8, space_after=2)
    bullet(doc, "Windows：双击 restore-tvhome.bat，输入电视 IP。")
    bullet(doc, "Mac：终端执行 bash restore-tvhome.sh 192.168.1.23。")
    para(doc, "电视要先打开 ADB 调试。脚本会自动关掉接管、撤销权限、卸载桌面，跑完重启电视即可。")

    h1(doc, "七、老电视（安卓 4.x / 5.x）")
    para(doc, "安卓 6.0 以下的电视装 tvhome-zh-2.0.2-legacy.apk，界面和用法一样，区别在下面几点。")
    bullet(doc, "装哪个：安卓 6.0 及以上装普通版，4.0–5.1 装老设备版。"
                "装错了系统会拒绝安装，不会把电视弄坏。")
    bullet(doc, "权限只开无障碍：安卓 4.4 及以下没有「使用情况访问」，只开无障碍就够；"
                "5.x 两个都开，和其它机型一样。")
    bullet(doc, "海报和片段可能没有：老电视的网络加密协议旧，取不到图时会显示静态壁纸，桌面照常用。")
    bullet(doc, "更新走老设备线路：装好后自动检查老设备版的更新，不会覆盖成普通版。")
    bullet(doc, "反馈一样能发：控制中心里的「发送反馈」可用，群里报问题时说一声是老电视、什么型号。")
    bullet(doc, "恢复原厂：和第六节完全相同，卸载后原厂桌面自动回来。")

    h1(doc, "常见问题")
    bullet(doc, "开机先进了原厂桌面：正常，几秒内自动盖回来。")
    bullet(doc, "按 Home 还是回原厂：检查两个权限是不是都开了；重装过的话无障碍会掉，重新打开一次。")
    bullet(doc, "点「下载并安装」提示「此电视不支持」：1.7.1 及以前的小米电视会这样。"
                "用这个包手动装一次 2.0.2，之后再升级就正常了。")
    bullet(doc, "海报没有图：先确认电视联网；第一次打开要拉一遍数据，稍等一会儿。")
    bullet(doc, "遥控器失灵：长按电视电源键重启。还不行就跑一键恢复。")
    bullet(doc, "恢复后想再装：U 盘再装一次就行。")

    para(doc, "", space_after=2)
    para(doc, "— 电视桌面 2.0.2 · 2026-10", size=9, color=(0x88, 0x84, 0x80))

    out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "安装与恢复教程.docx")
    doc.save(out)
    print(out)


if __name__ == "__main__":
    main()
