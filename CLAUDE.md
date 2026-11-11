# Prism AI — контекст проекта

Ты — ведущий разработчик проекта Prism AI. Следуй этому документу на всех этапах.

## Продукт

Prism AI — полностью офлайн ИИ-ассистент для **Windows 10/11 (x64)** и **Android 10+ (arm64-v8a)**.

- Все модели работают локально, данные пользователя никогда не покидают устройство.
- Сеть используется **только** для скачивания моделей по явному действию пользователя и — если пользователь
  включил «Интернет» (настройки, экран моделей или переключатель в чате; по умолчанию выключен) — для инструментов
  ассистента: поиск DuckDuckGo, чтение страниц, скачивание файлов. Других обращений в сеть нет.
- Никакой телеметрии и аналитики.

## Личность ассистента

- Ассистент всегда представляется как **Prism AI**: на вопросы «кто ты?», «какая ты модель?» отвечает, что он Prism AI.
- Реализуется тремя слоями (строки в ресурсах, на языке интерфейса; `ResourcePersonaProvider`):
  системный промпт; образец обмена «Кто ты?» → «Я Prism AI…» перед историей (никогда не обрезается);
  скрытое напоминание в конце последнего сообщения пользователя (только в промпте, не в истории).
  Малые Gemma 3 без напоминания называют себя Gemma — проверено в CI на Gemma 3 270M.
- На вопрос о базовой модели ассистент не врёт: «работаю на открытой языковой модели, какая — в Настройки →
  Модели и лицензии». Прямого отрицания («я не Gemma») не добавлять.
- Позже — опционально LoRA-дообучение открытой модели на ответах о личности Prism AI (отдельный GGUF в каталоге).

## Актуальность знаний

- Модели знают мир только до конца своего обучения и без подсказки считают текущим его год (например, советуют
  Android API 34–35, когда актуальны 36–37). Дообучение это не лечит: факты снова устаревают, а малые модели
  плохо запоминают новые факты из LoRA.
- Поэтому в системный промпт добавляется строка `current_date_note` (`AssistantPersona.currentDateNote`):
  сегодняшняя дата с устройства (`todayText()`), предупреждение, что знания старше этой даты, и короткая справка
  о текущих версиях (Android API, Java, Kotlin, Gradle, Python…). **Справку обновлять при каждом релизе.**
- С включённым интернетом агент проверяет версии SDK и библиотек через `web_search` (`agent_internet_note`).
- **Базовое семейство моделей — Gemma** (Google): хороший русский, малые версии для телефона, vision в небольших
  размерах, лицензия позволяет назвать дообученную модель Prism AI. Llama — только как дополнительная модель в каталоге
  (в русском слабее, vision только от 11B, дообученная модель обязана называться «Llama…»).
  Конкретные версии и размеры Gemma проверяются по актуальным релизам при сборке каталога моделей.
