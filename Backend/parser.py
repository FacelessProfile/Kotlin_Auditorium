import os
import re
import json
import logging
import psycopg2
import requests
from datetime import datetime
from bs4 import BeautifulSoup
from dotenv import load_dotenv

# Настройка логирования
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s'
)

load_dotenv()

# Регулярка для поиска JSON
DATE_DATA_REGEX=re.compile(r"days\[\d+\]\s*=\s*['\"]({.*?})['\"]",re.M)

class ScheduleParser:
    def __init__(self):
        self.session=requests.Session()
        self.db_params={
            "dbname": os.getenv("POSTGRES_DB","schedule_db"),
            "user": os.getenv("POSTGRES_USER"),
            "password": os.getenv("POSTGRES_PASSWORD"),
            "host": os.getenv("DB_HOST","db"),
            "port": os.getenv("POSTGRES_PORT","5432")
        }
        self.urls={
            "auth": "https://sibsutis.ru/auth/",
            "group_search": "https://sibsutis.ru/ajax/get_groups_soap.php",
            "schedule": "https://sibsutis.ru/students/schedule/"
        }

    def get_db_connection(self):
        return psycopg2.connect(**self.db_params)

    def login(self,login_val,password_val):
        auth_response=self.session.post(
            url=self.urls["auth"],
            data={
                "AUTH_FORM": "Y","TYPE": "AUTH",
                "USER_LOGIN": login_val,"USER_PASSWORD": password_val,
                "Login": "Войти"
            },
            params={"login": "yes"},
            headers={"Content-Type": "application/x-www-form-urlencoded"}
        )
        return auth_response.ok

    def get_or_create_id(self,cur,table,column,value):
        if not value: return None
        cur.execute(f"SELECT id FROM {table} WHERE {column}=%s",(value,))
        res=cur.fetchone()
        if res: return res[0]
        cur.execute(f"INSERT INTO {table} ({column}) VALUES (%s) RETURNING id",(value,))
        return cur.fetchone()[0]

    def update_group_schedule(self,group_name):
        conn=self.get_db_connection()
        cur=conn.cursor()
        
        try:
            #Group id search
            resp=self.session.get(self.urls["group_search"],params={"search_group": group_name})
            results=resp.json().get("results",[])
            if not results:
                logging.error(f"Группа {group_name} не найдена")
                return
            
            group_id=results[0]['id']
            group_text=results[0]['text']
            cur.execute("INSERT INTO groups (id,name) VALUES (%s,%s) ON CONFLICT (id) DO NOTHING",(group_id,group_text))

            #Sched request
            current_month=datetime.now().month
            logging.info(f"Запрашен месяц: {current_month} для группы {group_text}")
            
            resp=self.session.get(self.urls["schedule"],params={
                "type": "student",
                "group": group_id,
                "month": current_month 
            })
            
            soup=BeautifulSoup(resp.text,"html.parser")
            script_tag=soup.find("div",id="layout").find("script") if soup.find("div",id="layout") else None
            
            if not script_tag:
                logging.error("Битый блок данных проверь script")
                return

            matches=DATE_DATA_REGEX.findall(script_tag.text)
            logging.info(f"Найдено блоков: {len(matches)}")

            if len(matches) == 0:
                logging.warning("Данные не найдены")
                return

            # Чистим старые данные
            cur.execute("DELETE FROM schedules WHERE group_id=%s",(group_id,))

            count_inserted=0
            # Parse
            for day_idx,m in enumerate(matches[:14]):
                try:
                    # Создаем точку сохранения внутри транзакции для каждого дня
                    cur.execute("SAVEPOINT day_savepoint") 
                    
                    clean_json=m.replace("\\'","'")
                    data_json=json.loads(clean_json)
                    day_slots=data_json.get("ScheduleCell",[])
                    
                    for lesson_num_idx,slot in enumerate(day_slots):
                        if not slot or not isinstance(slot,dict) or "Subgroup" not in slot:
                            continue
                            
                        for description in slot["Subgroup"]:
                            subgroup_code=description.get("SUBGROUP")
                            if subgroup_code is None:
                                subgroup_code="_"
                                
                            subject_name=description.get("DISCIPLINE")
                            if not subject_name:
                                continue 

                            lesson_type=description.get("TYPE_LESSON","—")
                            t_raw=description.get("TEACHER")
                            if isinstance(t_raw,list):
                                t_name=",".join(filter(None,t_raw)) or "Не указан"
                            elif isinstance(t_raw,str):
                                t_name=t_raw
                            else:
                                t_name="Не указан"
                            classroom_raw=description.get("CLASSROOM","")
                            match_result=re.findall(r"(\d+)",classroom_raw)
                            
                            if not match_result:
                                room_info="Дистант"
                            else:
                                room=match_result[0]
                                if len(match_result) > 1:
                                    build=match_result[1]
                                    room_info=f"ауд. {room} (корп. {build})"
                                else:
                                    room_info=f"ауд. {room}"

                            subj_id=self.get_or_create_id(cur,"subjects","name",subject_name)
                            teacher_id=self.get_or_create_id(cur,"teachers","name",t_name)

                            cur.execute("""
                                INSERT INTO schedules 
                                (group_id,subject_id,teacher_id,lesson_num,day_idx,subgroup,lesson_type,room_info)
                                VALUES (%s,%s,%s,%s,%s,%s,%s,%s)
                            """,(group_id,subj_id,teacher_id,lesson_num_idx + 1,day_idx,
                                  subgroup_code,lesson_type,room_info))
                            count_inserted += 1
                    cur.execute("RELEASE SAVEPOINT day_savepoint")

                except Exception as e:
                    cur.execute("ROLLBACK TO SAVEPOINT day_savepoint")
                    logging.error(f"ошибка на дне {day_idx}: {e}")
                    continue
            conn.commit()
            logging.info(f"SUCCESS! Добавлено строк: {count_inserted}")

        except Exception as e:
            conn.rollback()
            logging.error(f"ошибка при обновлении группы: {e}")
        finally:
            cur.close()
            conn.close()


if __name__ == "__main__":
    p=ScheduleParser()
    login=os.getenv("SIBSUTIS_LOGIN")
    password=os.getenv("SIBSUTIS_PASSWORD")
    
    if p.login(login,password):
        logging.info("Вход выполнен!")
        p.update_group_schedule("ИКС-532")
    else:
        logging.error("Не удалось войти(")
