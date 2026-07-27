# Recreate fidelity and safety

## Overview

max-adversarial ревью репозитория дало 15 подтверждённых дефектов. Все они группируются в три оси:

1. **Точность воссоздания (fidelity).** `recreate()` строит create-body из inspect'а старого контейнера и при этом
   теряет или подделывает часть состояния: анонимные volume (потеря данных БД), `MacAddress`/`GwPriority`,
   `platform`, `Config.StopTimeout`, и — главное — копирует лейбл `com.docker.compose.image` со **старым** image id.
   Из-за последнего каждый последующий `docker compose up` пересоздаёт контейнер, который kodkod только что обновил
   (исходный вопрос, с которого началось ревью).
2. **Безопасность жизненного цикла (safety).** Старый контейнер и старый образ удаляются сразу после `204` от
   `POST /start`, без проверки, что процесс выжил; `DELETE /images/<old>` снимает пользовательские теги (пиннутый
   образ для отката); SIGTERM в середине recreate детерминированно ломает rollback и оставляет сервис остановленным
   под именем `*_kodkod_old_*`, невидимым для последующих циклов; проглоченные ошибки `start` превращают транзиентный
   сбой в постоянный простой; нет памяти о «отравленном» образе, поэтому неподнимающийся `:latest` вызывает
   самоинициированный простой каждый цикл.
3. **Поведение относительно compose и соседей.** `depends_on` парсится только по первому полю (игнорируется
   `condition: service_healthy`); autoheal делает голый `restart` без учёта зависимостей, ломая контейнеры,
   разделяющие сетевой namespace; немониторимые sidecar'ы остаются с мёртвым netns; глобальный `cycleLock`
   держится через все pull'ы и блокирует autoheal на минуты.

Отдельно — **находка #15: новая record/replay-обвязка не может упасть**. Эмпирически проверено шесть способов сломать
фикстуры при зелёной сборке. Это блокирующая проблема для всего остального: план выбран TDD, а тест, который не падает
на баге, бесполезен. Поэтому обвязка чинится первой.

Итог: kodkod после этого плана воссоздаёт контейнер так, что compose считает его сошедшимся, не теряет данные и
никогда не оставляет сервис в состоянии, из которого не может выбраться сам.

## Context (from discovery)

- **Файлы ядра:** `src/main/kotlin/io/heapy/kodkod/` — `Updater.kt` (цикл обновления, recreate, rollback, граф
  зависимостей), `ImageDefaults.kt` (вычитание дефолтов образа, сборка create-body), `DockerApi.kt` (HTTP/1.1 поверх
  unix-сокета), `DockerClient.kt` (интерфейс, за которым тесты подменяют демон), `Autoheal.kt`, `Main.kt`, `Config.kt`.
- **Тесты:** `src/test/kotlin/io/heapy/kodkod/` — `FakeDockerClient.kt` (in-memory демон), `UpdaterTest.kt`,
  `UpdaterGraphTest.kt`, `AutohealTest.kt`, `ImageDefaultsTest.kt`, `ConfigTest.kt`, `DockerApiParseTest.kt`,
  плюс новая replay-обвязка `DockerReplayTest.kt` + `OpLoggingClient.kt` + `ReplayDockerTransportTest.kt`.
- **Record/replay:** `DockerRecording.kt` (модель фикстур, `RecordingDockerTransport`, `ReplayDockerTransport`),
  фикстуры в `src/test/resources/docker-fixtures/engine-29.5.2_compose-5.1.4/{update-recreate,update-noop,
  autoheal-restart,deps-ordered}`, рекордер `src/e2eTest/kotlin/io/heapy/kodkod/e2e/DockerFixtureRecorder.kt`.
- **E2E:** `src/e2eTest/kotlin/io/heapy/kodkod/e2e/KodkodE2eTest.kt` + compose-файлы в `e2e/compose.*.yml`,
  запуск `./gradlew e2eTest` (DinD по умолчанию, `-Pkodkod.e2e.useCurrentDocker=true` — против текущего демона).
- **Паттерны проекта:** socket-only, без парсинга compose-файлов; вся конфигурация из env через `Config.fromEnv`;
  оркестрация всегда идёт через `DockerClient`, никогда напрямую через `DockerApi`; вспомогательные функции —
  `internal` + чистые, чтобы тестироваться без демона (`buildCreateBody`, `resolveLinks`, `topoSort`).
- **Незакоммиченное:** вся record/replay-обвязка и фикстуры сейчас в рабочем дереве (`git status`), первый коммит
  этого плана логично объединить с их доводкой.
- **Проверено по фикстурам при планировании:** `com.docker.compose.image` побайтово равен полю `Image` контейнера;
  лейбл `com.docker.compose.depends_on` = `db:service_started:false`; `Config.MacAddress` = `null` при заполненном
  `NetworkSettings.Networks[*].MacAddress` (то есть MAC сгенерирован демоном); `ImageManifestDescriptor.platform`
  у разных образов различается наличием `variant`; `?t=10` входит в ключ replay для `stop`/`restart`.

## Development Approach

- **testing approach**: TDD — каждая задача начинается с теста, который воспроизводит находку и **падает** на
  текущем коде; только потом фикс.
  - **исключение**: задачи, меняющие сигнатуры в `DockerClient` (9, 19), не могут иметь красный тест до правки —
    он не скомпилируется. Для них порядок: сигнатура → тест на новое поведение (красный на старой реализации) → фикс.
- **CRITICAL: задача, меняющая метод/путь/query HTTP-запросов, обязана перезаписать корпус фикстур внутри себя.**
  Ключ replay — это `"<method> <path>"`, поэтому снятие `?t=`, новый inspect или `all=true` листинг ломают
  `DockerReplayTest` (после задачи 1 он строгий). Перезапись требует Docker: она отмечена отдельным чекбоксом в
  задачах 8, 9, 10, 11, 13, 15, 19. Тела запросов в ключ не входят — задачи 4, 6, 7 перезаписи не требуют.
- **совместимость**: дефолты меняем напрямую (проект pre-1.0), корректное поведение важнее сохранения текущего.
  Новые `KODKOD_*` вводим только там, где нужен тюнинг (окна ожидания, cooldown) или где «правильное» поведение
  спорно (см. решение по `depends_on.restart` в Solution Overview).
- завершать каждую задачу полностью перед переходом к следующей
- небольшие сфокусированные изменения
- **CRITICAL: каждая задача ОБЯЗАНА содержать новые/обновлённые тесты** на изменённый код
  - тесты не опциональны — это часть чеклиста
  - unit-тесты на новые и изменённые функции
  - тест-кейсы на новые ветки кода, обновление существующих при смене поведения
  - покрывать и успешный, и ошибочный сценарий
- **CRITICAL: все тесты должны проходить до старта следующей задачи** — без исключений
- **CRITICAL: обновлять этот файл плана при изменении объёма работ**
- прогонять тесты после каждого изменения
- перезапись фикстур всегда с ревью diff'а: в нём должны меняться только ожидаемые пути/тела

## Testing Strategy

