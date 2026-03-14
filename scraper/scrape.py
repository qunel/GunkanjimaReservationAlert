"""
各ツアー会社の空き情報をスクレイピングして data.json に保存する。

会社一覧:
  - 軍艦島コンシェルジュ  GET ?c=reserve-1&YYMM=YYYY-MM
  - 高島海上交通          POST yearmonth=YYYYMM
  - やまさ海運            GET ?ymd=YYYYMMDD&crs=10
  - シーマン商会          POST yearmonth=YYYYMM (course=1)
  - 第七ゑびす丸          GET API /api/ja/reservations/date_selector (per date)
"""

import requests
from bs4 import BeautifulSoup
import json
import re
from datetime import datetime, timezone, timedelta, date
from concurrent.futures import ThreadPoolExecutor, as_completed

HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
        "Chrome/120.0.0.0 Mobile Safari/537.36"
    )
}

COMPANIES = ["軍艦島コンシェルジュ", "高島海上交通", "やまさ海運", "シーマン商会", "第七ゑびす丸"]
OUTPUT = "data.json"
MONTHS_AHEAD = 2   # 当月 + 翌2ヶ月
EBISU_DAYS   = 90  # 第七ゑびす丸は日単位API、90日分


# ──────────────────────────────────────────────────────
# ユーティリティ
# ──────────────────────────────────────────────────────

def next_months(n: int):
    """今月から n ヶ月分の (year, month) リストを返す"""
    today = date.today()
    result = []
    for i in range(n + 1):
        m = today.month + i
        y = today.year + (m - 1) // 12
        m = ((m - 1) % 12) + 1
        result.append((y, m))
    return result


def map_status(s: str) -> str:
    """各社共通のステータス文字列を ok / limited / cancel / unknown に変換"""
    s = s.strip()
    if s in ("○",):
        return "ok"
    if s in ("△",) or s.isdigit():
        return "limited"
    if s in ("×", "満", "済", "満席", "欠航", "/", "－", "−"):
        return "cancel"
    return "unknown"


def availability(entries: list[dict], company: str) -> list[dict]:
    """entries に会社名を付けて返す"""
    return [{"date": e["date"], "company": company, "period": e["period"], "status": e["status"]}
            for e in entries]


# ──────────────────────────────────────────────────────
# 軍艦島コンシェルジュ
# ──────────────────────────────────────────────────────

def _concierge_month(year: int, month: int) -> list[dict]:
    url = f"https://www.gunkanjima-concierge.com/cgi/web/?c=reserve-1&YYMM={year:04d}-{month:02d}"
    resp = requests.get(url, headers=HEADERS, timeout=30)
    soup = BeautifulSoup(resp.content, "html.parser")

    table = soup.find("table")
    if not table:
        return []

    results = []
    for td in table.find_all("td"):
        day_div = td.find("div", class_="day")
        if not day_div:
            continue
        day_match = re.search(r"(\d+)", day_div.get_text())
        if not day_match:
            continue
        day = int(day_match.group(1))
        date_str = f"{year:04d}-{month:02d}-{day:02d}"

        def_div = td.find("div", class_="def")
        if not def_div:
            continue

        for period, turn in [("AM", "1"), ("PM", "2")]:
            inp = def_div.find("input", attrs={"data-turn": turn})
            if inp:
                label = inp.find_next_sibling("label") or inp.parent
                tickets = label.find_all("div", class_="ticket") if label else []
                non_empty = [t for t in tickets if "_empty" not in t.get("class", [])]
                status = "ok" if non_empty else "limited"
            else:
                # disable div があれば満席
                status = "cancel"
            results.append({"date": date_str, "period": period, "status": status})

    return results


def scrape_concierge() -> list[dict]:
    results = []
    for y, m in next_months(MONTHS_AHEAD):
        try:
            results.extend(_concierge_month(y, m))
        except Exception as e:
            print(f"コンシェルジュ {y}/{m} error: {e}")
    return results


# ──────────────────────────────────────────────────────
# 高島海上交通 / シーマン商会（共通パーサー）
# ──────────────────────────────────────────────────────

def _parse_cruise_table(soup: BeautifulSoup, year: int, month: int) -> list[dict]:
    """
    テーブルセルテキスト形式: "14午前便:×午後便:×" を解析する。
    同じ日付で複数の状態が得られた場合は、より良い方（ok > limited > cancel）を採用。
    """
    priority = {"ok": 2, "limited": 1, "cancel": 0, "unknown": -1}
    best: dict[tuple, str] = {}  # (date, period) -> status

    for td in soup.find_all("td"):
        text = td.get_text(separator="", strip=True)
        day_match = re.match(r"^(\d{1,2})", text)
        if not day_match:
            continue
        day = int(day_match.group(1))
        if day < 1 or day > 31:
            continue
        date_str = f"{year:04d}-{month:02d}-{day:02d}"

        for label, period in [("午前便", "AM"), ("午後便", "PM")]:
            m_st = re.search(rf"{label}[:：]([○△×\d\/]+)", text)
            if m_st:
                s = map_status(m_st.group(1))
                key = (date_str, period)
                if priority.get(s, -1) > priority.get(best.get(key, "unknown"), -1):
                    best[key] = s

    return [{"date": k[0], "period": k[1], "status": v} for k, v in best.items()]


