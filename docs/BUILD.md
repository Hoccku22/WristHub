# Сборка из исходников

## Окружение

| Компонент | Версия |
|---|---|
| JDK | 17 |
| Gradle Wrapper | 8.11.1 |
| Android Gradle Plugin | 8.9.1 |
| Kotlin / Compose compiler | 2.1.10 |
| Android SDK / Build Tools | 35 / 35.0.0 |
| Минимальная версия телефона | Android 8.0, API 26 |
| Минимальная версия часов | Android 11, API 30 |

1. Клонируй репозиторий и открой папку в Android Studio.
2. Установи Platform 35, Build Tools 35.0.0 и Platform Tools через SDK Manager.
3. Выбери Gradle JDK 17. Android Studio создаст `local.properties` с путём SDK;
   для терминала можно вместо этого задать `ANDROID_HOME`.

```bash
chmod +x gradlew
./gradlew :mobile:assembleDebug :wear:assembleDebug
./gradlew :shared:testDebugUnitTest :mobile:lintDebug :wear:lintDebug
```

В PowerShell: `./gradlew.bat` с теми же задачами. Интернет компьютеру нужен для
первоначальной загрузки зависимостей. Приложения не используют сетевой транспорт.

APK:

```text
mobile/build/outputs/apk/debug/mobile-debug.apk
wear/build/outputs/apk/debug/wear-debug.apk
```

## Подпись

Сборки из Releases — debug APK. Локальная сборка получит debug-ключ среды,
который может отличаться от подписи скачанного релиза. Автоматическое обновление
поверх установленного приложения требует совпадения ключа.

Ключи не хранятся в публичном репозитории. Для собственного распространения нужно
настроить постоянный release-ключ и хранить его отдельно. Это также необходимый
этап перед возможной публикацией в магазине.

## Проверки

Сборка обоих APK, JVM-тесты протокола и Android Lint запускаются командами выше.
Постоянные файлы для установки находятся в Releases.

Тесты на физических часах запускаются отдельно. См. [TESTING.md](TESTING.md).