- **unit-тесты** (`./gradlew test`) — обязательны в каждой задаче. Основной инструмент — `FakeDockerClient`
  (детерминированный, без демона) и чистые функции из `ImageDefaults.kt`.
- **replay-тесты** (`./gradlew test`, `DockerReplayTest`) — прогоняют настоящие записанные ответы Docker через
  настоящий `DockerApi` + `Updater`/`Autoheal`; после задачи 1 они умеют падать. Их ценность — реальные байты
  ответов движка (форматы лейблов, `Mounts`, `ImageManifestDescriptor`), а не проверка выходов kodkod'а.
- **create-body проверяется через `OpLoggingClient.created`/`FakeDockerClient.created`**, а не через фикстуры:
  тело запроса — это выход кода под тестом, записывать его в корпус значит хранить golden своей же выдачи,
  который самозалечивается при каждой перезаписи.
- **e2e-тесты** (`./gradlew e2eTest`, DinD) — для того, что нельзя доказать без демона: повторный `compose up`,
  наследование анонимного volume, netns-потребители, liveness-гейт. Требуют Docker, в CI отдельным job'ом.
- **правило соответствия уровней**: чистая трансформация JSON → unit; поведение цикла → unit на `FakeDockerClient`;
  контракт с реальным Docker/compose → e2e; регресс на реальных байтах ответов → replay-фикстура.
- e2e-тесты пишутся в той же задаче, что и код, и должны проходить до перехода к следующей задаче.

## Progress Tracking

- отмечать выполненное `[x]` сразу
- новые обнаруженные задачи добавлять с префиксом ➕
- проблемы/блокеры — с префиксом ⚠️
- обновлять план, если реализация отклоняется от исходного объёма
- держать план в синхроне с фактической работой

## Solution Overview

**Порядок неслучаен.** Сначала (задачи 1–3) делаем тестовую обвязку способной падать — иначе TDD на остальных 14
находках невозможен. Затем (4–8) чиним точность create-body: это дешёвые чистые трансформации с однозначными
тестами, и именно они закрывают исходный вопрос про `compose up`. Затем (9–14) — безопасность жизненного цикла, где
меняется поведение и нужны новые конфиг-опции. В конце (15–19) — соседи: autoheal, netns, compose-условия,
конкурентность.

Ключевые архитектурные решения:

- **`com.docker.compose.image` перештамповывается только когда контейнер реально обновляется**
  (`Target.stale == true`), во всех остальных случаях (recreate по зависимости) копируется дословно. Лейбл
  описывает *разрешённый локальный image id*; при recreate по зависимости образ не менялся, и переписывать нечего.
  Ветки «новый id неизвестен» не существует: `markStale` не помечает цель stale, если не смог получить новый id.
- **`com.docker.compose.config-hash` копируется дословно.** Это хеш *определения сервиса в compose-файле*, который
  мы не меняем. Именно поэтому важно, чтобы остальные фиксы (volume, MacAddress, platform) реально восстанавливали
  конфигурацию: compose больше не станет её «чинить» за нас.
- **`stop`/`restart` получают `timeout: Int?`.** Когда у kodkod нет явного override (лейбл `<ns>.stop.timeout` или
  явно выставленный `KODKOD_STOP_TIMEOUT`), `?t=` не отправляется вовсе и демон применяет собственный
  `Config.StopTimeout` контейнера. Read timeout при этом считается из `Config.StopTimeout` из уже имеющегося
  inspect'а (в `Updater`) — иначе снятие `?t=` само создаёт таймаут на длинном graceful stop.
- **`depends_on.condition` соблюдаем, `depends_on.restart` — нет (по умолчанию).** `condition: service_healthy`
  описывает порядок запуска и обязателен к соблюдению. Поле `restart` в compose управляет распространением
  `compose restart`, а не заменой зависимости на **новый контейнер с новым IP/DNS**, где перезапуск зависимых
  обычно нужен. Так как `restart: false` — дефолт спеки, соблюдение этого поля превратило бы задокументированное
  поведение kodkod (`README` «Ordering & dependencies», KDoc `Updater.kt:20-23`) в no-op почти для всех стеков.
  Поэтому: парсим все три поля, но подчиняемся `restart` только при `KODKOD_RESPECT_DEPENDS_ON_RESTART=true`
  (default `false`). И даже тогда `restart=false` может подавлять только `linkedToRestarting`, **никогда**
  `linkedToRecreate` — иначе netns-потребитель останется с мёртвым namespace (то, что чинят задачи 15 и 18).
- **Единый резолвер зависимостей.** Поиск netns-потребителей и `--link`-зависимых выносится в `Dependents.kt`,
  которым пользуются и `Updater` (в том числе за пределами мониторимого набора), и `Autoheal`.
- **Разделение цикла обновления на `plan()` и `apply()`.** Первая фаза только читает (list/inspect/probe/pull) и
  идёт без глобального лока; вторая мутирует и держит лок. Это чинит голодание autoheal и даёт естественную точку
  для проверки «состояние не изменилось, пока мы качали образ». Обратная сторона: задачи 10 и 17 добавляют внутрь
  `apply` ожидания (liveness-гейт, `service_healthy`), поэтому суммарное время под локом ограничивается явно.
- **Инъекция времени.** Liveness-гейт, cooldown «отравленного» образа, autoheal-backoff и ожидание `service_healthy`
  нуждаются в часах и паузах. Вводим минимальный `TimeSource`/`Sleeper` с дефолтом на реальные часы, передаваемый
  в конструкторы `Updater`/`Autoheal` — иначе соответствующие тесты либо неписуемы, либо идут секунды и часы.
- **Состояние между циклами.** `Updater` получает память об известных плохих парах `(containerId, imageId)` с одним
  cooldown'ом (без экспоненты — минимум механики, закрывающий самоинициированный простой). `Autoheal` — счётчик
  попыток на контейнер для экспоненциального backoff'а.

## Technical Details

- **Лейблы compose:** `com.docker.compose.image` = `sha256:<id>` локального образа. Новый id уже вычисляется в
  `Updater.markStale` (`Updater.kt:153`, и в ветке registry-digest — `Updater.kt:139`) и выбрасывается; кладём его
  в `Target.newImageId` и передаём в `buildCreateBody`.
- **Анонимные volume:** в inspect'е анонимный том виден **только** в top-level `Mounts[]`
  (`{Type,Name,Source,Destination,Driver,Mode,RW,Propagation}`). В `HostConfig` его может не быть вовсе (образ
  объявил `VOLUME`, или `docker run -v /data` — тогда путь лежит в `Config.Volumes`, который `structMapSubtract`
  (`ImageDefaults.kt:150`) осознанно вычитает как дефолт образа). Поэтому резолвер идёт **от top-level `Mounts[]`**:
  для каждой записи с `Type=volume` и непустым `Name`, чей `Destination` ещё не покрыт `HostConfig.Mounts`/`Binds`,
  синтезируем запись в `HostConfig.Mounts`. **Важно:** `/containers/create` отвергает неизвестные поля, а ключи
  top-level `Mounts` (`Destination`, `Name`, `Mode`, `Propagation`, `RW`) в `HostConfig.Mounts` невалидны —
  эмитим только `Type`/`Source`/`Target`/`ReadOnly`.
