# TODO

Открытые пункты. Всё, что закрыто планом `docs/plans/…-recreate-fidelity-and-safety.md`, снято.

## Функциональность

1. Нотификации / метрики / lifecycle-хуки — единственная ось, где kodkod уступает watchtower и обоим autoheal'ам.
2. Один глобальный `KODKOD_REGISTRY_AUTH` вместо per-registry auth (у watchtower — per-registry).
3. Явно заданный MAC (`--mac-address` / compose `mac_address:`) не переносится на Docker ≥ 27: движок больше не
   отдаёт `Config.MacAddress` в inspect'е, а `NetworkSettings.Networks[*].MacAddress` заполнен всегда, поэтому
   отличить пользовательский MAC от сгенерированного нечем и kodkod осознанно не пинует ни один. Нужен другой
   дискриминатор (в inspect'е его нет) — возможно, сравнение с OUI Docker'а или отдельный kodkod-лейбл.
4. Образ, пиннутый по image id (`Config.Image = sha256:<id>`), не пропускается: skip есть только для
   `image@sha256:...`. Такой ref уходит в `splitImageRef` и дальше в registry/pull как `repo=sha256, tag=<id>`.
   Watchtower отсекает `strings.HasPrefix(imageName, "sha256:")` явно.
5. `--link` заявлен как поддерживаемая create-time зависимость, и зависимые теперь пересоздаются вместе с
   провайдером, но сам `HostConfig.Links` при recreate передаётся как есть — watchtower нормализует его в
   отдельном `GetCreateHostConfig`.
6. Версии разъехались: `build.gradle.kts` = 1.0.0, а теги — v0.2.0/v0.3.0. Решить: резать 1.0.0 или вернуть
   версию к реальности.

## Надёжность и корректность

7. `subtractImageDefaultsByKey` теряет пользовательские `Cmd`/`Entrypoint`/`User`/`WorkingDir`.
8. `pull` буферизует весь прогресс-стрим в памяти.
9. `DockerApi.create` бросает NPE без сообщения, когда в ответе нет `Id`.
10. `isSelf` использует `id.startsWith(selfId)` — префиксное сравнение id.

## Тесты и покрытие

11. Multi-arch digest не проверен: `UpdaterTest` гоняет одиночные digest'ы, e2e — single-arch busybox. Сравнение
    index-digest, скорее всего, корректно, но это классический watchtower-footgun без теста.
12. Health-ветка liveness-гейта (`unhealthy` / `starting`) покрыта только юнит-тестами: и рекордер, и e2e идут с
    `KODKOD_UPDATE_VERIFY_HEALTH=false`, потому что иначе число проб — гонка между интервалом проб и
    healthcheck'ом замены. Нужен детерминированный e2e (образ с предсказуемым healthcheck'ом).
13. Grace-хук шатдауна в `Main.kt` (`awaitTermination` → `shutdownNow`) не покрыт тестом: `main()` не
    разбирается на тестируемые части. Из него проверен только `ConfigTest.reads_the_shutdown_grace_period`.
    Чтобы покрыть — выделить планировщик и хук в отдельную тестируемую функцию.
14. Дублирование в тестах: хелперы `updateConfig`/`autohealConfig` рекордера и `config(...)` юнит-тестов.
