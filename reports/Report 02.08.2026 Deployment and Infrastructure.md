# Отчёт о проделанной работе
**Период:** 4 неделя летнего сезона проекта
**Дата составления:** 02.08.2026

## Описание проделанной работы

За четвёртую неделю была проведена комплексная работа по стабилизации деплоя, настройке единого https шлюза Nginx, обработке медиафайлов профиля и обеспечению стабильной работы вспомогательных сервисов (grafana, prometheus, azimut) через единый порт `9001`
Упор был сделан на решение сетевых проблем на сервере, ускорение сборки контейнеров в CI/CD, оптимизацию работы с аватарками пользователей и двухфакторную аутентификацию (2FA):
* **Обработка аватарок пользователей (Go Image Resizer):** Написали с Ромой обработчик аватарок (`avatar.go`), который принимает любые форматы изображений (JPEG, PNG, WebP), пропорционально масштабирует их методом BiLinear до 1600px, конвертирует в чистый PNG и вычисляет SHA256 контента
* **Исправление 2FA и админ-авторизации (Nginx htpasswd + 2FA):** Исправил проблему с передачей заголовков двухфакторной аутентификации (2FA) и валидацией SHA-1 хэшей в `.htpasswd`
Теперь прокси корректно пропускает заголовок `Authorization` без сброса сессий
* **Единый HTTPS шлюз Nginx:** Перенастроил конфигурацию Nginx,чтобы весь трафик (Frontend,Backend,Grafana,Prometheus,Azimutt) проксировался через единый https порт `9001`
 Добавлена обработка http запросов через `error_page 497` и отключил абсолютные редиректы (absolute_redirect off), чтобы номер порта не терялся в браузере при вводе в адресную строку
* **Автономная сборка Dockerfile.migrate:** Переписал Dockerfile миграций на multi-stage сборку с этапом `fetcher`, убрал вызовы `apk add`, которые зависали на наших серверах изза блокировок зеркал альпайна в России (мои догадки)
* **Поддержка субпутей админ-сервисов (Grafana, Prometheus, Azimutt):** Настроил проброс субпутей `/grafana/`, `/prometheus/` и `/azimutt/`, Для Azimutt был сделан `sub_filter` в Nginx для динамической подмены ссылок на статичные ресурсы, потому как без этого страница грузилась без контента
* **Стабилизация CI/CD деплоя:** В GitHub Actions заменёе стандартный `git pull` на связку из `git fetch && git reset --hard origin/main`, что решило конфликт расхождения веток на сервере если вдруг кто-то что-то случайно менял на сервере

---

## Поэтапное описание

### 1. Реализация обработки аватарок
Для загрузки профиля пользователя потребовалось надежное решение по обработке изображений:
* **Поддержка любых форматов:** Написали с Ромой модуль `ResizeAndEncodeAvatar`, использующий `image.Decode` для автоматического распознавания JPEG, PNG и WEBP
* **Пропорциональный скейлинг (BiLinear):** Изображения с высоким разрешением (например, 4K) сжимаются с сохранением пропорций до максимальной грани в 1600px и это экономит место в хранилище и ускоряет загрузку профиля на мобильных устройствах
* **Безопасность и очистка:** Перекодирование в стандартизированный PNG удаляет скрытые EXIF-метаданные и возможные вредоносные внедрения из исходных файлов что тоже не мало важно
* **Контентное хэширование (SHA256):** Вычисление SHA256 позволяет клиенту агрессивно кэшировать аватарки (`ETag`) и исключить дублирование файлов у нас в БД

### 2. Исправление 2FA и Nginx Basic Auth
При работе с админ панелями и двухфакторной аутентификацией 2FA браузер падал на этапе верификации изза битого SHA-1 хэша в `.htpasswd` и перезаписи заголовков авторизации:
* Настроен Nginx на сохранение заголовков `Authorization` при проксировании запросов к бэкенду, что починило логику валидации 2FA токенов

### 3. Настройка единого https шлюза nginx и SSL
При подключении по порту `9001` возникала проблема с редиректами: nginx сбрасывал порт при добавлении слэша `/` в конце URL или при переходе с http на https, возвращая ошибку `400 Bad Request`
* В `nginx.conf.template` включен `absolute_redirect off;`, благодаря чему все внутренние редиректы nginx стали относительными
* Добавлена директива `error_page 497 =307 https://$http_host$request_uri;`, которая автоматически перенаправляет обычные HTTP запросы, пришедшие на HTTPS порт, на защищенное соединение с сохранением порта

### 4. Оптимизация сборки Docker и решение проблем с подключением
При сборке контейнера миграций на сервере CI/CD процесс зависал на шаге `RUN apk add --no-cache ca-certificates`, как выяснилось изза недоступности `dl-cdn.alpinelinux.org`
* Переписал `Dockerfile.migrate` на двухэтапную сборку (`GO AS fetcher`), Теперь бинарный файл `goose` и корневые сертификаты `ca-certificates.crt` скачиваются на первом этапе и копируются в итоговый `alpine` образ без вызова сетевых `apk`