- **Endpoint:** `cleanEndpoint` (`Updater.kt:301`) пропускает только `Aliases`/`IPAMConfig`/`Links`/`DriverOpts`.
  Добавляем `GwPriority` всегда, а `MacAddress` — только если у контейнера непустой `Config.MacAddress`: именно его
  заполняют `--mac-address` и compose `mac_address:`, тогда как `NetworkSettings.Networks[*].MacAddress` в Docker ≥26
  случайно сгенерирован (подтверждено фикстурой `deps-ordered/0002`) и тащить его в новый контейнер не нужно.
- **platform:** контейнерный inspect несёт `ImageManifestDescriptor.platform = {architecture, os, variant}`.
  Передаём `os/arch` **без `variant`**: variant описывает конкретный манифест старого образа (в фикстурах у одного
  образа `arm64/v8`, у другого просто `arm64`), и пин на него рискует «no matching manifest» при обновлении.
- **depends_on:** значение лейбла — список `<service>:<condition>:<restart>`, например `db:service_started:false`
  (подтверждено фикстурой `deps-ordered/0001`). Парсим все три поля: `condition ∈ {service_started,
  service_healthy, service_completed_successfully}`, `restart ∈ {true,false}`.
- **liveness-гейт:** после `start` опрашиваем `GET /containers/{id}/json` c интервалом ~500 мс.
  Провал = `State.Running == false`, `State.Restarting == true` или `Health.Status == "unhealthy"`.
  Успех = N подряд удачных проб (default 3) — с ранним выходом, не дожидаясь конца окна.
  `Health.Status == "starting"` в конце окна считается **успехом**: `start_period` — это заявление автора образа
  о допустимом времени старта, и трактовать его как провал значит откатывать здоровые обновления.
- **cleanup образа:** перед `DELETE /images/<old>` инспектируем образ; пропускаем удаление, если у него остались
  `RepoTags`, отличные от обновляемого ref'а. Опасный случай — ровно **один** оставшийся пользовательский тег
  (`app:1.26` после того, как `app:latest` уехал на новый образ): такое удаление проходит успешно и снимает тег.
  Случай с двумя тегами Docker и сейчас отбивает 409, который `DockerApi.removeImage` уже терпит.
- **shutdown:** хук вызывает `scheduler.shutdown()` + `awaitTermination(KODKOD_SHUTDOWN_GRACE, default 30s)` и
  только затем `shutdownNow()`. Дополнительно `rollback()` снимает флаг прерывания (`Thread.interrupted()`) перед
  своими вызовами и восстанавливает его после — иначе NIO падает мгновенно и обе операции проглатываются.
- **reconcile:** бэкап опознаётся строго по суффиксу `_kodkod_old_<12 hex собственного id контейнера>` — имя,
  которое просто похоже на бэкап, не трогаем. Reconcile выполняется на старте процесса независимо от
  `KODKOD_UPDATE_ENABLED` (иначе с выключенным апдейтером осиротевший бэкап не восстановится никогда) и далее в
  начале каждого цикла обновления.
- **read timeout:** `DockerApi.request` жёстко ставит 60 000 мс (`DockerApi.kt:157`). Для `stop`/`restart` таймаут
  вычисляется как `max(60s, (effectiveStopTimeout + 15)s)`, где `effectiveStopTimeout` — явный override либо
  `Config.StopTimeout` из inspect'а.

## What Goes Where

- **Implementation Steps** (`[ ]`): всё, что делается в этом репозитории — код, тесты, фикстуры, документация.
- **Post-Completion** (без чекбоксов): перезапись фикстур на другой версии движка, ручная проверка на реальном
  стеке, релизные шаги.

## Implementation Steps

### Task 1: Сделать replay-обвязку способной падать

**Files:**
- Modify: `src/test/kotlin/io/heapy/kodkod/OpLoggingClient.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/DockerReplayTest.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/ReplayDockerTransportTest.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/DockerRecording.kt`
- Create: `src/test/kotlin/io/heapy/kodkod/OpLoggingClientTest.kt` (➕ тесты на новое различение
  «сделал»/«попытался» в `OpLoggingClient` — в replay-сценариях все вызовы успешны, поэтому маркер `!`
  иначе остался бы непокрытым)

- [x] мета-тест на строгость: собрать список `RecordedExchange` **в памяти** (как уже делает
      `ReplayDockerTransportTest`, с синтетическим `bodyLoader`), выбросить из него один обмен и убедиться, что
      сценарий падает — сейчас он зелёный, потому что исключение проглатывается кодом под тестом
- [x] в `OpLoggingClient` писать op **после** успешного делегирования, а неуспешный фиксировать отдельной пометкой
      (`create!:web`), чтобы «сделал» и «попытался» не сливались; `created` заполнять так же
- [x] в `ReplayDockerTransport` считать промахи (`NoSuchRecordedExchangeException`/`RecordedExchangesExhausted`)
      в счётчик, доступный тесту
- [x] в `DockerReplayTest` после каждого сценария проверять `isFullyConsumed()` и нулевой счётчик промахов
- [x] заменить `?: return emptyList()` на явный отказ при отсутствии `index.json` (корпус закоммичен — пустой
      прогон это поломка, а не «ещё не засеяли»)
- [x] сделать `update-noop` невакуумным: проверять, что сценарий реально проинспектировал контейнер и признал его
      актуальным, а не отработал на пустом списке
- [x] прогнать тесты — мета-тест красный до фикса, вся сюита зелёная после

### Task 2: Безопасный рекордер фикстур

**Files:**
- Modify: `src/e2eTest/kotlin/io/heapy/kodkod/e2e/DockerFixtureRecorder.kt`
- Create: `src/e2eTest/kotlin/io/heapy/kodkod/e2e/FixtureWriter.kt` (➕ запись корпуса вынесена из рекордера,
  чтобы атомарность подмены тестировалась без Docker — через инъекцию падающего `writeBytes`)
- Create: `src/e2eTest/kotlin/io/heapy/kodkod/e2e/FixtureWriterTest.kt` (➕ тесты живут в `e2eTest`, потому что
  `test` не видит исходники `e2eTest`; сами тесты Docker не требуют — `./gradlew e2eTest --tests '*FixtureWriterTest*'`)

- [x] тест/проверка на баг: запуск рекордера без `-Pkodkod.e2e.useCurrentDocker=true` сейчас молча уходит на
      **хостовый** демон, хотя харнесс поднял DinD (транспорт kodkod — только unix-сокет, `DOCKER_HOST=tcp://…`
      он использовать не может)
- [x] падать с внятным сообщением, когда DinD активен (выставлен `DOCKER_HOST`) — рекордер требует
      `-Pkodkod.e2e.useCurrentDocker=true`, а не подменяет демон втихую
- [x] писать сценарий во временный каталог и подменять закоммиченный только после успешной записи (убрать
      `deleteRecursively()` до записи), чтобы неудачный прогон не оставлял заглушку в индексе
