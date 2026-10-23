# Сторонние компоненты

Приложение использует зависимости из Google Maven и Maven Central:

- AndroidX / Jetpack Compose / Wear Compose / DataStore — Apache License 2.0.
- Kotlin и kotlinx.coroutines — Apache License 2.0.
- PDFBox Android (Tom Roush), Apache PDFBox — Apache License 2.0.
- Bouncy Castle, транзитивная зависимость PDFBox — лицензия Bouncy Castle (MIT-подобная).
- JUnit — Eclipse Public License 1.0; используется только для тестирования.
- Android SDK / Build Tools и Gradle применяются для сборки по собственным условиям.

Исходники PDFBox Android и его LICENSE / NOTICE:
[github.com/TomRoush/PdfBox-Android](https://github.com/TomRoush/PdfBox-Android).
Полный граф зависимостей: `gradlew :mobile:dependencies :wear:dependencies`.

Lint может предупреждать о TrustManager внутри неиспользуемых TLS-классов
транзитивной библиотеки Bouncy Castle. WristReader не вызывает эти классы,
не выполняет TLS-запросов и не имеет разрешения INTERNET. Предупреждения
не скрываются глобальным подавлением анализатора.
