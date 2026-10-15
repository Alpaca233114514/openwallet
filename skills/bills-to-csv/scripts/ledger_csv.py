#!/usr/bin/env python3
"""Write/check OpenWallet CSV after source extraction; Python standard library only."""

import argparse
import csv
import io
import json
import re
from datetime import datetime
from decimal import Decimal
from pathlib import Path


HEADERS = ("时间", "账户", "类型", "金额", "交易方", "分类", "流水号")
FORMULA_PREFIXES = "=+-@\t\r\n'"
MAX_ROWS = 10_000
MAX_BYTES = 2_000_000


def escape(value):
    return "'" + value if value and value[0] in FORMULA_PREFIXES else value


def unescape(value):
    if len(value) > 1 and value[0] == "'" and value[1] in FORMULA_PREFIXES:
        return value[1:]
    return value


def validate(records, accounts):
    if not isinstance(records, list) or not records:
        raise ValueError("需要非空的交易数组，不生成空白或示例账单")
    if len(records) > MAX_ROWS:
        raise ValueError("每个文件最多 10000 条，请按完整记录拆分")
    result = []
    seen_ids = set()
    totals = {"收入": Decimal("0.00"), "支出": Decimal("0.00")}
    for index, record in enumerate(records, 1):
        try:
            if not isinstance(record, dict) or set(record) != set(HEADERS):
                raise ValueError("每项必须且只能包含七个标准中文键")
            if any(not isinstance(record[key], str) for key in HEADERS):
                raise ValueError("所有值必须是字符串，金额和流水号不可使用浮点数")
            row = [record[key].strip() for key in HEADERS]
            time, account, kind, amount, merchant, category, external_id = row
            if not re.fullmatch(r"[0-9]{4}-[0-9]{2}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2}", time):
                raise ValueError("时间必须为 yyyy-MM-dd HH:mm:ss")
            datetime.strptime(time, "%Y-%m-%d %H:%M:%S")
            if not account or (accounts and account not in accounts):
                raise ValueError("账户为空或不在指定的目标账户名单中")
            if kind not in totals:
                raise ValueError("类型只能是收入或支出")
            if not re.fullmatch(r"[0-9]{1,10}(?:\.[0-9]{1,2})?", amount):
                raise ValueError("金额需为最多十位整数、两位小数的正数字符串")
            number = Decimal(amount)
            if number <= 0:
                raise ValueError("金额必须大于零")
            if not merchant:
                raise ValueError("交易方不能为空")
            if external_id:
                key = (account, external_id)
                if key in seen_ids:
                    raise ValueError("同一账户流水号重复，请先核对重复或冲突")
                seen_ids.add(key)
            row[3] = format(number, ".2f")
            row[5] = category or "其他"
            result.append(dict(zip(HEADERS, row)))
            totals[kind] += number
        except ValueError as exc:
            raise ValueError(f"第 {index} 条记录：{exc}") from exc
    summary = {"条数": len(result), **{key: format(value, ".2f") for key, value in totals.items()}}
    summary["账户名单已核对"] = bool(accounts)
    return result, summary


def read_csv(path):
    data = path.read_bytes()
    if len(data) > MAX_BYTES:
        raise ValueError("CSV 超过 2000000 字节，请拆分")
    stream = io.StringIO(data.decode("utf-8-sig"), newline="")
    reader = csv.reader(stream, strict=True)
    if next(reader, None) != list(HEADERS):
        raise ValueError("表头或列顺序不匹配")
    records = []
    for index, row in enumerate(reader, 2):
        if len(row) != len(HEADERS):
            raise ValueError(f"第 {index} 条 CSV 记录列数不是 7")
        records.append(dict(zip(HEADERS, map(unescape, row))))
    return records


def render(records):
    stream = io.StringIO(newline="")
    writer = csv.writer(stream, lineterminator="\r\n", quoting=csv.QUOTE_ALL)
    writer.writerow(HEADERS)
    writer.writerows([escape(record[key]) for key in HEADERS] for record in records)
    data = stream.getvalue().encode("utf-8-sig")
    if len(data) > MAX_BYTES:
        raise ValueError("CSV 超过 2000000 字节，请按完整记录拆分")
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    write = commands.add_parser("write", help="从标准七字段 JSON 数组写入 CSV")
    write.add_argument("source", type=Path)
    write.add_argument("output", type=Path)
    check = commands.add_parser("check", help="回读并校验标准 CSV")
    check.add_argument("source", type=Path)
    for command in (write, check):
        command.add_argument("--account", action="append", default=[], help="目标账户名，可重复")
    args = parser.parse_args()
    try:
        if args.command == "write":
            records = json.loads(args.source.read_text(encoding="utf-8-sig"))
        else:
            records = read_csv(args.source)
        records, summary = validate(records, set(args.account))
        if args.command == "write":
            data = render(records)
            with args.output.open("xb") as output:
                output.write(data)
            restored, restored_summary = validate(read_csv(args.output), set(args.account))
            if restored != records or restored_summary != summary:
                raise ValueError("回读不一致，请检查输出文件")
        print(json.dumps(summary, ensure_ascii=False))
    except (ValueError, OSError, csv.Error) as exc:
        parser.exit(1, f"错误：{exc}\n")


if __name__ == "__main__":
    main()