- [x] тест на то, что при исключении внутри записи существующая фикстура остаётся нетронутой
- [x] прогнать `./gradlew test` и записывающий прогон вручную — корпус не изменился
      (полный записывающий прогон пропущен: не автоматизируется — локальный движок 29.6.2 ≠ закоммиченного
      лейбла `engine-29.5.2`, и прогон создал бы второй лейбл вместо проверки «корпус не изменился»;
      финальная перезапись остаётся за задачей 20. Вместо него прогнан реальный запуск рекордера без флага —
      падает на `@BeforeAll` до старта DinD с внятным сообщением)

### Task 3: Тестовая инфраструктура: сбои, фильтры листинга, инъекция времени

**Files:**
- Modify: `src/test/kotlin/io/heapy/kodkod/FakeDockerClient.kt`
- Create: `src/main/kotlin/io/heapy/kodkod/Time.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/Updater.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/Autoheal.kt`
- Create: `src/test/kotlin/io/heapy/kodkod/FakeClock.kt` (➕ фейковые часы живут в test source set — в main
  их место заняли бы test-only классы, ровно та проблема, что уже отмечена у record/replay)
- Create: `src/test/kotlin/io/heapy/kodkod/FakeDockerClientTest.kt` (➕ тесты на сам фейк)
- Create: `src/test/kotlin/io/heapy/kodkod/TimeTest.kt` (➕ тесты на `FakeClock` и на реальные дефолты)
- Modify: `src/test/kotlin/io/heapy/kodkod/UpdaterTest.kt` (ассерты на ops упавших вызовов)

- [x] `FakeDockerClient.listContainers` должен уважать `all` и фильтры `status`/`label`/`health` (сейчас возвращает
      `listed` целиком) — без этого задачи 13/15/18 не могут доказать «ищем по всему демону, а не по мониторимому
      набору»
- [x] добавить `failRemove`/`failRename` (ветки rollback не покрыты вообще) и запись аргументов:
      `stopTimeouts`/`restartTimeouts` (включая `null`), `removedImages`, `platforms`
      (`platforms` отложен в задачу 8: у `pull`/`create` ещё нет параметра `platform`, записывать нечего;
      `stopTimeouts`/`restartTimeouts` уже типизированы как `MutableList<Int?>` под задачу 9)
- [x] добавить модель состояния: `startedThenExits` (после `start` inspect показывает `Running=false, ExitCode!=0`)
      и `health` — нужно для liveness-гейта и autoheal-тестов
- [x] писать `ops` после успешного вызова и добавлять маркер `create!:`/`start!:` при исключении — единый формат с
      `OpLoggingClient`; **обновить** `UpdaterTest.a_failed_create_rolls_back…` и `a_failed_start_removes…`,
      которые сейчас ассертят на ops от упавших вызовов
- [x] ввести `TimeSource`/`Sleeper` в `Time.kt` с реальным дефолтом; `Updater` и `Autoheal` принимают их
      параметрами конструктора со значением по умолчанию (env-конфиг остаётся в `Config.fromEnv`)
- [x] тесты на сам фейк: фильтр `status=running` отсекает остановленные, `all=true` возвращает всё, фейковые часы
      двигаются только вручную
- [x] прогнать тесты — вся существующая сюита зелёная

### Task 4: Перештамповывать `com.docker.compose.image` (исходный баг)

**Files:**
- Modify: `src/main/kotlin/io/heapy/kodkod/ImageDefaults.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/Updater.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/ImageDefaultsTest.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/UpdaterTest.kt`

- [x] тест-на-баг в `ImageDefaultsTest`: create-body для stale-контейнера с лейблом
      `com.docker.compose.image=sha256:OLD` и новым образом `sha256:NEW` обязан содержать `sha256:NEW`
- [x] тест-на-баг в `UpdaterTest`: после `runOnce()` тело `create` содержит новый image id в лейбле
      (два теста: pull-ветка и registry-digest-ветка)
- [x] добавить `Target.newImageId`, заполнять его в обеих ветках `markStale` (registry-digest и pull)
- [x] прокинуть `newImageId` в `buildCreateBody`/`buildContainerConfig`: при `stale == true` лейбл переписывается,
      иначе копируется дословно (ветки «id неизвестен» не существует — без нового id цель не становится stale)
- [x] зафиксировать в комментарии решение по `com.docker.compose.config-hash`: копируется дословно и почему
      (KDoc `restampComposeImage` в `ImageDefaults.kt`)
- [x] тесты на граничные случаи: контейнер без compose-лейблов; recreate по зависимости (не stale) — лейбл
      сохраняется байт-в-байт
- [x] прогнать тесты — до фикса красные, после зелёные (редность проверена нейтрализацией `restampComposeImage`:
      3 теста-на-баг падают, оба граничных остаются зелёными; затем полная сюита 96/96 зелёная)

### Task 5: E2E — повторный `compose up` не должен пересоздавать сервис

**Files:**
- Modify: `src/e2eTest/kotlin/io/heapy/kodkod/e2e/KodkodE2eTest.kt`
- Modify: `e2e/compose.update.yml` (при необходимости отдельный сервис)

- [x] новый e2e-сценарий: `compose up -d` → kodkod обновляет сервис → повторный `compose up -d` без изменений
      compose-файла (`composeUpAfterKodkodUpdateKeepsTheContainer`, на существующем `compose.update.yml` —
      отдельный сервис не понадобился: `app` уже не переопределяет ничего и несёт compose-лейблы)
- [x] ассерт: id контейнера после второго `compose up` совпадает с id, созданным kodkod'ом (пересоздания не было)
- [x] ассерт на вывод compose: сервис в состоянии `Running`, а не `Recreated`
- [x] прогнать `./gradlew e2eTest --tests '*composeUp*'` — зелёный (проверка «красный до задачи 4» делается
      вручную и тест не гейтит)

### Task 6: Наследовать анонимные volume при recreate

**Files:**
- Modify: `src/main/kotlin/io/heapy/kodkod/ImageDefaults.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/Updater.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/ImageDefaultsTest.kt`
- Modify: `src/e2eTest/kotlin/io/heapy/kodkod/e2e/KodkodE2eTest.kt`
- Modify: `e2e/compose.update.yml`

- [x] тест-на-баг №1 (compose `volumes: ["/data"]`): `HostConfig.Mounts=[{Type:volume,Source:"",Target:"/data"}]`
      + top-level `Mounts=[{Type:volume,Name:"vol123",Destination:"/data"}]` → create-body обязан содержать
      `Source: "vol123"`
- [x] тест-на-баг №2 (`VOLUME` в образе / `docker run -v /data`): в `HostConfig` записи нет вовсе, том виден только
      в top-level `Mounts` → create-body обязан получить синтезированную запись `HostConfig.Mounts`
- [x] написать чистую функцию `resolveMounts(inspectMounts, hostConfig)`: идёт **от top-level `Mounts[]`**,
      синтезирует/дополняет записи `HostConfig.Mounts`, эмитит только `Type`/`Source`/`Target`/`ReadOnly`
      (остальные ключи top-level невалидны для `/containers/create` и дадут 400)
