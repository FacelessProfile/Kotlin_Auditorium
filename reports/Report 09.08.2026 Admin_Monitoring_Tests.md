# Отчёт о проделанной работе
**Период:** 5 неделя летнего сезона проекта
**Дата составления:** 09.08.2026

## Описание проделанной работы

За текущую неделю была проведена масштабная работа по очистке беклога: расширению админ-панели, интеграционному тестированию и полной настройке мониторинга системы в grafana(autoprovisioning):

* **Интеграционное тестирование Karate:** Разработан и доведен до идеала полный комплекс из 11 `feature` файлов (31 сценарий lifecycle). Настроена автогенерация отчетов и фиксация схем ответов API
* **Расчистка TODO и реализация админ-панели:** Добавлены модули аудита действий пользователей (`audit_logs`), режим технического обслуживания (`system_maintenance`), модуль антифрода (`admin_antifraud`) для отслеживания fraud посещений, а также быстрый переход по кнопкам к сервисам Grafana, Prometheus и Azimutt
* **Иерархическая ролевая модель (RBAC):** Заложена гибкая структура ролей: Студент $\rightarrow$ Преподаватель $\rightarrow$ Секретарь $\rightarrow$ Зав. кафедрой $\rightarrow$ Директор института $\rightarrow$ Декан $\rightarrow$ Министр образования $\rightarrow$ Администратор(пока что так, потом не сложно расширить)
* **Настройка Провиженинга (Grafana,Prometheus):** Настроен автопровиженинг дашбордов и источников данных. Решена проблема 404 ошибок изза субпутей `/prometheus/` чтобы все отображалось корректно в grafana
* **Доработка самописного postgres-exporter (Go):** Расширен функционал экспортёра метрик PostgreSQL:добавлены метрики считывания и попадания в кэш блоков (`blks_hit`, `blks_read`) и автоматический расчет `pg_stat_database_cache_hit_ratio`
* **Поддержка TOTP Push:** Разработана Event-driven система push уведомлений с 2FA TOTP кодами через Firebase Cloud Messaging (FCM), не требующая 24/7 поллинга и не расходующая заряд смартфона(нужно будет проверить насколько это правда)

---

## Поэтапное описание

### 1. Написание и отладка интеграционных тестов Karate
Для уверенности в стабильности бэкенда написан полный набор тестов на фреймворке Karate:
* Создано, переработано 11 тестов (`admin_users.feature`, `admin_antifraud.feature`, `totp_push.feature`, `notifications.feature`, `staff.feature` и др.), включающие 31 сценарий
* Исправлены расхождения в именовании полей в JSON (например, подгнан пагинированный список `items` и поля идентификаторов `notification_id`)
* Все тесты запускаются одной командой `./tests/run-tests.sh tests` и проходят без ошибок в текущем коммите) Но пока что рано делать их блокирующими в pipeline

### 2. Админ-панель, антифрод и расчистка backend TODO
Реализованы ключи и логика административного контроля:
* **Антифрод модуль (`admin_antifraud`):** Фиксирует аномалии и попытки нечестного прохождения посещаемости с выводом списка «читеров» и логов системы для админа
* **Логирование аудита (`audit_logs`):** Все действия администраторов и важные изменения прав автоматически записываются в log
* **Управление доступом и сервисами:** Добавлен эндпоинт `services_launchpad`, выдающий ссылки на панели Grafana, Prometheus и Azimutt, а также режим технического обслуживания.

### 3. Исправление связки Grafana + Prometheus
В процессе подключения Grafana к Prometheus возникли ошибки «404 Not Found» и «No data»:
* **Причина 404:** В конфигурации Grafana datasource указывался URL `http://prometheus:9090` без префикса `/prometheus/`, требуемого им, добавление субпутя починило связь
* **Причина No Data на HTTP и Postgres:** В шаблоне дашборда имена метрик содержали лишний префикс `ejournal_backend_`, а запросы к БД содержали жесткий фильтр `datname="ejournal"`. Запросы были приведены к реальным именам метрик Fiber и `postgres-exporter`
* **Доработка postgres-exporter:** В исходный код экспортёра добавили сбор метрик попадания в буферный кэш `blks_hit` и `blks_read`, благодаря чему панель **Postgres Cache Hit Ratio** стала показывать реальный процент (98.8%)!!!

