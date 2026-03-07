import re

def format_month_schedule(json_data, month: int):
    month_schedule = {
        "odd_week": [],
        "even_week": []
    }
    
    for (day_number, day) in enumerate(json_data):
        day_schedule = []
        for lesson in day:
            time_slot_schedule = {}
            
            for description in lesson["Subgroup"]:
                subgroup = "_"
                
                if description["SUBGROUP"] != None:
                    subgroup = description["SUBGROUP"]
                time_slot_schedule[subgroup] = {}
                    
                time_slot_schedule[subgroup]["subject"] = description["DISCIPLINE"]
                time_slot_schedule[subgroup]["lesson_type"] = description["TYPE_LESSON"]
                time_slot_schedule[subgroup]["teachers"] = description["TEACHER"]
                
                match_result = re.findall(r"(\d+)", description["CLASSROOM"])
                if match_result != None:  
                    # Обрабатываем случай с дистантом
                    if len(match_result) == 0:
                        time_slot_schedule[subgroup]["room"] = None
                        time_slot_schedule[subgroup]["building"] = None
                    else:
                        time_slot_schedule[subgroup]["room"] = match_result[0]
                        time_slot_schedule[subgroup]["building"] = match_result[1]

            # Удаление дубликатов
            items = list(time_slot_schedule.items())
            new_items = {}
            i, j = 0, 1
            while i < len(items):
                new_key = items[i][0]
                while j < len(items):
                    if (items[i][1] == items[j][1]):
                        new_key += ", " + items[j][0]
                        items.pop(j)
                    else:
                        j += 1
                new_items[new_key] = items[i][1]
                i += 1
                j = i + 1
                    
            day_schedule.append(new_items)
            
        if day_number // 7 == 0:    
            month_schedule["even_week"].append(day_schedule)
        else:
            month_schedule["odd_week"].append(day_schedule)
    return month_schedule