- [x] вызвать её в `Updater.recreate` рядом с `resolveHostConfig`
- [x] тесты на граничные случаи: legacy `Binds` покрывает тот же `Destination` (дубликат не создаём), именованный
      том (не меняется, включая `VolumeOptions`), bind-mount и tmpfs (не трогаем), запись без `Name` (пропускаем),
      том `RW=false` → `ReadOnly=true`
- [x] e2e: сервис с `volumes: ["/data"]`, запись файла в `/data`, обновление образа kodkod'ом, ассерт что файл жив
      и имя тома то же (сервис `vol` в `compose.update.yml`,
      `anonymousVolumeIsInheritedByTheRecreatedContainer`)
- [x] прогнать `./gradlew test` и целевой e2e — зелёные (unit-тесты подтверждены красными на заглушке
      `resolveMounts`; «красный до фикса» для e2e, как и в задаче 5, проверяется вручную и тест не гейтит)

### Task 7: Сохранять `MacAddress` и `GwPriority` в endpoint'е

**Files:**
- Modify: `src/main/kotlin/io/heapy/kodkod/Updater.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/UpdaterTest.kt`
- Modify: `e2e/compose.multinet.yml`
- Modify: `src/e2eTest/kotlin/io/heapy/kodkod/e2e/KodkodE2eTest.kt` (➕ e2e-ассерт по MAC живёт здесь, в исходном
  списке файлов задачи он был пропущен)

- [x] тест-на-баг: контейнер с непустым `Config.MacAddress` и `GwPriority` в endpoint'е → `cleanEndpoint` обязан
      сохранить оба поля
- [x] расширить `cleanEndpoint`: `GwPriority` всегда; `MacAddress` — только когда `Config.MacAddress` непустой
      (в Docker ≥26 endpoint-MAC генерируется случайно, тащить его в новый контейнер не нужно)
- [x] тест на противоположный случай: `Config.MacAddress == null` → MAC в create-body не попадает
      (плюс третий кейс: `Config.MacAddress == ""` тоже считается «не задан»)
- [x] добавить в `e2e/compose.multinet.yml` сервис с явным `mac_address:`, чтобы у правила был живой источник
      (сервис `mac`, один network `neta`)