### 4. Интеграция Android TOTP Push-уведомлений
Для мобильного приложения на Android требуестся передавать TOTP коды 2FA:
* Чтобы не нагружать батарею смартфона постоянным поллингом, сделана Push схема через FCM
* Реализованы эндпоинты привязки и отвязки токенов устройств (`/api/user/device-token`)
* Метод удаления токена сделан идемпотентным при повторном запросе отмена подписки не вызывает ошибку 404, а отдаёт успешный результат, что проверено тестами

---

## Highlights

### 1. Расчет Cache Hit Ratio в самописном postgres-exporter (Go)
Добавление новых метрик и расчёт процента попадания в кэш БД PostgreSQL:

```go
var (
	pgBlksHit = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "pg_stat_database_blks_hit",
		Help: "Number of disk blocks found in buffer cache",
	})
	pgBlksRead = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "pg_stat_database_blks_read",
		Help: "Number of disk blocks read",
	})
	pgCacheHitRatio = prometheus.NewGauge(prometheus.GaugeOpts{
		Name: "pg_stat_database_cache_hit_ratio",
		Help: "Postgres buffer cache hit ratio in percent (0-100)",
	})
)

// В цикле опроса базы данных:
var numBackends int
var commits, rollbacks, blksHit, blksRead float64
err = db.QueryRow("SELECT COALESCE(sum(numbackends), 0), COALESCE(sum(xact_commit), 0), COALESCE(sum(xact_rollback), 0), COALESCE(sum(blks_hit), 0), COALESCE(sum(blks_read), 0) FROM pg_stat_database").Scan(&numBackends, &commits, &rollbacks, &blksHit, &blksRead)
if err == nil {
	pgNumBackends.Set(float64(numBackends))
	pgXactCommit.Set(commits)
	pgXactRollback.Set(rollbacks)
	pgBlksHit.Set(blksHit)
	pgBlksRead.Set(blksRead)

	ratio := 100.0
	if blksHit+blksRead > 0 {
		ratio = (blksHit / (blksHit + blksRead)) * 100.0
	}
	pgCacheHitRatio.Set(ratio)
}
```

### 2. Идемпотентное удаление FCM-токена устройства для Android (`totp_push.go`)
Обеспечение стабильности при отвязке Push-уведомлений на мобильном устройстве:

```go
// DeleteDeviceToken удаляет токен устройства пользователя из DB
func (s *Service) DeleteDeviceToken(userID int64, token string) error {
	res, err := s.db.Exec("DELETE FROM user_device_tokens WHERE user_id = $1 AND device_token = $2", userID, token)
	if err != nil {
		return fmt.Errorf("failed to delete device token: %w", err)
	}

	//если записи не было, не падаем с ошибкой, а регистрируем успешное удалени
	rows, err := res.RowsAffected()
	if err == nil && rows == 0 {
		log.Printf("Device token not found for user %d, treating as successfully deleted", userID)
	}

	return nil
}
```

### 3. Интеграционный тест Karate для регистрации Android устройства (`totp_push.feature`)
Сценарий проверки работы с FCM токенами на бэкенде:

```feature
Feature: Android TOTP Push & Device Token Management

Background:
  * url baseUrl
  * def adminLogin = read('classpath:helpers/login.feature')
  * def adminToken = adminLogin({ login: 'admin_test', password: '123456' }).token

Scenario: Register and unregister Android FCM device token
  Given path '/api/user/device-token'
  And header Authorization = 'Bearer ' + adminToken
  And request { device_token: 'test_fcm_token_12345', device_type: 'android' }
  When method post
  Then status 200
  And match response.ok == true

  Given path '/api/user/device-token'
  And header Authorization = 'Bearer ' + adminToken
  And param token = 'test_fcm_token_12345'
  When method delete
  Then status 200
  And match response.ok == true
```

---

## TODO (Задачи для разработок под Android)

- [ ] **Прием FCM Push-уведомлений:** Реализовать в Android-приложении `FirebaseMessagingService` для приема Push-уведомлений с TOTP-кодом в фоновом режиме
- [ ] **Безопасное хранение ключей:** Настроить сохранение TOTP-секретов на устройстве через `EncryptedSharedPreferences`
- [ ] **Авто-подстановка 2FA кода:** Добавить системный всплывающий баннер (Notification) на андроиде
- [ ] **Системные уведомления от админа:** Реализовать в приложении экран просмотра важных сообщений и объявлений
