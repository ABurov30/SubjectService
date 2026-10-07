# SubjectService

Сервис хранит Subject и создает задачу в Jira при переводе в статус `REVIEW`. Изменение статуса сохраняется в PostgreSQL, а запрос в Jira выполняется в фоне через transactional outbox.

Проект использует Java 17, Spring Boot, Hibernate/JPA и Liquibase. PostgreSQL хранит данные и очередь outbox. Swagger описывает REST API, Docker Compose запускает сервис вместе с базой.

## Как работает

`POST /subjects` создает Subject в статусе `CREATED`. `PATCH /subjects/status` меняет статус на `CREATED`, `REVIEW` или `TERMINATED`.

При переходе в `REVIEW` сервис сохраняет Subject и запись outbox в одной транзакции. Фоновый обработчик забирает запись, ищет задачу Jira по метке `subject-<UUID>` и создает ее, если совпадений нет. Результат сохраняется в outbox.

Успешный PATCH подтверждает запись в базу. Задача Jira может появиться позже.

## Принятые решения

- Использовал transactional outbox, чтобы изменение статуса и регистрация отправки в Jira фиксировались вместе. HTTP-запросы выполняются вне транзакции базы.
- Для каждого Subject регистрируется одна операция создания задачи. Уникальный индекс по `subject_id` и `INSERT ON CONFLICT` защищают от повторной регистрации. Повторный `REVIEW`, в том числе после возврата в `CREATED`, сохраняет прежнюю операцию.
- Для конкурентных изменений Subject использовал `@Version`. Конфликт обновления возвращает `409 Conflict`.
- Обработчик забирает записи через `FOR UPDATE SKIP LOCKED`. Токен владельца и срок аренды позволяют восстановить обработку после сбоя и не дают прежнему владельцу перезаписать результат нового.
- Результат доставки `OutboxOutcome` создаётся через builder с именованными параметрами повторов, HTTP-вызова и неопределённого исхода. Для успешной доставки есть фабрика `published()`.
- При сетевых ошибках, `5xx` и `429` отправка повторяется с увеличением задержки. Учитывается `Retry-After`. Остальные `4xx` и исчерпание попыток переводят операцию в `FAILED`.
- Перед созданием задачи выполняется поиск по метке. Это уменьшает вероятность дублей при потере ответа Jira, но поиск и создание не атомарны: при задержке индексации дубль возможен.

`FAILED` — конечный статус. Автоматического или ручного перезапуска нет; это отклонение от требования ТЗ о перезапуске FAILED. Перевод Subject в `TERMINATED` не отменяет уже зарегистрированную отправку.

## Запуск

Нужны Java 17, Maven 3.9+ и Docker с Compose v2.

Обработчик outbox находится в пакете `subjectservice.outbox` и собирается вместе с сервисом. Отдельная outbox-библиотека и токен GitHub Packages для сборки не нужны.

Пакет `subjectservice.outbox` потенциально можно вынести в отдельную библиотеку и переиспользовать в других сервисах.

```sh
cp .env.example .env
mvn spotless:apply
mvn clean verify
docker compose up -d --build --wait
```

WireMock (`jira-mock`) запускается только при включённом профиле: установите в `.env` `COMPOSE_PROFILES=jira-mock` (уже задано в `.env.example`) или выполните `COMPOSE_PROFILES=jira-mock docker compose up -d --build --wait`. Сервис обращается к нему по адресу `http://jira-mock:8080`, с хоста мок доступен на `http://localhost:8081`. Если `.env` уже существует, установите также `JIRA_URL=http://jira-mock:8080`.

Без `COMPOSE_PROFILES` или с пустым значением мок не запускается. Требуется Docker Compose 2.20.0+ для необязательной зависимости `depends_on.required: false`.

