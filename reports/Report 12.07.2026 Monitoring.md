# Отчёт о проделанной работе
**Период:** 1 неделя летнего сезона проекта
**Дата составления:** 12.07.2026

## Описание проделанной работы

За первую неделю была проделана работа по интеграции системы автоматизированного тестирования API с помощью karate test framework и внедрению базового мониторинга через grafana + prometheus.
Основной упор был сделан на обеспечение и валидации стабильности работы в текущей инфраструктуре:
* **Интеграционные API-тесты (Karate):** Разработаны и запущены сценарии тестирования бизнес-логики выставления оценок (`grades.feature`). Написан скрипт для подготовки чистой среды и автоматического развертывания тестового окружения.
* **Мониторинг (Prometheus & Grafana):** Развернута данная связка для отслеживания работоспособности сервисов, активности пользователей и состояния базы данных, также был настроен экспорт метрик с сервера
* **Сделана новая ветка** В ходе работы была создана новая branch, она готова к слиянию, но ожидает финального review от Ромы для выполнения merge в main
* **Kubernetes (Резерв):** С Ромой мы поняли что сервису потребуется развертывание в K8s, оно вроде как подготовлено, но это трудно и избыточно, на имплементацию было решено выделить время позже.

---

## Поэтапное описание

### 1. Интеграционное тестирование на Karate
Сценарии тестирования охватывают полный цикл управления успеваемостью: от авторизации преподавателя и создания контрольной точки до выставления оценки и проверки ее отображения в личном кабинете студента (основная бизнес логика)
Перед каждым прогоном тестов база данных автоматически очищается от результатов предыдущих запусков с помощью вспомогательного скрипта дабы избежать

### 2. Мониторинг базы данных и бэкенда
* Бэкенд на Go был вместе с Ромой интегрирован с библиотекой `fiberprometheus`, которая в реальном времени собирает статистику HTTP-запросов (`http_requests_total`, `http_request_duration_seconds`)
* Postgres Exporter опрашивает системную таблицу `pg_stat_database` каждые 5 секунд, транслируя метрики в формат Prometheus
* Prometheus настроен на регулярный сбор метрик с бэкенда и экспортера БД, а Grafana используется в качестве визуализатора собранных данных на порту 3000

---
### Сценарий сквозного тестирования успеваемости (Karate DSL)
Пример интеграционного сценария проверяющего корректность сопоставления выставленной оценки в профиле студента:

```cucumber
# tests/grades.feature
Scenario: Student can view their assigned grades
  # SETUP: Назначение оценки преподавателем
  Given path 'login'
  And request { login: 'student_test', password: '123456' }
  When method post
  Then status 200
  * def studentToken = response.result.token

  Given path 'profile'
  And header Authorization = 'Bearer ' + studentToken
  When method get
  Then status 200
  * def studentId = response.result.student_id

  # Авторизация преподавателя и получение ID предмета

  Given path 'api/teacher/grades'
  And header Authorization = 'Bearer ' + teacherToken
  And request { student_id: '#(studentId)', item_id: '#(itemId)', score: 1, comment: 'Excellent' }
  When method post
  Then status 200

  # ACTION: Проверка отображения в кабинете студента
  Given path 'api/student/grades/all'
  And header Authorization = 'Bearer ' + studentToken
  When method get
  Then status 200
  And match response.ok == true
  * def subjects = response.result.subjects
  * def targetSubject = karate.filter(subjects, function(x){ return x.subject_id == subjectId })[0]
  And match targetSubject != null
  * def grade = karate.filter(targetSubject.grades, function(x){ return x.item_id == itemId })[0]
  And match grade != null
  And match grade.score == 1
```

### Конфигурация Docker Compose для сервисов мониторинга
Описание связки Prometheus + Grafana и экспортера базы данных в общем оркестрационном файле:

```yaml
# docker-compose.yml
  postgres-exporter:
    build:
      context: ./backend
      dockerfile: cmd/postgres-exporter/Dockerfile
    container_name: postgres-exporter
    environment:
      DATA_SOURCE_NAME: postgres://${POSTGRES_USER}:${POSTGRES_PASSWORD}@postgres:5432/${POSTGRES_DB}?sslmode=disable
    depends_on:
      postgres:
        condition: service_healthy
    ports:
      - "9187:9187"
    restart: unless-stopped

  prometheus:
    image: prom/prometheus:v2.50.0
    container_name: prometheus
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus-data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'
      - '--storage.tsdb.path=/prometheus'
    ports:
      - "9090:9090"
    restart: unless-stopped

  grafana:
    image: grafana/grafana:10.3.0
    container_name: grafana
    environment:
      - GF_SECURITY_ADMIN_PASSWORD=admin
      - GF_PATHS_DATA=/var/lib/grafana
      - GF_PATHS_HOME=/usr/share/grafana
      - GF_PATHS_CONFIG=/etc/grafana/grafana.ini
      - GF_PATHS_LOGS=/var/log/grafana
      - GF_PATHS_PLUGINS=/var/lib/grafana/plugins
      - GF_PATHS_PROVISIONING=/etc/grafana/provisioning
      - PATH=/usr/share/grafana/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    ports:
      - "3000:3000"
    volumes:
      - grafana-data:/var/lib/grafana
    restart: unless-stopped
```

---

## TODO

- [ ] Подождать Code Review изменений и выполнить слияние (merge) рабочей ветки в `main`
- [ ] Разработать детальные дашборды в Grafana для отслеживания CPU/RAM контейнеров, RPS бэкенда и статистики Postgres
- [ ] Посмотреть kuber более подробно
