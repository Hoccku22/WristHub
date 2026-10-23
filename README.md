<p align="center">
  <img src="docs/images/hero.svg" alt="WristReader — Your documents. On your wrist." width="100%">
</p>

<p align="center">
  <a href="https://github.com/Hoccku22/WristReader/releases/latest"><img src="https://img.shields.io/github/v/release/Hoccku22/WristReader?color=42A5F5&amp;label=release" alt="Latest release"></a>
  <a href="https://github.com/Hoccku22/WristReader/actions/workflows/android.yml"><img src="https://github.com/Hoccku22/WristReader/actions/workflows/android.yml/badge.svg" alt="Android build"></a>
  <img src="https://img.shields.io/badge/transport-Bluetooth%20only-68E0CE" alt="Bluetooth only">
  <img src="https://img.shields.io/badge/platform-Android%20%2B%20Wear%20OS-9DB8CD" alt="Android and Wear OS">
</p>

<p align="center">
  <strong>PDF и фотографии со смартфона — на запястье. Читать можно без телефона.</strong><br>
  Читалка для круглого экрана Xiaomi Watch 2 Pro: свайпы, головка и три режима PDF.<br>
  Бесплатно, без рекламы, аккаунтов и облачного обмена файлами.
</p>

<p align="center">
  <a href="#скачать">Скачать</a> · <a href="#как-это-работает">Как это работает</a> · <a href="docs/INSTALL.md">Установка</a> · <a href="docs/BUILD.md">Сборка</a> · <a href="CHANGELOG.md">Что нового</a>
</p>

## Скачать

<p align="center">
  <a href="https://github.com/Hoccku22/WristReader/releases/latest/download/WristReader-phone.apk"><img src="docs/images/download-phone.svg" alt="Скачать APK для телефона" width="300"></a>
  <a href="https://github.com/Hoccku22/WristReader/releases/latest/download/WristReader-watch.apk"><img src="docs/images/download-watch.svg" alt="Скачать APK для часов" width="300"></a>
</p>

