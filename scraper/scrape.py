import requests
from bs4 import BeautifulSoup
import json
from datetime import datetime, timezone, timedelta

URL = "https://nagasaki-tours.com/gunkanjima-tour-calendar"
OUTPUT = "data.json"


def get_status(cell):
    classes = cell.get("class", [])
    if "status-ok" in classes:
        return "ok"
    if "status-limited" in classes:
        return "limited"
    if "status-cancel" in classes:
        return "cancel"
    return "unknown"


def scrape():
    headers = {
        "User-Agent": "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0.0.0 Mobile Safari/537.36"
    }
    resp = requests.get(URL, headers=headers, timeout=30)
    resp.raise_for_status()

    soup = BeautifulSoup(resp.text, "html.parser")
    table = soup.find("table")
    if not table:
        raise ValueError("カレンダーテーブルが見つかりません")

    # ヘッダーから事業者名を動的取得
    companies = []
    thead = table.find("thead")
    if thead:
        for row in thead.find_all("tr"):
            for th in row.find_all("th"):
                colspan = int(th.get("colspan", 1))
                text = th.get_text(strip=True)
                if colspan >= 2 and text and text not in {"AM", "PM", "日付", "日程"}:
                    companies.append(text)
            if companies:
                break

    if not companies:
        raise ValueError("事業者名を取得できませんでした")

    # tbody の行を解析
    availabilities = []
    tbody = table.find("tbody") or table

    for row in tbody.find_all("tr"):
        cells = row.find_all("td")
        if len(cells) < 2:
            continue
        raw_date = cells[0].get_text(strip=True)
        if not raw_date:
            continue

        for i, company in enumerate(companies):
            am_index = 1 + i * 2
            pm_index = 2 + i * 2

            if am_index < len(cells):
                availabilities.append({
                    "date": raw_date,
                    "company": company,
                    "period": "AM",
                    "status": get_status(cells[am_index])
                })
            if pm_index < len(cells):
                availabilities.append({
                    "date": raw_date,
                    "company": company,
                    "period": "PM",
                    "status": get_status(cells[pm_index])
                })

    jst = timezone(timedelta(hours=9))
    updated_at = datetime.now(jst).strftime("%Y-%m-%dT%H:%M:%S+09:00")

    return {
        "updated_at": updated_at,
        "companies": companies,
        "availabilities": availabilities
    }


if __name__ == "__main__":
    data = scrape()
    with open(OUTPUT, "w", encoding="utf-8") as f:
        json.dump(data, f, ensure_ascii=False, indent=2)
    print(f"完了: {len(data['companies'])}社, {len(data['availabilities'])}件")