def _fetch_cruise(url: str, year: int, month: int, extra_data: dict = None) -> list[dict]:
    data = {"yearmonth": f"{year:04d}{month:02d}"}
    if extra_data:
        data.update(extra_data)
    resp = requests.post(url, headers=HEADERS, data=data, timeout=30)
    soup = BeautifulSoup(resp.content, "html.parser")
    return _parse_cruise_table(soup, year, month)


def scrape_takashima() -> list[dict]:
    url = "https://www.gunkanjima-cruise.jp/reserve_input.php"
    results = []
    for y, m in next_months(MONTHS_AHEAD):
        try:
            results.extend(_fetch_cruise(url, y, m))
        except Exception as e:
            print(f"高島 {y}/{m} error: {e}")
    return results


def scrape_seaman() -> list[dict]:
    url = "https://www.gunkanjima-tour-reserve.jp/reserve_input.php"
    results = []
    for y, m in next_months(MONTHS_AHEAD):
        try:
            results.extend(_fetch_cruise(url, y, m, {"course": "1"}))
        except Exception as e:
            print(f"シーマン {y}/{m} error: {e}")
    return results


# ──────────────────────────────────────────────────────
# やまさ海運
# ──────────────────────────────────────────────────────

def _yamasa_month(year: int, month: int) -> list[dict]:
    url = "https://order.gunkan-jima.net/yamasa/ja/Event/Calender"
    params = {"ymd": f"{year:04d}{month:02d}01", "crs": "10"}
    resp = requests.get(url, headers=HEADERS, params=params, timeout=30)
    soup = BeautifulSoup(resp.content, "html.parser")

    table = soup.find("table")
    if not table:
        return []

    results = []
    for td in table.find_all("td"):
        text = td.get_text(separator="", strip=True)
        # "609:00：△13:00：×" 形式
        m = re.match(r"^(\d{1,2})09:00[：:](.+?)13:00[：:](.+?)$", text)
        if not m:
            continue
        day = int(m.group(1))
        date_str = f"{year:04d}-{month:02d}-{day:02d}"
        results.append({"date": date_str, "period": "AM", "status": map_status(m.group(2))})
        results.append({"date": date_str, "period": "PM", "status": map_status(m.group(3))})

    return results


def scrape_yamasa() -> list[dict]:
    results = []
    for y, m in next_months(MONTHS_AHEAD):
        try:
            results.extend(_yamasa_month(y, m))
        except Exception as e:
            print(f"やまさ {y}/{m} error: {e}")
    return results


# ──────────────────────────────────────────────────────
# 第七ゑびす丸（日単位API）
# ──────────────────────────────────────────────────────

def _ebisu_date(target: date) -> list[dict]:
    url = "https://mikata.in/api/ja/reservations/date_selector"
    h = {**HEADERS,
         "Referer": "https://mikata.in/nagasaki-tours/reservations/new?plan_id=2720",
         "X-Requested-With": "XMLHttpRequest"}
    resp = requests.get(url, headers=h,
                        params={"plan_id": "2720", "visit_date": target.strftime("%Y-%m-%d")},
                        timeout=15)
    if resp.status_code != 200:
        return []

    soup = BeautifulSoup(resp.content, "html.parser")
    table = soup.find("table")
    if not table:
        return []

    date_str = target.strftime("%Y-%m-%d")
    results = []
    for row in table.find_all("tr"):
        cells = row.find_all("td")
        if not cells:
            continue
        time_text = cells[0].get_text(strip=True)
        period = "AM" if time_text == "10:00" else ("PM" if time_text == "15:00" else None)
        if not period:
            continue
        # ボタン「申し込む」があれば予約可能
        btn = row.find("button")
        if btn and "申し込む" in btn.get_text():
            status = "ok"
        else:
            img = row.find("img")
            cls = img.get("class", []) if img else []
            if "rsv-cal-status-few" in cls:
                status = "limited"
            else:
                status = "cancel"
        results.append({"date": date_str, "period": period, "status": status})

    return results


def scrape_ebisu() -> list[dict]:
    today = date.today()
    dates = [today + timedelta(days=i) for i in range(1, EBISU_DAYS + 1)]
    results = []
    with ThreadPoolExecutor(max_workers=10) as ex:
        futures = {ex.submit(_ebisu_date, d): d for d in dates}
        for fut in as_completed(futures):
            try:
                results.extend(fut.result())
            except Exception as e:
                print(f"ゑびす丸 {futures[fut]} error: {e}")
    return results


# ──────────────────────────────────────────────────────
# メイン
# ──────────────────────────────────────────────────────

def main():
    scrapers = {
        "軍艦島コンシェルジュ": scrape_concierge,
        "高島海上交通":         scrape_takashima,
        "やまさ海運":           scrape_yamasa,
        "シーマン商会":         scrape_seaman,
        "第七ゑびす丸":         scrape_ebisu,
    }

    all_avail = []
    with ThreadPoolExecutor(max_workers=5) as ex:
        futures = {ex.submit(fn): name for name, fn in scrapers.items()}
        for fut in as_completed(futures):
            name = futures[fut]
            try:
                entries = fut.result()
                all_avail.extend(availability(entries, name))
                print(f"{name}: {len(entries)}件")
            except Exception as e:
                print(f"{name} error: {e}")

    jst = timezone(timedelta(hours=9))
    data = {
        "updated_at": datetime.now(jst).strftime("%Y-%m-%dT%H:%M:%S+09:00"),
        "companies": COMPANIES,
        "availabilities": all_avail,
    }

    with open(OUTPUT, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)

    print(f"\n完了: {len(all_avail)}件 → {OUTPUT}")


if __name__ == "__main__":
    main()