- [x] e2e-ассерт: после обновления MAC сервиса не изменился — ассерт условный: ⚠️ **эмпирически проверено на
      движке 29.6.2 (и через `/v1.43`, `/v1.46`, и без версии): `Config.MacAddress` в inspect'е отсутствует
      вовсе даже при явном `--mac-address`/`mac_address:`** (поле удалено из ответа в Docker ≥27, а не «пустое»).
      То есть на современных движках правило из Technical Details никогда не срабатывает и MAC не переносится —
      это осознанно безопасная сторона (случайный MAC не пинуется), но исходную находку (`TODO` #5) оно на
      29.x не закрывает. Поэтому e2e читает `Config.MacAddress` и, если движок его отдаёт, требует неизменности
      MAC; иначе требует, чтобы у замены был MAC вообще. Если понадобится реально переносить явный MAC на
      Docker ≥27, нужен другой дискриминатор (в inspect'е его нет) — отдельная задача, вне объёма этой
- [x] снять пункт 5 из `TODO.md` — пункты 6–11 перенумерованы в 5–10. Ссылки на номера в задачах 16 и 21 и в
      Post-Completion читать по новой нумерации: per-registry auth 6→5, backoff 7→6, версии 8→7, CHANGELOG 9→8,
      `--link` 10→9, image-id-pin 11→10; URL-encoding остался 4
- [x] прогнать тесты и целевой e2e

### Task 8: Передавать `platform` в pull и create

**Files:**
- Modify: `src/main/kotlin/io/heapy/kodkod/DockerClient.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/DockerApi.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/Updater.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/UpdaterTest.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/DockerApiParseTest.kt`

- [x] тест-на-баг: контейнер с `ImageManifestDescriptor.platform={architecture:amd64,os:linux}` → `pull` и `create`
      получают `platform="linux/amd64"`
- [x] добавить `platform: String?` в `pull`/`create` интерфейса `DockerClient` и `?platform=` в `DockerApi`
      (параметр не добавляется вовсе, когда `null`)
- [x] извлекать платформу из inspect'а в `Updater` как `os/arch` **без `variant`**, хранить в `Target`
      (`Updater.imagePlatform()`); `FakeDockerClient.platforms` пишет аргумент обоих вызовов
- [x] тесты: нет `ImageManifestDescriptor` (старый движок) → параметр не отправляется; у старого образа
      `variant: v8`, у нового его нет → pull всё равно проходит (variant не пинуется)
- [x] перезаписать фикстуры (пути запросов изменились — новые ключи replay) и отревьюить diff — записан новый
      лейбл `engine-29.6.2_compose-5.3.1`, устаревший `engine-29.5.2_compose-5.1.4` удалён из корпуса и
      `index.json` (его нельзя перезаписать без ровно той версии движка, а старые пути `create` без
      `?platform=` навсегда красили replay). Число обменов по сценариям не изменилось (14/5/2/20),
      в `create` появился `&platform=linux%2Farm64`
- [x] прогнать тесты

### Task 9: Уважать `Config.StopTimeout` и честный read timeout

**Files:**
- Modify: `src/main/kotlin/io/heapy/kodkod/DockerClient.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/DockerApi.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/Updater.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/Autoheal.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/Config.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/UpdaterTest.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/AutohealTest.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/ConfigTest.kt`

- [x] сменить сигнатуры на `stop(id, timeout: Int?)` / `restart(id, timeout: Int?)`; в `DockerApi` не добавлять
      `?t=`, когда `null` (порядок «сигнатура → тест → фикс», см. исключение в Development Approach) —
      добавлен третий параметр `expectedStopSeconds: Int? = timeout`: он не уходит на демон, а только
      определяет наш read timeout (`?t=` и «сколько ждать ответа» — разные величины, как только `?t=` не
      отправляется вовсе)
- [x] тест: контейнер с `Config.StopTimeout=30` и без kodkod-лейбла → `stop`/`restart` вызывается с `null`, а не с 10
      (`UpdaterTest.without_an_override_the_container_decides_its_own_stop_timeout`,
      `AutohealTest.without_an_override_the_container_decides_its_own_stop_timeout`)
- [x] отличать «явно выставленный `KODKOD_STOP_TIMEOUT`» от дефолта (`defaultStopTimeout: Int?` в `Config`);
      `stopTimeout()` в `Updater` и его аналог в `Autoheal` возвращают `null`, если нет ни лейбла, ни env
- [x] per-call read timeout для `stop`/`restart`: `max(60s, (effectiveStopTimeout + 15)s)`, где
      `effectiveStopTimeout` — override либо `Config.StopTimeout` из inspect'а (`Updater` его уже имеет);
      для `Autoheal` зафиксировать компромисс в комментарии: без inspect'а он использует пол в 60s
- [x] тесты: лейбл побеждает `StopTimeout`; env побеждает `StopTimeout`; read timeout считается верно для 120s
      (`DockerApiParseTest.a_long_graceful_window_stretches_the_read_timeout` → 135 000 мс)
- [x] перезаписать фикстуры (`?t=10` уходит из путей `stop`/`restart` — ключи replay меняются во всех сценариях) —
      перезаписан тот же лейбл `engine-29.6.2_compose-5.3.1`; нормализованная сверка манифестов: число
      обменов не изменилось (2/20/5/14), единственная разница — исчезнувший `?t=10` у `stop`/`restart`
- [x] прогнать тесты

### Task 10: Liveness-гейт перед удалением старого контейнера

**Files:**
- Modify: `src/main/kotlin/io/heapy/kodkod/Updater.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/Config.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/UpdaterTest.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/ConfigTest.kt`
- Modify: `e2e/compose.rollback.yml`
- Modify: `src/e2eTest/kotlin/io/heapy/kodkod/e2e/KodkodE2eTest.kt`

- [x] тест-на-баг: новый контейнер стартует и сразу выходит с кодом 1 → старый контейнер и старый образ **не**
      удаляются, происходит rollback (использует `startedThenExits` и фейковые часы из задачи 3) —
      `UpdaterTest.a_replacement_that_starts_and_exits_is_rolled_back_and_destroys_nothing`; `FakeDockerClient.create`
      теперь регистрирует созданный контейнер в `containers` (иначе гейту нечего инспектировать), не перетирая
      payload, который тест положил заранее
- [x] добавить `KODKOD_UPDATE_VERIFY_SECONDS` (default 15) и `KODKOD_UPDATE_VERIFY_HEALTH` (default true)
      (отрицательное окно нормализуется в 0 = одна проба)
- [x] после `start(newId)` опрашивать inspect каждые ~500 мс: успех = 3 подряд удачных пробы (ранний выход);
      провал = `Running=false`, `Restarting=true` или `Health=unhealthy`; `Health=starting` в конце окна — успех
      (`Updater.verifyStarted`: `Restarting` проверяется раньше `Running`, потому что контейнер между попытками
      restart-политики отдаёт `Running=false`; непрочитанный inspect считается «ещё не устоялся», а не провалом —
      блип на сокете не доказательство поломки)
- [x] удаление старого контейнера и образа перенести за успешный гейт (гейт стоит внутри того же `try`, что
      снимает замену, поэтому провал идёт по существующему пути rollback)
- [x] тесты: контейнер жив → ранний выход без ожидания всего окна; restart-loop → rollback; `unhealthy` при
      `VERIFY_HEALTH=false` → успех; `starting` до конца окна → успех; конфиг-тесты на новые переменные
      (плюс `unhealthy` при `VERIFY_HEALTH=true` → rollback; весь `UpdaterTest` переведён на инъекцию `FakeClock`
      через хелпер `updater(...)`, чтобы юнит-сюита не спала на гейте)
- [x] e2e: вариант `compose.rollback.yml` «стартует и падает через секунду» — старый контейнер вернулся
      (`e2e/testapp/Dockerfile.crasher` + `aReplacementThatStartsAndThenDiesIsRolledBack`; смерть через 0.2s —
      контейнер, доживший до третьей пробы, был бы принят как удачное обновление; в compose добавлен
      `KODKOD_UPDATE_VERIFY_SECONDS: "8"`)
- [x] перезаписать фикстуры (появился `GET /containers/<newId>/json` после `start`) — перезаписан тот же лейбл
      `engine-29.6.2_compose-5.3.1`; нормализованная сверка манифестов: в `update-recreate` и `deps-ordered`
      появилось ровно по 3 новых `GET /containers/<newId>/json` между `start` и `DELETE /containers/<old>`,
      `update-noop` и `autoheal-restart` не изменились. Конфиг рекордера и `DockerReplayTest` получили
      `KODKOD_UPDATE_VERIFY_HEALTH=false`: с включённой проверкой здоровья число проб — это гонка между
      интервалом проб и healthcheck'ом новой замены, и запись, которую replay смог бы повторить только случайно
- [x] прогнать тесты и целевой e2e (`./gradlew test` 133/133; `--tests '*aReplacementThatStartsAndThenDies*'`,
      `'*failedRecreateRollsBackToRunningOriginal*'`, `'*updatePullsAndAdoptsNewImageDefaults*'` — зелёные;
      краснота новых тестов подтверждена нейтрализацией вызова `verifyStarted`: падают 5 из 6, шестой —
      негативный контроль `VERIFY_HEALTH=false`, он и должен остаться зелёным)

### Task 11: Не удалять образ, у которого остались чужие теги

**Files:**
- Modify: `src/main/kotlin/io/heapy/kodkod/Updater.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/UpdaterTest.kt`

- [x] тест-на-баг: у старого образа остался **ровно один** пользовательский тег (`app:1.26`, после того как
      `app:latest` уехал на новый образ) → `removeImage` не вызывается. Именно этот случай опасен: удаление
      проходит успешно и снимает тег; вариант с двумя тегами Docker и так отбивает 409
      (`a_single_remaining_user_tag_saves_the_old_image_from_the_prune`)
- [x] перед удалением инспектировать старый образ и пропускать удаление, если остались `RepoTags`, отличные от
      обновляемого ref'а; писать в лог, почему пропустили (`Updater.pruneOldImage`; ref нормализуется через
      `normalizeImageRef`, чтобы `app` и `app:latest` сравнивались как одно и то же)
- [x] заменить немой `runCatching` на логирование фактической ошибки удаления
- [x] тесты: dangling-образ (нет `RepoTags`) → удаляется; только обновляемый тег → удаляется; inspect образа упал
      → удаление пропускается (консервативно)
- [x] перезаписать фикстуры (перед `DELETE /images/...` появился дополнительный inspect образа). Записанный
      реальным движком старый образ несёт `RepoTags:[127.0.0.1:5000/testapp:v1]` (рекордер публикует каждый
      вариант и как `:latest`, и как `:vN`), поэтому в корпусе `DELETE /images/...` теперь честно отсутствует —
      `DockerReplayTest` это и утверждает
- [x] прогнать тесты (`./gradlew test` 137/137, `compileE2eTestKotlin` — зелёные; краснота двух новых тестов
      подтверждена до правки)

### Task 12: Надёжный rollback и не проглоченные ошибки старта

**Files:**
- Modify: `src/main/kotlin/io/heapy/kodkod/Updater.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/UpdaterTest.kt`

- [x] тест-на-баг: `remove(newId)` падает → `rename(old → name)` получает 409 → сервис остаётся под именем
      `*_kodkod_old_*` (требует `failRemove` из задачи 3)
- [x] в rollback: при конфликте имени сначала убрать/переименовать мешающий контейнер, затем повторить `rename`;
      логировать каждую неудачу вместо молчаливого `runCatching`
- [x] после rollback проверять фактическое состояние (inspect: запущен и под исходным именем) и писать ERROR,
      если восстановиться не удалось
- [x] в bring-back-проходе `runOnce` добавить ограниченный retry для `api.start` (3 попытки с паузой через
      `Sleeper`) и ERROR-лог «контейнер остался остановленным», если не помогло
- [x] тесты: 409 на rename → сервис в итоге под правильным именем; start падает дважды, третий успешен → зелёный
      путь; start падает всегда → ERROR (контейнер подхватит reconcile из задачи 13)
- [x] прогнать тесты (`./gradlew test` 142/142 и `compileE2eTestKotlin` — зелёные; краснота четырёх новых
      тестов подтверждена до правки)

### Task 13: Корректный шатдаун и reconcile осиротевших бэкапов

**Files:**
- Modify: `src/main/kotlin/io/heapy/kodkod/Main.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/Updater.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/Config.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/UpdaterTest.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/ConfigTest.kt`
- Modify: `src/e2eTest/kotlin/io/heapy/kodkod/e2e/KodkodE2eTest.kt`

- [ ] тест-на-баг: rollback на потоке с установленным флагом прерывания обязан отработать (сейчас NIO падает
      мгновенно и обе операции проглатываются)
- [ ] в `rollback()` снимать флаг прерывания (`Thread.interrupted()`) перед вызовами и восстанавливать после
- [ ] в shutdown-хуке: `scheduler.shutdown()` + `awaitTermination(KODKOD_SHUTDOWN_GRACE, default 30s)`, затем
      `shutdownNow()`; добавить переменную в `Config`
- [ ] реализовать reconcile: поиск по `all=true` контейнеров с именем `<name>_kodkod_old_<12 hex собственного id>`
      (строгий дискриминатор — похожие имена не трогаем); нет замены → вернуть имя и запустить, замена работает →
      удалить бэкап. Выполнять на старте процесса **независимо от `KODKOD_UPDATE_ENABLED`** и в начале каждого цикла
- [ ] тесты: осиротевший бэкап без замены → переименован и запущен; бэкап при работающей замене → удалён;
      контейнер с суффиксом от чужого id не трогается; reconcile работает при выключенном апдейтере;
      конфиг-тест на `KODKOD_SHUTDOWN_GRACE`
- [ ] e2e: убить kodkod между `rename` и `start` (на базе `compose.rollback.yml`), перезапустить, убедиться что
      сервис вернулся
- [ ] перезаписать фикстуры (в начале цикла появился `all=true` листинг)
- [ ] прогнать тесты и целевой e2e; снять пункт 1 из `TODO.md`

### Task 14: Память о неподнимающемся образе

**Files:**
- Modify: `src/main/kotlin/io/heapy/kodkod/Updater.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/Config.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/UpdaterTest.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/ConfigTest.kt`

- [ ] тест-на-баг: два цикла подряд с неподнимающимся образом → второй цикл не должен ни останавливать, ни
      переименовывать контейнер (фейковые часы из задачи 3)
- [ ] добавить память известных плохих пар `(containerId, imageId) -> времяПоследнейПопытки`; запись сбрасывается,
      когда у тега появляется другой image id, и при успешном recreate
- [ ] добавить `KODKOD_UPDATE_FAILURE_COOLDOWN` (default 21600 = 6ч) — один плоский cooldown, без экспоненты
- [ ] логировать пропуск явно («образ sha256:… уже провалился, следующая попытка не раньше …»)
- [ ] тесты: cooldown истёк → попытка повторяется; вышел новый image id → память сброшена, попытка сразу;
      успешный recreate очищает запись
- [ ] прогнать тесты

### Task 15: Общий резолвер зависимых и netns-потребители при autoheal

**Files:**
- Create: `src/main/kotlin/io/heapy/kodkod/Dependents.kt`
- Create: `src/test/kotlin/io/heapy/kodkod/DependentsTest.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/Autoheal.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/AutohealTest.kt`

- [ ] тест-на-баг: рестарт контейнера, к чьему netns подключён другой контейнер, обязан перезапустить и
      потребителя (сейчас потребитель остаётся с мёртвым namespace и продолжает считаться `Running`)
- [ ] вынести поиск зависимых в `Dependents.kt`: netns-потребители (`HostConfig.NetworkMode=container:<id|name>`)
      и legacy `--link`; поиск по `all=true`, а не по мониторимому набору
- [ ] в `Autoheal` после успешного рестарта перезапускать найденных потребителей
- [ ] тесты `DependentsTest`: потребитель по id, по имени, по короткому id; `--link`; контейнер без потребителей
- [ ] тесты `AutohealTest`: порядок операций, потребитель вне мониторимого набора тоже перезапускается
- [ ] перезаписать фикстуры (в autoheal-сценарии появился `all=true` листинг)
- [ ] прогнать тесты

### Task 16: Backoff для «хлопающего» unhealthy

**Files:**
- Modify: `src/main/kotlin/io/heapy/kodkod/Autoheal.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/Config.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/AutohealTest.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/ConfigTest.kt`

- [ ] тест-на-баг: контейнер, остающийся unhealthy, не должен рестартоваться каждые 30 секунд бесконечно
      (фейковые часы из задачи 3)
- [ ] добавить экспоненциальный backoff на контейнер с потолком `KODKOD_AUTOHEAL_MAX_INTERVAL` (default 3600)
- [ ] сбрасывать счётчик, когда контейнер вернулся в healthy или исчез
- [ ] тесты: интервал растёт 30 → 60 → 120 …; упирается в потолок; сбрасывается после выздоровления; разные
      контейнеры считаются независимо; конфиг-тест на новую переменную
- [ ] прогнать тесты; снять пункт 7 из `TODO.md`

### Task 17: Честный `depends_on` — condition (и опционально restart)

**Files:**
- Modify: `src/main/kotlin/io/heapy/kodkod/Updater.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/Config.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/UpdaterGraphTest.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/UpdaterTest.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/ConfigTest.kt`
- Modify: `e2e/compose.deps.yml`

- [ ] тест-на-баг: `db:service_healthy:true` → `web` стартует только после того, как `db` стал healthy
      (сейчас третье и второе поля отбрасываются `substringBefore(':')`)
- [ ] тест-на-баг: при `KODKOD_RESPECT_DEPENDS_ON_RESTART=true` и `db:service_started:false` → `web` не рестартуется
- [ ] распарсить все три поля лейбла в `resolveLinks`, сохранить условие и флаг restart на ребро
- [ ] соблюдать `condition: service_healthy` всегда: ждать здоровья зависимости перед стартом зависимых с
      ограничением `KODKOD_DEPENDENCY_HEALTH_TIMEOUT` (default 120s) и понятным логом при истечении
- [ ] флаг `restart` подчиняется только при `KODKOD_RESPECT_DEPENDS_ON_RESTART=true` (default `false` — см.
      решение в Solution Overview) и **только** для `linkedToRestarting`, никогда для `linkedToRecreate`
- [ ] добавить в `e2e/compose.deps.yml` сервис **без** fallback-лейбла `kodkod.depends-on` (сейчас `web` несёт и
      его, поэтому ребро сохраняется независимо от парсинга и сценарий не проверяет новый код)
- [ ] тесты: `service_started` не ждёт; `service_healthy` ждёт; таймаут ожидания логируется и не роняет цикл;
      двухполевой формат лейбла не ломает парсер; `restart=false` не подавляет netns-ребро
- [ ] прогнать тесты (ops в replay-сценарии `deps-ordered` не меняются — дефолт `RESPECT_DEPENDS_ON_RESTART=false`)

### Task 18: Create-time зависимые за пределами мониторимого набора

**Files:**
- Modify: `src/main/kotlin/io/heapy/kodkod/Updater.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/Dependents.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/UpdaterTest.kt`
- Modify: `src/e2eTest/kotlin/io/heapy/kodkod/e2e/KodkodE2eTest.kt`
- Modify: `e2e/compose.container-mode.yml`

- [ ] тест-на-баг: немаркированный sidecar с `network_mode: container:app` при обновлении `app` обязан быть
      пересоздан/перезапущен, а не остаться с мёртвым netns (требует фильтр-aware листинг из задачи 3)
- [ ] использовать `Dependents.kt` из задачи 15 в `Updater`: искать create-time зависимых по всему демону
- [ ] пересоздавать/перезапускать найденных после замены провайдера; если пересоздать нельзя — явный WARN
      вместо тишины
- [ ] тесты: sidecar внутри мониторимого набора (как раньше), sidecar снаружи, legacy `--link` снаружи
- [ ] e2e: расширить `compose.container-mode.yml` немаркированным потребителем, проверить наличие сети после
      обновления провайдера
- [ ] прогнать тесты и целевой e2e

### Task 19: Разделить цикл обновления на чтение и мутацию

**Files:**
- Modify: `src/main/kotlin/io/heapy/kodkod/Updater.kt`
- Modify: `src/main/kotlin/io/heapy/kodkod/Main.kt`
- Modify: `src/test/kotlin/io/heapy/kodkod/UpdaterTest.kt`

- [ ] тест-на-баг: фаза планирования не должна выполнять мутирующих операций — ассерт «в `ops` после `plan()` нет
      ничего, кроме `pull:`» (сам pull остаётся в фазе чтения и пишется в `ops` обеими обвязками)
- [ ] разделить `runOnce()` на `plan(): UpdatePlan` (только чтение) и `apply(plan)` (мутации)
- [ ] в `Main` брать `cycleLock` только вокруг `apply`; в комментарии зафиксировать, почему pull больше не держит
      лок и каким верхним пределом ограничены ожидания внутри `apply` (liveness-гейт из задачи 10 и
      `service_healthy` из задачи 17)
- [ ] в начале `apply` перепроверять актуальность плана (контейнер существует, image id не изменился) — состояние
      могло измениться, пока качался образ
- [ ] тесты: план на устаревшем состоянии отбрасывается с логом; обычный путь не изменился
- [ ] перезаписать фикстуры, если порядок или состав запросов изменился; иначе явно убедиться, что не изменился
- [ ] прогнать тесты

### Task 20: Verify acceptance criteria

- [ ] проверить, что все 15 находок ревью закрыты — пройтись по списку и сопоставить с тестами
- [ ] проверить граничные случаи: контейнер без compose-лейблов, digest-pinned образ, host/none network mode,
      контейнер без healthcheck
- [ ] прогнать полный тестовый набор: `./gradlew test`
- [ ] прогнать e2e: `./gradlew e2eTest`
- [ ] финальная перезапись фикстур на актуальном движке и зелёный replay:
      `./gradlew e2eTest -Pkodkod.e2e.useCurrentDocker=true -Pkodkod.e2e.record=true --tests '*DockerFixtureRecorder*'`
- [ ] вручную проверить главный сценарий: `compose up` → обновление kodkod'ом → `compose up` без пересоздания

### Task 21: [Final] Update documentation

- [ ] привести README в соответствие с фактическим поведением: строки про сохранение `HostConfig/volumes/labels`
      (91), про rollback (92-93) и про удаление старого образа (73); добавить новые `KODKOD_*` в таблицу
- [ ] задокументировать решение по `depends_on.restart` и переменную `KODKOD_RESPECT_DEPENDS_ON_RESTART` в разделе
      «Ordering & dependencies»
- [ ] обновить `CHANGELOG.md` — сюда же попадают незаписанные пункты 9 из `TODO.md` (decoupling за `DockerClient`,
      JUnit-миграция e2e, CI job) и всё из этого плана
- [ ] почистить `TODO.md`: снять закрытые пункты (1, 5, 7, частично 10), оставшиеся — переформулировать без
      протухших ссылок на строки; добавить отложенный хвост из Post-Completion, включая `wise-mapping-shell.md`
- [ ] описать рекордер фикстур в `E2E_TESTING.md`, включая обязательные флаги и то, что он пишет в корпус
- [ ] обновить `CLAUDE.md`, если появились новые паттерны (правило «ops пишутся после успешного вызова»,
      правило «изменил путь запроса — перезаписал фикстуры»)
- [ ] перенести этот план в `docs/plans/completed/`

## Post-Completion

*Требует ручного вмешательства или внешних систем — без чекбоксов, информационно*

**Ручная проверка:**
- прогон на реальном стеке с базой данных: обновить образ Postgres и убедиться, что данные на месте (главный
  риск задачи 6 — потеря анонимного тома)
- проверка на arm64-хосте с `platform: linux/amd64`-пиннутым сервисом (задача 8)
- поведение при недоступном/зависшем registry: autoheal должен продолжать работать (задача 19)
- контейнер со `stop_grace_period: 120s`: убедиться, что kodkod дожидается, а не рвёт по read timeout (задача 9)

**Перезапись фикстур на других версиях:**
- корпус версионирован по `engine-<ver>_compose-<ver>`; желательно записать второй label на другой версии движка,
  чтобы replay ловил version drift, — но это требует второй машины/версии Docker

**Не входит в план (нижний приоритет, из отсечённого хвоста ревью и `TODO.md`):**
- `Config.bool()` трактует пустую переменную как `false` вместо дефолта
- `subtractImageDefaultsByKey` теряет пользовательские `Cmd`/`Entrypoint`/`User`/`WorkingDir`
- `pull` буферизует весь прогресс-стрим; `dechunk` молча обрезает хвост
- test-only record/replay классы `public` в main source set и попадают в production-jar
- `.dockerignore` тащит корпус фикстур в build context
- `DockerApi.create` бросает NPE без сообщения на отсутствующем `Id`
- `isSelf` использует `id.startsWith(selfId)`
- дублирование версии `kotlinx-serialization-json` и `updateConfig`/`autohealConfig` в тестах
- `TODO.md` #4: raw-ref без URL-кодирования в `inspectImage`/`removeImage`/`inspectDistribution` (задача 11
  добавляет ещё один вызов на тот же незакодированный путь)
- `TODO.md` #11: образ, пиннутый по `sha256:<id>` в `Config.Image`, уходит в `splitImageRef` и далее в pull как
  `repo=sha256, tag=<id>`
- `TODO.md` #3: нотификации/метрики/lifecycle-хуки
- `TODO.md` #6: per-registry auth вместо одного `KODKOD_REGISTRY_AUTH`
- `TODO.md` #8: расхождение версии 1.0.0 с тегами v0.2.0/v0.3.0
- `wise-mapping-shell.md` в корне репозитория — перенести в `docs/` или удалить

Эти пункты переносятся в `TODO.md` в задаче 21, чтобы не потеряться.