| Файл | Куда установить | Текущая версия |
|---|---|---|
| [WristReader-phone.apk](https://github.com/Hoccku22/WristReader/releases/latest/download/WristReader-phone.apk) | Смартфон, Android 8.0+ | 1.0 |
| [WristReader-watch.apk](https://github.com/Hoccku22/WristReader/releases/latest/download/WristReader-watch.apk) | Часы, Wear OS на Android 11+ | 1.2 |

Все версии и контрольные суммы — в [Releases](https://github.com/Hoccku22/WristReader/releases). APK подписаны debug-ключом: это те же сборки, которые проверялись на устройстве. Они предназначены для самостоятельной установки, не для Google Play.

> **Установить на часы с телефона можно без компьютера и команд.**
> Для этого подойдёт Wear Installer 2. Он использует беспроводную ADB-отладку, поэтому её всё равно нужно включить и выполнить сопряжение. [Пошаговая инструкция →](docs/INSTALL.md)

## На экране часов

<table>
  <tr>
    <td align="center"><img src="docs/images/slides.png" width="230" alt="Постраничный PDF и синее кольцо прогресса"><br><strong>Слайды</strong><br>Страница целиком, свайпы и масштаб</td>
    <td align="center"><img src="docs/images/feed.png" width="230" alt="Непрерывная лента PDF"><br><strong>Лента</strong><br>Прокрутка без лишних разрывов</td>
    <td align="center"><img src="docs/images/text.png" width="230" alt="Адаптивный текст с кириллицей"><br><strong>Текст</strong><br>Перенос строк под ширину экрана</td>
  </tr>
</table>

<p align="center"><sub>Реальные снимки Xiaomi Watch 2 Pro с демонстрационным документом. Баннер сверху — иллюстрация.</sub></p>

## Как это работает

```text
Телефон                     Bluetooth RFCOMM                       Часы
PDF / JPEG / PNG / WebP  ──────────────────────────────────►  Локальная библиотека
Подготовка текста PDF       Без интернета и Wi-Fi fallback       Чтение офлайн
                            SHA-256 + подтверждение
```

1. Открой WristReader на часах. Приём включится автоматически; при первом запуске разреши доступ к Bluetooth.
2. На телефоне выбери сопряжённые часы и файлы. Можно отправлять через системное меню «Поделиться».
3. Дождись **«Доставлен ✓»**. Открывай документы из библиотеки часов — дальше телефон не нужен.

Пока приложение открыто и приём включён, экран не засыпает. При сворачивании приём прекращается. Если свернуть часы во время передачи, её нужно повторить; незавершённый файл не попадёт в библиотеку.

## Для удобного чтения

| Возможность | Что умеет WristReader |
|---|---|
| **Три режима PDF** | Слайды, вертикальная лента и адаптивный текст |
| **Головка и жесты** | Перелистывание, прокрутка, увеличение и перемещение страницы |
| **Круглый экран** | Увеличенная область PDF, меню в нижнем сегменте, безопасные поля текста |
| **Кольцо прогресса** | Тонкая синяя дуга от «12 часов»; можно отключить в настройках |
| **Позиция чтения** | Страница и прокрутка сохраняются между запусками |
| **Настройки** | Шрифт, интервал, поля, тема, чувствительность головки и направление свайпов |
| **Изображения** | JPEG, PNG, WebP; масштабирование и последовательность слайдов |
| **Надёжная передача** | SHA-256, прогресс, отмена, повтор, проверка свободного места |

## Передача — только по Bluetooth

Используется прямой **Bluetooth Classic RFCOMM**. В приложении нет Data Layer, сетевого резерва, HTTP-клиента и разрешения `INTERNET`. Проверка контрольных сумм и сохранение выполняются до подтверждения доставки. [Как устроен транспорт →](docs/TRANSPORT.md)

Wi-Fi может понадобиться **для установки APK через отладку**. К передаче документов внутри WristReader это не относится.

## Совместимость и ограничения

- Основное проверенное устройство — **Xiaomi Watch 2 Pro, Android API 34**. Другие часы с Wear OS отдельно не проверялись; доступность RFCOMM зависит от прошивки.
- Извлечение текста не заменяет OCR: сканы можно смотреть как страницы, а сложные таблицы и колонки могут терять структуру.
- Максимум: оригинал **512 МиБ**, извлечённый текст **32 МиБ**, запас памяти **8 МиБ**. Проверка потока 20 МиБ есть в JVM-тестах; отдельный тест физической пары с отключённым Wi-Fi пока не зафиксирован.
- PDF рендерится с ограничением 640 px по ширине и 2048 px по высоте; сильное увеличение не добавляет детализацию.
- Реальные проверки сборки, рендеринга, rotary input и жизненного цикла приёма описаны в [отчёте о тестировании](docs/TESTING.md).

## Разработчикам

```text
mobile/   Android · Compose Material 3 · SAF · PDFBox
wear/     Wear OS · Compose · PdfRenderer · DataStore
shared/   Bluetooth service · протокол WR01 · SHA-256 · хранилище
```

**JDK 17 · Kotlin 2.1.10 · Gradle 8.11.1 · Android SDK 35**

```bash
./gradlew :mobile:assembleDebug :wear:assembleDebug
./gradlew :shared:testDebugUnitTest :mobile:lintDebug :wear:lintDebug
```

В Windows используй `gradlew.bat`. [Полная инструкция сборки →](docs/BUILD.md)

## Обратная связь

Нашёл ошибку? [Создай issue](https://github.com/Hoccku22/WristReader/issues/new/choose) и укажи модель часов, версию Android/Wear OS и действия перед сбоем. Пожелания по чтению и управлению тоже можно предложить через Issues.

Сторонние компоненты и их лицензии перечислены в [THIRD_PARTY.md](docs/THIRD_PARTY.md). Отдельная лицензия на переиспользование исходников пока не выбрана.