- Лицензии: **Gemma 4 — Apache 2.0** (https://www.apache.org/licenses/LICENSE-2.0); Gemma 3 — Gemma Terms of Use
  (https://ai.google.dev/gemma/terms). Лицензия каждой модели указана в каталоге и показывается в карточке модели
  и в «Настройки → Модели и лицензии».
- Каталог: `shared/src/commonMain/composeResources/files/models.json` (репозитории ggml-org на Hugging Face, без авторизации).
  Размер и SHA-256 каждого файла обязательны — загрузка проверяет их. Поле `vision` — модуль зрения (mmproj, Q8_0)
  из того же репозитория; модель с ним понимает изображения (все Gemma 4). Модель = основной файл + модуль зрения:
  статус считается по обоим, но чат работает, как только скачан основной (`ModelEntry.chatReady`).
- Лицензии моделей соблюдаются: в «О приложении» и в карточке модели указываются исходная модель и её лицензия
  (Apache 2.0 — Qwen/Mistral; Llama — «Built with Llama»; Gemma Terms of Use).

## Функции (по этапам)

1. **Чат с историей:** несколько диалогов, потоковый ответ.
2. **Картинки:** понимание изображений vision-моделями.
3. **Рисование:** генерация изображений по описанию (Windows — FLUX.1 [schnell] Q4, Android — SDXL-Lightning).
4. **Файлы:** вопросы по PDF, TXT, MD, DOCX через RAG.
5. **Агент:** папка проекта диалога (чтение, поиск, создание и правка файлов, на Windows — команды) и интернет.
6. **Видео:** Wan 2.2 TI2V 5B на Windows, экспериментально Wan 2.1 1.3B на мощных телефонах.
7. **Голос:** распознавание речи (диктовка), озвучка ответов, режим разговора.

## Стек

- **Kotlin Multiplatform + Compose Multiplatform.** Модули: `shared` (логика и UI), `androidApp`, `desktopApp`.
- **Инференс:** llama.cpp как нативная библиотека:
  - Android NDK — `.so`;
  - Windows — `.dll` с бэкендами CPU и Vulkan, опционально CUDA.
  - JNI-обёртка, подключение через `expect`/`actual`.
- **Модели:** формат GGUF. Каталог моделей — JSON-файл, не хардкод.
- **Голос:** sherpa-onnx (STT — Whisper, TTS — Piper с русскими голосами, VAD — Silero).
- **БД:** SQLDelight. **DI:** Koin. **Асинхронность:** Coroutines + Flow.
- **Навигация:** Decompose (см. обоснование ниже).
- Перед добавлением любой библиотеки проверяй её актуальную стабильную версию. Все версии — только в `gradle/libs.versions.toml`.

### Почему Decompose, а не Voyager

- Компоненты Decompose — обычные Kotlin-классы без зависимости от UI. Логика экранов (MVI-сторы) живёт в `shared` и тестируется без Compose.
- Компоненты получают собственный жизненный цикл, сохраняют состояние и переживают смену конфигурации на Android. Это нужно, чтобы не прерывать потоковую генерацию и загрузку моделей.
- Decompose стабильно поддерживается и официально работает на Android и Desktop JVM. Развитие Voyager заметно замедлилось.
- Типобезопасная навигация через сериализуемые конфигурации (`kotlinx.serialization`) и хорошая совместимость с Koin.

## Архитектура

- Слои **data / domain / presentation**, MVI с однонаправленным потоком данных.
- Интерфейс `InferenceEngine`: `load`, `generate` (`Flow<String>`), `cancel`, `unload`, `modelInfo`.
  Позже через него можно подключить Ollama как второй бэкенд на Windows.
- **Изображения (Этап 3):** libmtmd из llama.cpp. Модуль зрения загружается в память лениво — при первом запросе
  с изображением. Фото сохраняются уменьшенными JPEG (до 1024 px, поворот по EXIF) в `images/` каталога приложения
  (`ImageStore`; Android — ImageDecoder, Desktop — ImageIO), в БД — таблица `message_image` (миграция `1.sqm`).
  В промпте изображение — метка `<__media__>` перед текстом сообщения; кэш промпта переиспользует уже закодированные
  изображения по их id. Gemma 4: не больше 280 токенов на изображение (`MAX_IMAGE_TOKENS`, должно помещаться в батч).
  Android: системный Photo Picker (без разрешений) и камера через `CameraOutputProvider` (снимок не попадает в галерею).
- **Рисование:** stable-diffusion.cpp (подмодуль `native/stable-diffusion.cpp`, закреплён на теге `master-…`) в отдельном
  процессе `prism_imagegen` (`native/imagegen`): его изменённая ggml несовместима с ggml из llama.cpp в одном процессе,
  а нехватка памяти при рисовании не должна ронять приложение. Протокол — строки JSON через stdin/stdout
  (load/generate/cancel/quit → started/loaded/progress/done/error); пути только через stdin (UTF-8, кириллица на Windows).
  Kotlin: `ProcessImageGenerator`. Windows — сборка с Vulkan (без драйвера Vulkan программа не стартует →
  `GPU_DRIVER_MISSING`), Android — `libprism_imagegen.so` в jniLibs (запускать можно только lib*.so из каталога
  нативных библиотек). Модели изображений в каталоге: `kind: image`, `platforms`, `mainRole`, `components`
  (t5xxl/clip_l/clip_g/vae/taesd), `image` (размер, шаги, сэмплер). Перед рисованием русское описание переводит
  модель чата (активная, иначе самая лёгкая скачанная; CLIP/T5 понимают только английский — без перевода русское
  описание не рисуется: `TRANSLATOR_REQUIRED` / `TRANSLATION_FAILED`), затем она выгружается; перед ответом чата выгружается
  модель рисования — в памяти всегда одна.

- **Файлы (Этап 4):** PDF, DOCX, TXT, MD прикладываются к диалогу (не к сообщению): `DocumentService`
  извлекает текст (`FileTextExtractor`: PDFBox на Desktop, PdfBox-Android на Android, `word/document.xml` из DOCX,
  UTF-8/UTF-16/Windows-1251), делит на фрагменты ~900 символов внутри одной страницы с перекрытием (`TextChunker`)
  и считает эмбеддинги моделью каталога `kind: embedding` — EmbeddingGemma 300M (`LlamaTextEmbedder`: JNI
  `createEmbedder`/`embed`, процессор, свой поток; шаблоны запроса/фрагмента — поле `embedding` каталога).
  Сам файл не хранится: таблицы `document`, `document_chunk` (вектор float32 LE), `message_source` (миграция `2.sqm`).
  Перед ответом: если файлы целиком помещаются в бюджет (~0,9 символа на токен контекста модели, 2–9 тыс.),
  модель получает весь текст, иначе — самые похожие фрагменты (`DocumentSearch`: не дальше 0,15 от лучшего).
  Фрагменты добавляются только к последнему сообщению в промпте (`AssistantPersona.documentContext`),
  у ответа сохраняются источники (файл, страница). Модель эмбеддингов выгружается после подготовки файлов и поиска.

- **Агент (Этап 5):** протокол вызова инструментов для локальных моделей — разметка без экранирования
  (`<tool name="write_file"><path>…</path><content>…</content></tool>`, результат — `<result name="…">`),
  `ToolCallProtocol`; генерация останавливается на `</tool>`, `ChatGenerationService.runAgent` выполняет вызов
  (`AgentToolbox`) и продолжает, не больше 12 шагов. Инструменты зависят от диалога: папка проекта (`chat.workspace`,
  миграция `3.sqm`; Desktop — `FileWorkspace` на java.nio, Android — `SafWorkspace` через Storage Access Framework,
  пути вне папки отклоняются), интернет (`HttpWebClient`: HTML-версия DuckDuckGo, страницы → текст `HtmlText`),
  команды (`ProcessCommandRunner`, только Desktop: `cmd /c`). Запись/правка/скачивание ждут разрешения
  (`PendingAction`, разница «было/стало»), если не включено «Править файлы без подтверждения»; команды — всегда.
  Шаги ответа хранятся в `message_step` и показываются под ответом.

- **Видео (Этап 6):** Wan в stable-diffusion.cpp через тот же процесс `prism_imagegen`: команда `generate_video`
  (`generate_video()` C API), ролик пишется в MJPEG AVI (`write_mjpeg_avi`: кадры JPEG, индекс idx1) — его открывают
  плееры, а приложение проигрывает по кадрам (`MjpegAvi` → `VideoPlayer`). Видео — вложение сообщения в той же
  таблице `message_image` (файл `….avi`, `ImageAttachment.isVideo`). Каталог: `kind: video`, поле `video` (размер,
  кадры 4k+1, fps, шаги, cfg, flow shift, отрицательное описание, `offloadToCpu` — веса в ОЗУ через
  `params_backend="*=cpu"`, `imageToVideo`). Windows — Wan 2.2 TI2V 5B Q4_0 + umt5 Q4_K_M + Wan2.2 VAE (умеет оживлять
  фото), Android — экспериментально Wan 2.1 T2V 1.3B Q4_K_M + umt5 Q3_K_S + Wan 2.1 VAE. Описание переводится
  моделью чата, как для рисования. Тест: `PRISM_TEST_WAN_DIR` (задание «Video generation (Wan 2.1 1.3B)»).

- **Длительность и LTX-2.5 (после этапа 7):** у моделей видео в каталоге `frameStep` (Wan — 4, LTX — 8), `maxFrames`
  (кадров за раз), `durations` (секунды на выбор) и `audio`. Длительность выбирается в чате (режим «Видео»,
  `AppSettings.videoSeconds`); длиннее одного куска — продолжением: `VideoPlan` делит ролик на куски, следующий
  кусок снимается от последнего кадра предыдущего (оживление фото), `VideoClipBuilder` склеивает их без повтора кадра,
  при остановке сохраняет снятое. LTX-2.5 distilled (unsloth GGUF UD-Q4_K_XL, 8 шагов, CFG 1) — вторая модель видео
  на Desktop, со звуком: `prism_imagegen` получает `llm` (Gemma 4 12B с проекцией, bf16 — у сжатого GGUF репозиторий
  закрыт), `audio_vae`, пишет звук в WAV рядом с AVI, `ProcessImageGenerator` вшивает его вторым потоком
  (`MjpegAvi.write`, PCM 16 бит), плеер раскодирует кадры на лету и играет звук. Выбор модели — «Настройки → Видео».
  Лицензия LTX-2 Community License: бесплатно при доходе до $10 млн в год.

- **Голос (Этап 7):** sherpa-onnx 1.13.8 (последняя версия с espeak-ng, без которого не работают голоса Piper;
  в 2.0 его убрали). AAR и jar берутся из релизов GitHub через ivy-репозиторий в `settings.gradle.kts`;
  Android — Kotlin API из AAR (`AndroidSherpaSpeechEngine`), Desktop — Java API и нативные библиотеки ОС сборки
  (`DesktopSherpaSpeechEngine`), общая часть — `SherpaSpeechEngine` (модели в памяти, один поток, ошибки →
  `VoiceError.ENGINE`). Каталог: `kind: speech_recognition` — GigaAM v3 CTC с пунктуацией (русский, `nemo_ctc`),
  Whisper Small и Turbo (`whisper`, язык определяется сам); у каждой компонент `vad` — Silero VAD (общий файл,
  удаляется вместе с последней моделью, которая его использует); `kind: speech_synthesis` — голоса Piper
  «Дмитрий» и «Денис» (датасет CC0) и «Ирина» (записи RHVoice, лицензия не указана — по просьбе владельца
  проекта, в описании указано «для личного использования»; «Руслан» не взят: CC BY-NC-SA), один архив `tar.bz2`
  (`archive: true`), распаковывается после загрузки (`TarBz2Unpacker`, commons-compress) в `<файл>.d`.
  `VoiceService`: диктовка в поле ввода (запись до паузы 1,2 с, детектор речи отрезает тишину, фрагменты до 20 с),
  озвучка ответа по фразам (`SpeechTextSplitter` убирает Markdown, код и адреса ссылок; следующая фраза синтезируется,
  пока играет текущая) и режим разговора (слушает → `ChatGenerationService.send` → озвучивает ответ по мере
  генерации → снова слушает; заканчивается кнопкой, ошибкой ответа или 20 с тишины). Модели речи выгружаются
  через 2 минуты без голосовых действий. Микрофон: Android — `AudioRecord` (источник VOICE_RECOGNITION,
  разрешение RECORD_AUDIO спрашивает `rememberMicrophonePermission`), Desktop — Java Sound. Выбор модели
  распознавания, голоса и скорости речи — «Настройки → Голос». Лицензии: sherpa-onnx — Apache 2.0, внутри
  espeak-ng — GPL-3.0 (исходники открыты), GigaAM, Whisper, Silero VAD — MIT.
  Тесты: `SpeechIntegrationTest` (Piper произносит фразу, Silero находит её, GigaAM и Whisper распознают;
  модели качает `.github/download_speech_models.py` по каталогу) и `VoiceDetectorNativeTest` (загрузка нативных
  библиотек, в том числе на Windows).

## Дизайн

- Две темы на выбор:
  - **Material 3 Expressive** (динамические цвета на Android);
  - **Liquid Glass** (полупрозрачные стеклянные панели, размытие, мягкие блики).
- Светлый и тёмный режим, плавные анимации.
- Интерфейс на русском, **все строки в ресурсах** (Compose Multiplatform resources), чтобы потом добавить английский.

## Сборка и CI

- Облачная среда разработки не имеет доступа к `dl.google.com` (Google Maven), поэтому проект собирается
  и проверяется в GitHub Actions (`.github/workflows/build.yml`):
  - **Android APK** — `:androidApp:assembleDebug`, артефакт `PrismAI-android-debug-apk`;
  - **Desktop** — `:desktopApp:compileKotlin :shared:jvmTest`, UI-тесты сохраняют скриншоты (артефакт `PrismAI-screenshots`);
  - **Windows** — `:desktopApp:createDistributable` на `windows-latest`, артефакт `PrismAI-windows`.
- llama.cpp — git-подмодуль `native/llama.cpp` (закреплён на релизном теге), JNI-мост — `native/prism_llama.cpp`.
  Android собирает его через `externalNativeBuild` (arm64-v8a), Desktop — модуль `:native` (cmake; `-Pprism.vulkan=true`
  для Vulkan). Бэкенды ggml — динамические (`GGML_BACKEND_DL`), CPU во всех вариантах набора инструкций.
  Локальная сборка Desktop требует cmake и компилятор C++; после клонирования: `git submodule update --init`.
- Интеграционные тесты llama.cpp: `-Pprism.nativeTests=true` + переменные `PRISM_TEST_MODEL` / `PRISM_TEST_GEMMA`,
  для изображений — `PRISM_TEST_VLM` / `PRISM_TEST_VLM_MMPROJ` (SmolVLM 256M) и `PRISM_TEST_GEMMA4` /
  `PRISM_TEST_GEMMA4_MMPROJ` (Gemma 4 E2B): модель должна назвать цвет однотонной картинки.
- Рисование: `-Pprism.imagegen=true` собирает `prism_imagegen` (на Windows с `-Pprism.vulkan=true` — с Vulkan);
  после клонирования ещё `git -C native/stable-diffusion.cpp submodule update --init --depth 1 ggml`.
  Поиск по файлам: `PRISM_TEST_EMBED` (EmbeddingGemma 300M) — вопрос ближе к фрагменту о том же.
  Тесты рисования: `PRISM_TEST_SDXL` / `PRISM_TEST_SDXL_TAESD` (в задании Desktop) и `PRISM_TEST_FLUX_DIR` (отдельное задание
  «Image generation (FLUX.1 schnell)»: ~10 ГБ не помещаются в кэш Actions) — модель рисует красный фон, проверяется цвет.
- Облачная среда разработки не видит huggingface.co: метаданные файлов (размер, SHA-256) печатает задание
  «Model catalog report» (`.github/catalog_report.py`) для коммита со словом `[catalog-report]` в сообщении.
- Релиз: коммит со словом `[release]` (или тег `vX.Y.Z`) собирает приложение и публикует релиз GitHub
  с тегом `v<prism-versionName>` (APK, zip для Windows, SHA256SUMS); текст — `.github/release-notes.md`.
  Тег из облачной среды не пушится — его создаёт задание release.
- Предупреждения компилятора — ошибки (`allWarningsAsErrors`). Шаг диагностики CI при падении печатает
  их текст (`-Pprism.werror=false` используется только им).
- Версия приложения задаётся в `gradle/libs.versions.toml` (`prism-versionName`) и попадает в код через сгенерированный `BuildInfo`.
- Известные особенности версий:
  - в стабильном material3 API Expressive (`MaterialExpressiveTheme`, `MotionScheme`) внутренние — стиль собран из публичных API;
  - AGP 9: модуль `shared` использует плагин `com.android.kotlin.multiplatform.library` и блок `kotlin { android { } }`;
  - в UI-тестах Desktop — `androidx.compose.ui.test.v2.runDesktopComposeUiTest` (v1 устарел);
  - главный поток Decompose на Desktop — поток Swing (`runOnUiThread`);
  - API llama.cpp меняется между релизами (`llama_batch_ext`, `llama_process`, `llama_memory_*`): перед обновлением
    подмодуля сверяйся с `include/llama.h`;
  - Gemma 4 использует формат `<|turn>…<turn|>`, который не понимает `llama_chat_apply_template` — промпт собирает
    `ChatPromptFormatter` по полю `chatFormat` из каталога.
  - Gemma 4 (проверено на E2B в CI) рассуждает текстом даже при пустом канале `<|channel>thought\n<channel|>`
    в промпте: «…The capital of France is Paris.<channel|>Paris». `Gemma4ReplyFilter` скрывает всё до `<channel|>`
    и служебные теги; пока идут рассуждения, чат показывает «думает…». В историю попадает только ответ.

## Правила работы

- Работай строго в рамках текущего этапа, не забегай вперёд.
- В конце каждого этапа проект собирается на обеих платформах (`:desktopApp:run` и APK) **без предупреждений компилятора**.
- В конце этапа — короткий отчёт: что сделано, как проверить, известные ограничения.
- Никаких TODO-заглушек в рабочих путях.
- Обрабатывай ошибки: нехватка памяти, повреждённая модель, прерванная загрузка.
- Никакой телеметрии и аналитики.
