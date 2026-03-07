# Отчёт о проделанной работе
**Период:** 1 неделя второго сезона проекта  
**Дата составления:** 06.03.2026  

## Описание проделанной работы

За первую неделю работы над проектом в этом сезоне были выполнены следующие шаги:

* Разработан парсер расписания с сайта sibsutis.ru на языке python 
* Конфигурация была вынесена в dotenv файл для удобства и безопасности
* Было проведено обьяснение того, как работает прошлый Backend на python для Ромы

## Поэтапное описание

### ___1. Разработан парсер расписания___
В ходе недели разработал парсер который считывает основную информацию в расписании для разных групп включая поля:
- subject - название предмета 
- lesson_type - лекция/практика/лабораторная работа
- room + building - место проведения пары
- teachers - список преподавателей которые ведут пару
Расписание парсится с сайта и представляется пока что в виде текста в консоли:
<img width="710" height="385" alt="image" src="https://github.com/user-attachments/assets/05c86138-1dd2-402f-9103-b5cd1ce431ef" />

Основная логика состоит в том чтобы получать расписание на месяц, потом на неделю и уже затем на день
```python3
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
```

### ___2.Конфигурационный файл___
В конфигурационный dotenv файл были добавлены поля которые нужно ввести для работоспособности
```
SIBSUTIS_LOGIN="Логин от кабинета на sibsutis.ru"
SIBSUTIS_PASSWORD="Пароль от кабинета на sibsutis.ru"
```
Вынесение в отдельный файл обеспечит безопасность и легкое изменение данных для работы

