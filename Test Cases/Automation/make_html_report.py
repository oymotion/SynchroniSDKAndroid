#!/usr/bin/env python
"""把 Demo 自动化「导出报告」产出的 report_<Model>_<时间>.json 转成 HTML 报告。

用法（在任意目录）：
    python "Test Cases/Automation/make_html_report.py" reports/report_Cerelax-Pro_20261125_101500.json
    python "Test Cases/Automation/make_html_report.py" reports/*.json --out reports

规则：
- 自动 / 半自动 / 人工三类用例都进报告，并在「自动化」列标注；未执行（含待人工确认）记「待人工」。
- 设备能力不适用（spec 不支持）的用例不进报告。
- 通过率 = 通过 / 已执行用例（通过 + 失败 + 跳过），待人工不计入分母。
- 失败用例若在 known_issues.json 里登记了问题编号，报告显示「失败 · 问题 NNNN」并填入缺陷清单；
  「型号/用例ID」优先于全局「用例ID」，用于同一用例不同机型开不同单。
- 每份 JSON 输出一份 report_<Model>.html，文件名不含时间戳，覆盖上一版。
"""
import argparse
import html
import json
import re
import sys
from pathlib import Path

VERDICT = {
    "PASS": ("pass", "通过"),
    "FAIL": ("fail", "失败"),
    "SKIP": ("skip", "跳过"),
    "PENDING": ("pending", "待人工"),
}
MODE_TEXT = {"AUTO": "自动", "SEMI_AUTO": "半自动", "MANUAL": "人工"}

