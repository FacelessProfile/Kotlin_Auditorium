from datetime import datetime
import json
import requests
from bs4 import BeautifulSoup
from helpers import format_month_schedule
from dotenv import load_dotenv
import re
import os
# Регулярное выражение для поиска всех объектов расписания
# Индексы 0-13 расписание уроков
# Индексы 14- и до конца (в зависимости от длины месяца) расписание экзаменов
date_data_regex = re.compile(r"(?:days\[\d+\]\s+=\s+['\"]({.*})['\"])", re.M)

cached_schedules = {}
last_caching_date = datetime.today()

urls = {
    "auth": "https://sibsutis.ru/auth/",
    "group_search": "https://sibsutis.ru/ajax/get_groups_soap.php",
    "schedule": "https://sibsutis.ru/students/schedule/"
}

session = requests.Session()


# Авторизация
def login(login: str, password: str):
    auth_response = session.post(
        url=urls["auth"],
        data={
            "AUTH_FORM": "Y",
            "TYPE": "AUTH",
            "USER_LOGIN": login,
            "USER_PASSWORD": password,
            "Login": "Войти"
        },
        params={
            "login": "yes"
        },
        headers={
            "Content-Type": "application/x-www-form-urlencoded"
        }
    )

    if auth_response.ok:
        return True
    else:
        return False


# Поиск группы по названию
def find_group(name: str):
    group_search_response = session.get(
        url=urls["group_search"],
        params={
            "search_group": name
        }
    )

    data = group_search_response.json()
    
    if "results" in data and data["results"] != None:
        # Выбрасывает слишком длинные вхождения, оставляем формат XXXX-0000
        groups = [g for g in data["results"] if len(g["text"]) < 10]
        return groups
    else:
        return []


def get_month_schedule(group, month: int):
    """Получаем расписание на месяц\n
    Принимает индексы 0-11"""
    
    # Проверка кеша
    today = datetime.today()
    if last_caching_date < today:
        cached_schedules = {}
        
    if group["name"] in cached_schedules.keys():
        return cached_schedules[group["name"]]
    
    schedule_response = session.get(
        url=urls["schedule"],
        params={
            "type": "student",
            "group": group["id"],
            "month": (month + 1)
        }
    )

    soup = BeautifulSoup(schedule_response.text, "html.parser")
    layout = soup.find("div", id="layout")
    if not layout or not layout.find("script"):
        print(f"ошибка: Данные расписания не найдены в HTML для группы {group['id']}.")
        return {"odd_week": [], "even_week": []}

    script_content = layout.find("script").text
    matches = date_data_regex.findall(script_content)
    if not matches:
        return {"odd_week": [], "even_week": []}
    json_data = []
    for m in matches[:14]:
        try:
            json_data.append(json.loads(m)["ScheduleCell"])
        except (KeyError, json.JSONDecodeError):
            continue
    return format_month_schedule(json_data, month)

#получение недели расписания и так далее по вложенной структуре
def get_week_schedule(group, month: int, week: int):
    month_schedule = get_month_schedule(group, month)
    week_schedule = month_schedule["even_week" if week % 2 == 0 else "odd_week"]
    
    return week_schedule

def get_day_schedule(group, month: int, day: int):
    now = datetime.now()
    week = datetime(year=now.year, month=month, day=day).isocalendar()[1]
    week_day = datetime(year=now.year, month=month, day=day).isocalendar()[2] - 1
    
    month_schedule = get_month_schedule(group, month)
    week_schedule = month_schedule["even_week" if week % 2 == 0 else "odd_week"]
    day_schedule = week_schedule[week_day]
    
    return day_schedule

def get_group_schedule(group):
    group_schedule = {
        "group_id": group["id"],
        "group_name": group["name"],
        "months": []
    }
    
    for month in range(12):
        data = get_month_schedule(group, month)
        group_schedule["months"].append(data)

    return group_schedule

if __name__ == "__main__" and load_dotenv() and login(os.getenv("SIBSUTIS_LOGIN"), os.getenv("SIBSUTIS_PASSWORD")):
    target_group_name = "ИКС-532"
    print(f"Поиск группы {target_group_name}...")
    found_groups = find_group(target_group_name)
    if not found_groups:
        print("Группа не найдена.")
    else:
        selected_group = {
            "id": found_groups[0]['id'],
            "name": found_groups[0]['text']
        }

        now = datetime.now()
        current_month = now.month - 1
        current_day = now.day
        
        try:
            print(f"Загрузка расписания для {selected_group['name']} на {now.strftime('%d.%m.%Y')}...")
            day_schedule = get_day_schedule(selected_group, current_month, current_day)
            print(f"\n--- Расписание на сегодня ({selected_group['name']}) ---")
            
            if not day_schedule:
                print("Занятий нет (или выходной).")
            else:
                for lesson_index, time_slot in enumerate(day_schedule, 1):
                    if not time_slot: #если в этот слот ничего нет
                        continue
                    
                    print(f"Пара №{lesson_index}:")
                    for subgroup_name, info in time_slot.items():
                        prefix = f" [{subgroup_name}]" if subgroup_name != "_" else ""
                        
                        subject = info.get('subject', 'Нет названия')
                        l_type = info.get('lesson_type', '—')
                        room = info.get('room', '—')
                        building = info.get('building', '—')
                        teachers = info.get('teachers', '—')
                        room_full = f"ауд. {room}" if room else "Дистант"
                        if building:
                            room_full += f" (корп. {building})"
                        print(f"  • {subject}{prefix}")
                        print(f"    Тип: {l_type} | Локация: {room_full}")
                        print(f"    Преподаватель: {''.join(teachers)}")
                    
                    print("-" * 80)
                    
        except Exception as e:
            print(f"Ошибка при получении расписания: {e}")
