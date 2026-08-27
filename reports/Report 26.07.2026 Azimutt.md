# Отчёт о проделанной работе
**Период:** 3 неделя летнего сезона проекта
**Дата составления:** 26.07.2026

## Описание проделанной работы

За текущую(третью) неделю мы развернули и полностью изолировали self-hosted систему визуализации и проектирования баз данных **Azimutt** в нашем продуктовом стеке `ejournal`.
Основной упор был сделан на обеспечение безопасности, снятие SaaS-ограничений бесплатного тарифа и автоматизацию административного доступа:
* **Автономный визуализатор Azimutt + Gateway:** Настроили локальное развертывание Azimutt и микросервиса `azimutt-gateway` в Docker Compose для исследования и совместного редактирования схемы PostgreSQL без отправки данных во внешние сервисы.
* **Обход платных ограничений (Enterprise Unlock):** Написали специальный триггер в PostgreSQL, который автоматически присваивает всем создаваемым организациям статус `enterprise` со 100 слотами и активной лицензией. Это решило проблему с ошибкой "Your Free plan can't save projects".
* **Защита от публичной регистрации:** Чтобы закрыть сервис от посторонних лиц, на уровне базы данных был внедрен триггер `trg_block_self_registration`. Теперь зарегистрироваться с веб-интерфейса невозможно.
* **Централизованный CLI-скрипт администрирования:** Написали утилиту на Python (`scripts/manage_azimutt_users.py`), которая генерирует Bcrypt-хэши через Elixir-контейнер Azimutt, создает учетные записи, управляет ролями (`writer`/`owner`), сбрасывает пароли и выполняет каскадное удаление по 18 связанным таблицам.
* **Гарантированный деплой на сервере (`setup-azimutt-triggers`):** Выделили отдельный инициализационный контейнер, который циклически ждет завершения миграций Azimutt и намертво накатывает все защитные триггеры при первом «холодном» старте на сервере.

---

## Поэтапное описание

### 1. Развертывание Self-Hosted Azimutt & Gateway
Мы добавили в `docker-compose.yml` два новых сервиса: `azimutt` (Phoenix/Elixir) и `azimutt-gateway` (Node 20). Гейтвей отвечает за безопасное выполнение интроспекции PostgreSQL и передачу метаданных схемы в UI. Для корректной работы браузера настроили CORS и сетевое взаимодействие между контейнерами.

### 2. Снятие тарифных лимитов и защита регистраций в БД
При работе с официальным образом Azimutt требует подписку для сохранения проектов. Чтобы не завязаться на SaaS, мы реализовали триггеры прямо на уровне PostgreSQL:
* `set_organization_plan()` — при любых операциях с организациями выставляет `plan = 'enterprise'`, `plan_status = 'active'`, `plan_seats = 100` и текущую дату `plan_validated = NOW()`, предотвращая краши Elixir `Date.compare` и блокировки UI.
* `check_user_registration_permission()` — проверяет специальный JSONB-флаг `created_by_admin` у новых пользователей. Если пользователь пробует зарегистрироваться сам через форму на сайте, триггер выбрасывает `RAISE EXCEPTION`.

### 3. Автоматизация деплоя и каскадное управление пользователями
* **Проблема холодного старта:** При первом запуске Azimutt миграции таблиц запускаются уже после старта базы. Обычные SQL-скрипты падали, так как таблиц `users` и `organizations` еще не существовало. Мы решили это через sidecar-сервис `setup-azimutt-triggers` с циклом ожидания `until psql ... SELECT 1 FROM information_schema.tables WHERE table_name = 'users'`.
* **Управление пользователями (Python CLI):** Написали утилиту `manage_azimutt_users.py`. Она позволяет создавать пользователей, автоматически добавляя их в корпоративную организацию `Sibsutis` с ролью `writer`, выполнять безопасный сброс паролей (`reset-password`), чинить битые связи (`repair`) и каскадно удалять учетки без нарушения Foreign Key ограничений в 18 таблицах.

---

## Code Highlights

### Автоматическая синхронизация триггеров защиты и Enterprise-плана (`docker-compose.yml`)
Пример инициализационного сервиса, который ждет завершения миграций Azimutt и активирует бизнес-логику безопасности:

