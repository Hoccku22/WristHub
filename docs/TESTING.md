# Что проверено

Проверки при разработке выполнены 23 октября 2026 года. Физическое устройство —
**Xiaomi Watch 2 Pro, API 34**, прошивка `AW2A.240903.001.XM128S`.

| Проверка | Результат |
|---|---|
| Сборка APK телефона и часов | Успешно |
| Android Lint | Без ошибок; есть предупреждения |
| JVM: протокол и сохранение пакета | 10 тестов прошли |
| RFCOMM-сервер на реальной прошивке | Открытие и закрытие прошли |
| PDF и изображения | Рендеринг, быстрое переключение, повреждённый PDF и уменьшенное декодирование прошли |
| Геометрия интерфейса | Увеличенная страница, нижнее меню, реальные пропорции ленты и малые зазоры прошли |
| Синтетический rotary input | Переключение слайда прошло |
| Позиции и файлы после уничтожения процесса | Запись и чтение отдельными запусками прошли |
| Версия 1.2: автоприём | Открытие, фон, возврат и удержание экрана прошли |
| Версия 1.2: кольцо | 1/3, 2/3, выключение в настройках, сохранение и отсутствие изменения размера PDF прошли |

Три актуальных instrumentation-проверки версии 1.2 (`ForegroundReceiveTest` и два
метода `ReaderUiTest`) завершились `OK (3 tests)`. Снимки в README получены на
реальных часах с тестовым документом, а не через Bluetooth-передачу.

## Что эти проверки не подтверждают

- JVM-поток 20 МиБ не является проверкой радиоканала. Полная передача такого файла
  с телефона на часы при выключенных Wi-Fi и мобильных данных отдельно не зафиксирована.
- Открытие RFCOMM-сервера подтверждает наличие API, но не работу любых пар телефонов
  и часов. Пользователь сообщил об успешной работе приложения на своей паре устройств.
- Синтетический rotary input не заменяет ручной проверки физической головки.
- Другие модели часов и установка через Wear Installer 2 отдельно не тестировались.

## Повторение

```bash
./gradlew :shared:testDebugUnitTest :wear:assembleDebugAndroidTest
adb -s WATCH_SERIAL install -r wear/build/outputs/apk/debug/wear-debug.apk
adb -s WATCH_SERIAL install -r wear/build/outputs/apk/androidTest/debug/wear-debug-androidTest.apk
adb -s WATCH_SERIAL shell am instrument -w -e class dev.wristreader.wear.ForegroundReceiveTest,dev.wristreader.wear.ReaderUiTest dev.wristreader.wear.test/androidx.test.runner.AndroidJUnitRunner
```

Bluetooth должен быть включён, разрешение приложению — выдано. Тесты временно
удерживают экран включённым, используют свои документы и восстанавливают настройки.
`DeviceSmokeTest` можно запустить отдельно. В Windows используй `gradlew.bat`.

Для проверки сохранения после остановки процесса запускай фазы отдельно:

```bash
adb -s WATCH_SERIAL shell am instrument -w -e class 'dev.wristreader.wear.PositionPersistenceTest#aWritePositions' dev.wristreader.wear.test/androidx.test.runner.AndroidJUnitRunner
adb -s WATCH_SERIAL shell am force-stop dev.wristreader.wear
adb -s WATCH_SERIAL shell am instrument -w -e class 'dev.wristreader.wear.PositionPersistenceTest#bReadPositionsAfterProcessRestart' dev.wristreader.wear.test/androidx.test.runner.AndroidJUnitRunner
```

## Ручной чек-лист

- Один PDF, несколько изображений и реальный файл около 20 МиБ.
- Отправка при отключённых Wi-Fi и мобильных данных на обеих сторонах.
- Обрыв, отмена, повтор, нехватка памяти и потеря финального подтверждения.
- Сохранение позиции, перезагрузка часов, чтение при выключенном телефоне.
- Физическая головка, чувствительность, крупный системный шрифт и обе темы.
- Автоприём после возврата, ручное отключение, сворачивание посреди передачи.

Техническая основа Bluetooth-only — отсутствие сетевого транспорта и разрешений
INTERNET / Wi-Fi, использование BluetoothSocket RFCOMM. Подробности в [TRANSPORT.md](TRANSPORT.md).

## Полноэкранные изображения — 1.3

Сборка APK и Android Lint проверяются локально. Добавлен инструментальный тест
`ReaderUiTest#imagesFillScreenWithoutReaderChrome`: область изображения на весь
экран, заполнение верхней и нижней части, отсутствие кольца и подписей, меню по касанию.
На Xiaomi Watch 2 Pro (API 34) новый тест выполнен успешно: `OK (1 test)`.
Повторная проверка `readerModesTextAndPositionOnRoundWatch` также прошла: `OK (1 test)`.