TEMPLATE = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Synchroni Android SDK 测试报告 - {model}</title>
<style>
* {{ box-sizing: border-box; }}
body {{ font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif; margin: 0; background: #f5f6f8; color: #1f2937; line-height: 1.6; }}
header {{ background: linear-gradient(135deg, #1e3a8a, #3b82f6); color: #fff; padding: 32px 40px; }}
header h1 {{ margin: 0 0 8px; font-size: 24px; }}
header .meta {{ opacity: 0.9; font-size: 14px; }}
.wrap {{ max-width: 1100px; margin: 0 auto; padding: 24px 32px 48px; }}
section {{ margin-bottom: 32px; }}
h2 {{ font-size: 18px; border-left: 4px solid #3b82f6; padding-left: 12px; margin: 0 0 16px; }}
h3 {{ font-size: 15px; margin: 24px 0 8px; color: #111827; }}
.summary {{ display: flex; gap: 16px; flex-wrap: wrap; }}
.card {{ flex: 1; min-width: 130px; background: #fff; border-radius: 12px; padding: 20px; text-align: center; box-shadow: 0 1px 3px rgba(0,0,0,0.08); }}
.card .num {{ font-size: 32px; font-weight: 700; }}
.card .lbl {{ font-size: 13px; color: #6b7280; margin-top: 4px; }}
.card.pass .num {{ color: #16a34a; }}
.card.fail .num {{ color: #dc2626; }}
.card.skip .num {{ color: #6b7280; }}
.card.pending .num {{ color: #b45309; }}
.card.rate .num {{ color: #3b82f6; }}
table {{ width: 100%; border-collapse: collapse; background: #fff; border-radius: 10px; overflow: hidden; box-shadow: 0 1px 3px rgba(0,0,0,0.08); font-size: 13px; }}
th, td {{ padding: 9px 12px; text-align: left; border-bottom: 1px solid #eef0f3; vertical-align: top; }}
th {{ background: #f9fafb; font-weight: 600; color: #374151; white-space: nowrap; }}
tr:last-child td {{ border-bottom: none; }}
.mono {{ font-family: ui-monospace, "SF Mono", Consolas, monospace; font-size: 12px; }}
.badge {{ display: inline-block; padding: 2px 10px; border-radius: 999px; font-size: 12px; font-weight: 600; white-space: nowrap; }}
.badge.pass {{ background: #dcfce7; color: #15803d; }}
.badge.fail {{ background: #fee2e2; color: #b91c1c; }}
.badge.skip {{ background: #e5e7eb; color: #4b5563; }}
.badge.pending {{ background: #fef3c7; color: #b45309; }}
.prio {{ display: inline-block; padding: 1px 8px; border-radius: 4px; font-size: 11px; font-weight: 700; }}
.prio.p0 {{ background: #fee2e2; color: #b91c1c; }}
.prio.p1 {{ background: #fef3c7; color: #b45309; }}
.prio.p2 {{ background: #dbeafe; color: #1d4ed8; }}
.bug {{ color: #b91c1c; font-weight: 700; }}
.chcount {{ font-size: 12px; color: #9ca3af; font-weight: normal; }}
.detail {{ white-space: pre-wrap; word-break: break-word; max-width: 420px; }}
.none {{ background: #fff; padding: 16px; border-radius: 10px; color: #6b7280; }}
</style>
</head>
<body>
<header>
  <h1>Synchroni Android SDK 测试报告</h1>
  <div class="meta">平台：Android ｜ 设备：{model}（{identity}） ｜ MAC：{mac} ｜ 生成时间：{generated}</div>
</header>
<div class="wrap">
<section class="summary">
  <div class="card"><div class="num">{total}</div><div class="lbl">总用例</div></div>
  <div class="card pass"><div class="num">{passed}</div><div class="lbl">通过</div></div>
  <div class="card fail"><div class="num">{failed}</div><div class="lbl">失败</div></div>
  <div class="card skip"><div class="num">{skipped}</div><div class="lbl">跳过</div></div>
  <div class="card pending"><div class="num">{pending}</div><div class="lbl">待人工</div></div>
  <div class="card rate"><div class="num">{rate}</div><div class="lbl">通过率（已执行）</div></div>
</section>
{defects}
<section class="chapters"><h2>用例明细</h2>
{chapters}
</section>
</div>
</body>
</html>
"""


def load(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def chapter_name(chapter, prefixes):
    name = re.sub(r"^\d+_", "", chapter or "未分类")
    prefix = " / ".join(f"{p}-*" for p in sorted(prefixes))
    return f"{name}用例（{prefix}）"


def esc(text):
    return html.escape(str(text), quote=True)


def bug_of(issues, case_id, model=""):
    """用例 → 问题编号。机型前缀（"型号/用例ID"）优先，其次全局用例 ID。

    支持 "ID": "7749" 或 "ID": {"bug": "7749", "note": "..."}。
    """
    entry = None
    if model and f"{model}/{case_id}" in issues:
        entry = issues[f"{model}/{case_id}"]
    elif case_id in issues:
        entry = issues[case_id]
    if isinstance(entry, dict):
        return str(entry.get("bug") or "").strip()
    if isinstance(entry, str):
        return entry.strip()
    return ""


def build(data, source, issues=None):
    issues = issues or {}
    model = data.get("deviceModel") or ""
    case_results = [
        r for r in data.get("results", [])
        if r.get("mode") in MODE_TEXT and r.get("verdict") in VERDICT
    ]
    # 能力不适用（spec 不支持）的用例不进报告。
    case_results = [
        r for r in case_results
        if not (r.get("detail") or "").startswith("当前设备 spec 不支持")
    ]

    passed = sum(1 for r in case_results if r["verdict"] == "PASS")
    failed = sum(1 for r in case_results if r["verdict"] == "FAIL")
    skipped = sum(1 for r in case_results if r["verdict"] == "SKIP")
    pending = sum(1 for r in case_results if r["verdict"] == "PENDING")
    total = len(case_results)
    executed = total - pending
    rate = f"{passed / executed * 100:.1f}%" if executed else "—"

    defects = ""
    failed_cases = sorted(
        (r for r in case_results if r["verdict"] == "FAIL"),
        key=lambda r: r["id"],
    )
    if failed_cases:
        rows = []
        for r in failed_cases:
            rows.append(
                "<tr>"
                f'<td class="mono">{esc(r["id"])}</td>'
                f'<td>{esc(r["title"])}</td>'
                f'<td>{esc(re.sub(r"^\d+_", "", r.get("chapter", "")))}</td>'
                f'<td><span class="prio {esc(r.get("priority", "p1").lower())}">{esc(r.get("priority", ""))}</span></td>'
                f'<td class="mono bug">{esc(bug_of(issues, r["id"], model) or "—")}</td>'
                f'<td class="detail mono">{esc(r.get("detail", ""))}</td>'
                "</tr>"
            )
        defects = (
            '<section class="defects"><h2>缺陷清单（失败用例）</h2>'
            "<table><thead><tr><th>用例 ID</th><th>用例名称</th><th>章节</th>"
            "<th>优先级</th><th>问题编号</th><th>失败详情</th></tr></thead><tbody>"
            + "".join(rows)
            + "</tbody></table></section>"
        )

    chapters = []
    grouped = {}
    for r in case_results:
        grouped.setdefault(r.get("chapter") or "未分类", []).append(r)
    for chapter in sorted(grouped):
        cases = sorted(grouped[chapter], key=lambda r: r["id"])
        prefixes = {r["id"].split("-")[0] for r in cases}
        rows = []
        for r in cases:
            css, text = VERDICT[r["verdict"]]
            suffix = ""
            if r["verdict"] == "FAIL":
                bug = bug_of(issues, r["id"], model)
                if bug:
                    suffix = f" · 问题 {esc(bug)}"
                elif r.get("detail"):
                    suffix = f" · {esc(r['detail'].strip().splitlines()[0][:80])}"
            elif r["verdict"] in ("SKIP", "PENDING") and r.get("detail"):
                suffix = f" · {esc(r['detail'].strip().splitlines()[0][:80])}"
            rows.append(
                "<tr>"
                f'<td class="mono">{esc(r["id"])}</td>'
                f'<td>{esc(r["title"])}</td>'
                f'<td><span class="prio {esc(r.get("priority", "p1").lower())}">{esc(r.get("priority", ""))}</span></td>'
                f'<td>{esc(MODE_TEXT.get(r.get("mode"), ""))}</td>'
                f'<td><span class="badge {css}">{text}{suffix}</span></td>'
                "</tr>"
            )
        chapters.append(
            f'<h3>{esc(chapter_name(chapter, prefixes))} <span class="chcount">{len(cases)} 用例</span></h3>'
            "<table><thead><tr><th>用例 ID</th><th>用例名称</th><th>优先级</th>"
            "<th>自动化</th><th>结果</th></tr></thead><tbody>"
            + "".join(rows)
            + "</tbody></table>"
        )
    if not chapters:
        chapters.append('<div class="none">该报告没有任何用例结果，请确认是否执行了「Run All Auto」。</div>')

    return TEMPLATE.format(
        model=esc(data.get("deviceModel") or "unknown"),
        identity=esc(data.get("deviceIdentity") or ""),
        mac=esc(data.get("deviceMac") or ""),
        generated=esc(data.get("generatedAt") or ""),
        total=total,
        passed=passed,
        failed=failed,
        skipped=skipped,
        pending=pending,
        rate=rate,
        defects=defects,
        chapters="\n".join(chapters),
    )


def main():
    parser = argparse.ArgumentParser(description="Android 自动化 JSON 报告 → HTML")
    parser.add_argument("reports", nargs="+", help="report_*.json 路径")
    parser.add_argument("--out", help="HTML 输出目录（默认与 JSON 同目录）")
    parser.add_argument(
        "--issues",
        default=str(Path(__file__).resolve().parent / "known_issues.json"),
        help="用例→问题编号映射（默认同目录 known_issues.json）",
    )
    args = parser.parse_args()
    issues = load(args.issues) if Path(args.issues).is_file() else {}

    for pattern in args.reports:
        for path_str in sorted(Path().glob(pattern)) if any(c in pattern for c in "*?") else [Path(pattern)]:
            path = Path(path_str)
            if not path.is_file():
                print(f"[跳过] 不存在: {path}", file=sys.stderr)
                continue
            data = load(path)
            html_text = build(data, path, issues)
            out_dir = Path(args.out) if args.out else path.parent
            out_dir.mkdir(parents=True, exist_ok=True)
            model = re.sub(r"[^A-Za-z0-9._-]", "_", data.get("deviceModel") or "unknown")
            out_path = out_dir / f"report_{model}.html"
            out_path.write_text(html_text, encoding="utf-8")
            counts = {label: 0 for label in MODE_TEXT.values()}
            for r in data.get("results", []):
                label = MODE_TEXT.get(r.get("mode"))
                if label:
                    counts[label] += 1
            print(f"{path.name} -> {out_path}（自动 {counts['自动']} / 半自动 {counts['半自动']} / 人工 {counts['人工']}）")


if __name__ == "__main__":
    main()
