# Отчёт о проделанной работе
**Период:** 2 неделя второго сезона проекта  
**Дата составления:** 13.03.2026  

## Описание проделанной работы

За вторую неделю работы над проектом в этом сезоне были выполнены следующие шаги:
* Доработан парсер расписания с сайта университета
* Добавлена база данных и cron task
* Теперь всё крутится в докере и собирается в одну команду с помощью compose

## Поэтапное описание

### ___1.Доработан парсер___
В ходе недели было добавлено логирование для парсера а также подключение его к Postgres DB
Было добавлено больше env variables, текущая структура выглядит так:
```bash
#Auth settings
SIBSUTIS_LOGIN=CHANGEME
SIBSUTIS_PASSWORD=CHANGEME

#Postgres Settings

POSTGRES_USER=scheduler_admin
POSTGRES_PASSWORD=superSecretPassword123
POSTGRES_DB=schedule_db
DB_HOST=db
POSTGRES_PORT=5432
```
Добавлено логирование + запись в файл чтобы было удобно отлаживать, если something went wrong.
```log
2026-03-15 11:44:37,509 - INFO - SUCESSFUL UPDATE (ИКС-532)
2026-03-15 11:44:37,947 - INFO - SUCESSFUL UPDATE (ИКС-531)
2026-03-15 11:56:02,081 - INFO - SUCESSFUL UPDATE (ИКС-532)
2026-03-15 11:56:02,534 - INFO - SUCESSFUL UPDATE (ИКС-531)
2026-03-15 12:13:09,981 - ERROR - UPDATE ERROR: 'str' object has no attribute 'get'
2026-03-15 12:13:10,324 - ERROR - UPDATE ERROR: 'str' object has no attribute 'get'
2026-03-15 12:17:19,271 - ERROR - Ошибка авторизации: Проверьте логин/пароль.
```

### ___2.DB___
* Было решено попробовать развернуть db с помощью docker'a для простоты коммуникации и запуска вместе со скриптом.
db включает в себя 4 таблички по следующей схеме:\
https://drive.google.com/file/d/19GMuwKfO_o6ofsDPVMRyAq3_7QHpOlm7/view?usp=sharing
* Добавление записей в таблицу происходит через механизм транзакций в parser. В случае чего предусмотрен откат на last successful update.
В итоге имеем систему парсинга, которая работает и разворачивается в один клик.\