```yaml
  setup-azimutt-triggers:
    image: postgres:16-alpine
    container_name: ejournal-setup-azimutt-triggers
    depends_on:
      postgres:
        condition: service_healthy
      azimutt:
        condition: service_started
    environment:
      POSTGRES_USER: ${POSTGRES_USER:-postgres}
      POSTGRES_PASSWORD: ${POSTGRES_PASSWORD:-postgres}
    command: >
      sh -c "until PGPASSWORD=\"$$POSTGRES_PASSWORD\" psql -h postgres -U \"$$POSTGRES_USER\" -d azimutt_db -tc \"SELECT 1 FROM information_schema.tables WHERE table_name = 'users';\" | grep -q 1; do echo 'Waiting for Azimutt tables migration...'; sleep 3; done;
             PGPASSWORD=\"$$POSTGRES_PASSWORD\" psql -h postgres -U \"$$POSTGRES_USER\" -d azimutt_db -c \"
             CREATE OR REPLACE FUNCTION set_organization_plan() RETURNS trigger AS \\\$\\\$ BEGIN NEW.plan := 'enterprise'; NEW.plan_status := 'active'; NEW.plan_seats := 100; IF NEW.plan_validated IS NULL THEN NEW.plan_validated := NOW(); END IF; RETURN NEW; END; \\\$\\\$ LANGUAGE plpgsql;
             DROP TRIGGER IF EXISTS trg_organization_plan ON organizations;
             CREATE TRIGGER trg_organization_plan BEFORE INSERT OR UPDATE ON organizations FOR EACH ROW EXECUTE FUNCTION set_organization_plan();

             CREATE OR REPLACE FUNCTION check_user_registration_permission() RETURNS trigger AS \\\$\\\$ BEGIN IF NEW.data IS NULL OR (NEW.data->>'created_by_admin') IS DISTINCT FROM 'true' THEN RAISE EXCEPTION 'Self-registration is disabled. Only administrators can create accounts.'; END IF; RETURN NEW; END; \\\$\\\$ LANGUAGE plpgsql;
             DROP TRIGGER IF EXISTS trg_block_self_registration ON users;
             CREATE TRIGGER trg_block_self_registration BEFORE INSERT ON users FOR EACH ROW EXECUTE FUNCTION check_user_registration_permission();
             \""
    restart: "no"
```

### CLI скрипт администрирования пользователей с генерацией Bcrypt и каскадной очисткой (`scripts/manage_azimutt_users.py`)
Фрагмент генерации Bcrypt-пароля через Elixir-контейнер и выполнения безопасного каскадного удаления по 18 таблицам:

```python
def generate_bcrypt_hash(password: str) -> str:
    """Generates a Bcrypt hash using Azimutt's Elixir runtime container."""
    elixir_code = f'IO.puts(Bcrypt.hash_pwd_salt("{password}"))'
    cmd = [
        "docker", "compose", "exec", "-T", "azimutt",
        "/app/bin/azimutt", "eval", elixir_code
    ]
    output = run_cmd(cmd)
    for line in output.splitlines():
        line = line.strip()
        if line.startswith("$2b$") or line.startswith("$2a$"):
            return line
    raise RuntimeError(f"Failed to extract Bcrypt hash from output: {output}")

def delete_user(email: str):
    email = email.lower().strip()
    if not check_user_exists(email):
        print(f"❌ User with email '{email}' does not exist.", file=sys.stderr)
        sys.exit(1)

    # Каскадная очистка всех связанных записей (events, tokens, profiles, orgs)
    sql = f"""
    DELETE FROM events WHERE created_by IN (SELECT id FROM users WHERE email = '{email}');
    DELETE FROM user_tokens WHERE user_id IN (SELECT id FROM users WHERE email = '{email}');
    DELETE FROM user_profiles WHERE user_id IN (SELECT id FROM users WHERE email = '{email}');
    DELETE FROM user_auth_tokens WHERE user_id IN (SELECT id FROM users WHERE email = '{email}');
    DELETE FROM project_tokens WHERE created_by IN (SELECT id FROM users WHERE email = '{email}');
    DELETE FROM organization_invitations WHERE created_by IN (SELECT id FROM users WHERE email = '{email}');
    DELETE FROM organization_members WHERE user_id IN (SELECT id FROM users WHERE email = '{email}');
    DELETE FROM users WHERE email = '{email}';
    """
    res = execute_sql(sql)
    print(f"🗑️ Deleted user {email}: {res}")
```

---

## TODO

- [ ] Зафиксировать первоначальный "Remote" проект визуализации схемы `ejournal` на продуктовом сервере
- [ ] Оформить документацию в README по добавлению разработчиков через `manage_azimutt_users.py`
- [ ] Настроить проброс портов в Nginx reverse proxy для удобного доступа к Azimutt по субдомену
