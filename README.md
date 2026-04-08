# WakeWord Assistant (Android, Kotlin)

Готовый каркас Android-приложения голосового ассистента:
- Wake Word через Picovoice Porcupine в `Foreground Service`.
- После активации — команда в `SpeechRecognizer`.
- Распознанный текст отправляется в Google Gemini (`gemini-1.5-flash`).
- Ответ модели строго JSON и исполняется как `play_music` или `speak`.

## 1) Где взять ключи

### Picovoice AccessKey
1. Зарегистрируйтесь: https://console.picovoice.ai/
2. Создайте AccessKey в консоли.
3. Вставьте ключ в `app/build.gradle.kts` в поле:
   - `buildConfigField("String", "PICOVOICE_ACCESS_KEY", '"YOUR_KEY"')`

### Gemini API Key
1. Откройте Google AI Studio: https://aistudio.google.com/app/apikey
2. Создайте API key.
3. Вставьте ключ в `app/build.gradle.kts` в поле:
   - `buildConfigField("String", "GEMINI_API_KEY", '"YOUR_KEY"')`

> Для production лучше хранить ключи через `local.properties`/Secrets Gradle Plugin, а не в git.

## 2) Wake Word модель (.ppn)

Для кастомного Wake Word сгенерируйте `.ppn` в Picovoice Console и положите файл в проект
(например, `app/src/main/assets/porcupine_android.ppn`).

В этом каркасе `VoiceService` ожидает путь `porcupine_android.ppn` (настройте под свой файл).

## 3) Что уже реализовано

- Runtime permissions и запуск сервиса из `MainActivity`.
- `Foreground Service` с постоянным уведомлением и lifecycle Porcupine.
- Передача управления в `SpeechRecognizer` после wake word.
- Запрос в Gemini с `system_instruction` и требованием вернуть только JSON.
- Парсинг JSON и выполнение экшенов:
  - `play_music` -> `MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH`
  - `speak` -> озвучка через `TextToSpeech`

## 4) Зависимости

Все нужные зависимости уже добавлены в `app/build.gradle.kts`:
- `ai.picovoice:porcupine-android`
- `com.squareup.okhttp3:okhttp`
- `kotlinx-coroutines-android`
- `kotlinx-serialization-json`
- AndroidX базовые библиотеки

## 5) Разрешения и манифест

`AndroidManifest.xml` включает:
- `RECORD_AUDIO`
- `INTERNET`
- `FOREGROUND_SERVICE`
- `FOREGROUND_SERVICE_MICROPHONE`
- `POST_NOTIFICATIONS`

И сервис:
- `android:foregroundServiceType="microphone"`

## 6) Важные примечания

- На Android 12+ есть ограничения запуска активностей из background. Если музыкальный интент блокируется,
  лучше отправлять broadcast в медиаплеер, либо открывать UI после пользовательского взаимодействия.
- Рекомендуется добавить retry/backoff для сетевых ошибок Gemini.
- Для устойчивости сервиса можно добавить `BOOT_COMPLETED` + автозапуск (с учетом ограничений OEM).

## 7) Быстрый запуск

1. Вставьте ключи Picovoice/Gemini в `app/build.gradle.kts`.
2. Подключите кастомный `.ppn` wake word.
3. Соберите и установите приложение.
4. Нажмите `Start voice service`, выдайте permissions.
5. Произнесите wake word, затем команду.