Ответы заданы в `wiremock/mappings`: поиск `POST /rest/api/3/search/jql` возвращает пустой список, создание `POST /rest/api/3/issue` — `201` с задачей `TEST-100`. Это простой мок без хранения задач: поиск всегда пустой, а создание всегда возвращает одинаковый id/key. После изменения файлов перезапустите мок командой `docker compose restart jira-mock`. Полученные запросы можно посмотреть через `curl http://localhost:8081/__admin/requests`.

Для настоящей Jira установите `COMPOSE_PROFILES=` и заполните в `.env` значения `JIRA_URL`, `JIRA_EMAIL`, `JIRA_TOKEN`, `JIRA_PROJECT` и `JIRA_ISSUE_TYPE`. Если мок уже запущен, сначала остановите его: `docker compose stop jira-mock`. Клиент использует Jira Cloud REST API v3 и Basic Auth с email и API token.

После запуска:

- Swagger UI: [localhost:8080](http://localhost:8080/).
- Health check: [localhost:8080/actuator/health](http://localhost:8080/actuator/health).
- OpenAPI: [localhost:8080/v3/api-docs](http://localhost:8080/v3/api-docs).

```sh
docker compose logs -f subject-service
docker compose down
```

`down` сохраняет данные PostgreSQL в Docker volume.

## API

Создать Subject:

```sh
curl -X POST http://localhost:8080/subjects -H 'Content-Type: application/json' -d '{"name":"Example"}'
```

Ответ `201 Created` содержит `id`, `name`, `status` и `version`. Имя обязательно, длина — до 255 символов.

Перевести созданный Subject в `REVIEW`, подставив его `id`:

```sh
curl -X PATCH http://localhost:8080/subjects/status -H 'Content-Type: application/json' -d '{"subjectId":"<id>","status":"REVIEW"}'
```

Ошибки запроса возвращают `400`, отсутствующий Subject — `404`, конфликт версии — `409`, непредвиденная ошибка — `500`.

Для проверки запросов есть [Postman-коллекция](postman/SubjectService.postman_collection.json) и [порядок ее запуска](postman/README.md).

## Настройки

Пример переменных находится в [.env.example](.env.example). `.env` читает Docker Compose; при запуске JAR переменные нужно передать Java-процессу через окружение.

| Переменная | По умолчанию | Назначение |
| --- | --- | --- |
| `SUBJECT_PORT` / `POSTGRES_PORT` | `8080` / `5432` | Порты сервиса и базы |
| `JIRA_MOCK_PORT` | `8081` | Порт WireMock на хосте |
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | `subjects` | Параметры локальной базы |
| `OUTBOX_SCHEDULER_ENABLED` | `true` | Фоновая отправка; `false` для проверки только REST API |
| `OUTBOX_POLLING` | `5s` | Период опроса outbox |
| `OUTBOX_MAX_RETRIES` | `5` | Число повторов после первой попытки |
| `OUTBOX_INITIAL_DELAY` / `OUTBOX_MAX_DELAY` | `5s` / `5m` | Начальная и максимальная задержка повторов |
| `OUTBOX_LEASE` | `60s` | Срок аренды записи |

Без доступной Jira сервис принимает REST-запросы, а обработчик повторяет отправку до заданного лимита. Для диагностики используются логи и состояние outbox в базе.

## Проверки

`mvn clean verify` запускает Checkstyle, Spotless, unit-тесты, интеграционные тесты и генерацию отчёта JaCoCo без минимального порога покрытия. Интеграционные тесты используют PostgreSQL через Testcontainers 2 (`org.testcontainers.postgresql.PostgreSQLContainer`) и WireMock для Jira; для них нужен работающий Docker.

Тесты проверяют откат транзакций, конкурентные изменения, повторную регистрацию, восстановление аренды и потерю ответа Jira. Ограничение дедупликации при задержке индексации описано выше.

CI выполняет те же проверки без токена GitHub Packages. Отчеты сохраняются в `target/surefire-reports/`, `target/failsafe-reports/` и `target/site/jacoco/`.
