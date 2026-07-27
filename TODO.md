1. Startup-reconcile осиротевших *_kodkod_old_* (моя рек. #2). In-process rollback теперь покрыт тестами (failed-create / failed-start), но смерть процесса между rename(old→backup) и start(new) по-прежнему не восстанавливается.
   — самый содержательный из оставшихся.
2. Multi-arch digest не проверен. UpdaterTest гоняет одиночные digest'ы, e2e — single-arch busybox. Сравнение index-digest, скорее всего, корректно, но это классический watchtower-footgun без теста. — дёшево добавить кейс в    
   UpdaterTest.
3. Нотификации / метрики / lifecycle-хуки (моя рек. #4) — по-прежнему нет; единственная ось, где kodkod уступает всем трём эталонам.
4. URL-encoding ref в inspectImage/removeImage/inspectDistribution всё ещё raw (/images/$ref/json), хотя pull кодирует через enc() (моя рек. #5a).
5. Один глобальный KODKOD_REGISTRY_AUTH vs per-registry auth у watchtower.
6. Backoff для «хлопающего» unhealthy — нет (общий грех с обоими docker-autoheal).
7. Версии разъехались: build.gradle.kts = 1.0.0, а теги v0.2.0/v0.3.0 и CHANGELOG сверху [Unreleased]. Решить: резать 1.0.0 или вернуть версию к реальности.
8. CHANGELOG отстал: decoupling за DockerClient, JUnit-миграция e2e, CI-job, NUL-fix — не записаны в [Unreleased].
9. --link все еще заявлен как supported create-time dependency, но при recreate HostConfig.Links не переписывается. resolveLinks видит legacy links, но recreate передает HostConfig почти как есть, только меняет NetworkMode. У watchtower есть отдельный GetCreateHostConfig, который нормализует links перед create. См. Updater.kt (line 217), Updater.kt (line 407), reference container.go (line 350).
10. pinned image by image id sha256:... не пропускается. Сейчас skip только для image@sha256:...; если Config.Image будет sha256:<id>, код пойдет в splitImageRef, затем в registry/pull path как будто это repo:tag. Watchtower явно отсекает strings.HasPrefix(imageName, "sha256:"). См. Updater.kt (line 124), Updater.kt (line 471), reference client.go (line 361).