### 5. Настройка корректной работы Grafana, Prometheus и Azimutt через субпути
* **Grafana и Prometheus:** В `docker-compose.yml` прописал точные значения `GF_SERVER_ROOT_URL` и `--web.external-url` с указанием порта `:9001`, сделано чтобы Nginx передавал путь `/grafana/` без обрезки
* **Azimutt:** Сервис генерировал ссылки на статику от корня домена (`/elm/`, `/dist/`) Для избежания этого в Nginx зашиты директивы `sub_filter`, которые на лету подменяют в HTML `href="/` и `src="/` на `/azimutt/`... Также включил `PUBLIC_SITE: "true"`, чтобы обойти вторичный экран входа Elixir и оставить только защиту Nginx Basic Auth

---

## Highlights

### Модуль нормализации и масштабирования аватарок
Код сжатия аватарок, генерации PNG и расчета SHA256-хэша:

```go
package app

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"image"
	_ "image/jpeg"
	"image/png"
	_ "image/png"
	"io"

	"golang.org/x/image/draw"
	_ "golang.org/x/image/webp"
)

// ResizeAndEncodeAvatar декодирует любой формат (JPEG, PNG, WEBP),
// пропорционально сжимает изображение до maxDim и возвращает PNG + SHA256 хэш
func ResizeAndEncodeAvatar(r io.Reader, maxDim int) ([]byte, string, string, error) {
	srcImg, _, err := image.Decode(r)
	if err != nil {
		return nil, "", "", fmt.Errorf("failed to decode image: %w", err)
	}

	bounds := srcImg.Bounds()
	w, h := bounds.Dx(), bounds.Dy()
	if maxDim <= 0 {
		maxDim = 1600
	}

	newW, newH := w, h
	if w > maxDim || h > maxDim {
		if w >= h {
			newW = maxDim
			newH = (h * maxDim) / w
		} else {
			newH = maxDim
			newW = (w * maxDim) / h
		}
	}

	dstImg := image.NewRGBA(image.Rect(0, 0, newW, newH))
	draw.BiLinear.Scale(dstImg, dstImg.Bounds(), srcImg, srcImg.Bounds(), draw.Over, nil)

	var buf bytes.Buffer
	if err := png.Encode(&buf, dstImg); err != nil {
		return nil, "", "", fmt.Errorf("failed to encode PNG avatar: %w", err)
	}

	data := buf.Bytes()
	hashBytes := sha256.Sum256(data)
	hashStr := hex.EncodeToString(hashBytes[:])

	return data, "image/png", hashStr, nil
}
```

### Настройка Nginx с относительными редиректами и подменой путей Azimutt
Конфигурация nginx для работы шлюза с подменой ссылок azimutt:

```nginx
http {
    include /etc/nginx/mime.types;
    default_type application/octet-stream;
    absolute_redirect off;

    server {
        listen 443 ssl;
        server_name $DOMAIN;

        ssl_certificate /etc/letsencrypt/live/$DOMAIN/fullchain.pem;
        ssl_certificate_key /etc/letsencrypt/live/$DOMAIN/privkey.pem;

        # автоматический редирект HTTP в HTTPS с сохранением порта
        error_page 497 =307 https://$http_host$request_uri;

        # проксирование azimutt с динамической подменой путей статики
        location ^~ /azimutt/ {
            auth_basic "Restricted Admin Area";
            auth_basic_user_file /etc/nginx/.htpasswd;

            proxy_pass http://ejournal-azimutt:4000/;
            proxy_set_header Host $http_host;
            proxy_set_header Accept-Encoding "";

            sub_filter 'href="/' 'href="/azimutt/';
            sub_filter 'src="/' 'src="/azimutt/';
            sub_filter_once off;
            sub_filter_types text/html text/css application/javascript;
        }
    }
}
```

### Автономная сборка мигратора без сетевых вызовов apk
Сборка образа goose без использования alpine репозиториев:

```dockerfile
FROM golang:1.26.1-alpine AS fetcher

RUN wget -q -O /usr/local/bin/goose https://github.com/pressly/goose/releases/download/v3.24.1/goose_linux_x86_64 && \
    chmod +x /usr/local/bin/goose

FROM alpine:latest

# копируем сертификаты и бинарник из первого этапа без использования apk add
COPY --from=fetcher /etc/ssl/certs/ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
COPY --from=fetcher /usr/local/bin/goose /usr/local/bin/goose

WORKDIR /app
COPY migrations ./migrations

ENTRYPOINT ["goose"]
```

---

## TODO

- [ ] Настроить автопродление сертификатов Lets Encrypt через скрипты
- [ ] Подготовить дашборды в Grafana для отслеживания метрик производительности бэкенда и ресурсов Docker, которые можно будет посмотреть
- [ ] Оформить краткую инструкцию для команды по входу в панели под паролем nginx
- [ ] Подумать над админ панелью и общим доступом
