# Prism AI — контекст проекта

Ты — ведущий разработчик проекта Prism AI. Следуй этому документу на всех этапах.

## Продукт

Prism AI — полностью офлайн ИИ-ассистент для **Windows 10/11 (x64)** и **Android 10+ (arm64-v8a)**.

- Все модели работают локально, данные пользователя никогда не покидают устройство.
- Сеть используется **только** для скачивания моделей по явному действию пользователя, для проверки и загрузки
  обновления приложения по кнопке «Проверить обновления» (GitHub Releases; фоновых проверок нет) и — если пользователь
  включил «Интернет» (настройки, экран моделей или переключатель в чате; по умолчанию выключен) — для инструментов
  ассистента: поиск DuckDuckGo, чтение страниц, скачивание файлов. Других обращений в сеть нет.
- Никакой телеметрии и аналитики.

## Личность ассистента

- Ассистент всегда представляется как **Prism AI**: на вопросы «кто ты?», «какая ты модель?» отвечает, что он Prism AI.
- Реализуется тремя слоями (строки в ресурсах, на языке интерфейса; `ResourcePersonaProvider`):
  системный промпт; образец обмена «Кто ты?» → «Я Prism AI…» перед историей (никогда не обрезается);
  скрытое напоминание в конце последнего сообщения пользователя (только в промпте, не в истории).
  Малые Gemma 3 без напоминания называют себя Gemma — проверено в CI на Gemma 3 270M.
- На вопрос о базовой модели ассистент не врёт: «работаю на открытой языковой модели, какая — в Настройки → О приложении →
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
  размерах, лицензия позволяет назвать дообученную модель Prism AI. Qwen3.8-27B (Apache 2.0, ggml-org, `chatFormat: qwen` —
  ChatML с пустым `<think>`, `ThinkReplyFilter` скрывает размышления) — дополнительная модель для мощных ПК.
  Llama — только как дополнительная модель в каталоге
  (в русском слабее, vision только от 11B, дообученная модель обязана называться «Llama…»).
  Конкретные версии и размеры Gemma проверяются по актуальным релизам при сборке каталога моделей.
- Лицензии: **Gemma 4 — Apache 2.0** (https://www.apache.org/licenses/LICENSE-2.0); Gemma 3 — Gemma Terms of Use
  (https://ai.google.dev/gemma/terms). Лицензия каждой модели указана в каталоге и показывается
  и в «Настройки → О приложении → Модели и лицензии».
- Каталог: `shared/src/commonMain/composeResources/files/models.json` (репозитории ggml-org на Hugging Face, без авторизации).
  Размер и SHA-256 каждого файла обязательны — загрузка проверяет их. Поле `oldFileNames` (у модели, `vision`
  и компонентов) — прежние имена файла: при запуске и после переноса папки `FileModelStorage.adoptOldFiles`
  переименовывает уже скачанные файлы (и `.part`, `.d`, сжатые копии), чтобы переименование в каталоге не заставляло
  качать модели заново. `id` моделей не меняются — на них ссылаются настройки. Поле `vision` — модуль зрения (mmproj, Q8_0)
  из того же репозитория; модель с ним понимает изображения (все Gemma 4). Модель = основной файл + модуль зрения:
  статус считается по обоим, но чат работает, как только скачан основной (`ModelEntry.chatReady`).
- Модели в каталоге называются по-нашему (`name`: Prism 1 Lite/Flash/Plus/Pro, Prism 1.5 Pro Max/Quadron, PrismFile Reader,
  Prism Voice Detector…, Prism Voice 1.5, Prism Image 0.5 (Lite), Prism Video 0.5 Lite (PC)/Super Lite (Mobile), Prism Video 1 Pro,
  Prism Video Beta), файлы — `prism-…`; `family` и `parameters` хранят исходную модель (Gemma 4 E2B, Qwen3.8 27B…).
- Лицензии моделей соблюдаются, но спрятаны: «Настройки → О приложении → Модели и лицензии» (свёрнуто) — название Prism,
  исходная модель и её лицензия со ссылкой (Apache 2.0 — Qwen/Gemma 4; Gemma Terms of Use — Gemma 3; LTX-2 Community License).
  В карточке модели лицензии нет. На вопрос о базовой модели ассистент отсылает в этот раздел.

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
  (`AgentToolbox`) и продолжает, не больше `agentSteps` шагов (настройка); к результату последнего шага добавляется
  `agent_step_limit` (подвести итог без инструментов), а если модель всё равно вызывает инструмент — ответ заканчивается
  подсказкой `agent_step_limit_notice` («напишите „продолжай“»). `list_files` показывает вложенные папки
  (до `folderDepth` уровней, 300 строк, служебные папки не раскрываются) — модель не тратит шаг на каждую папку. Инструменты зависят от диалога: папка проекта (`chat.workspace`,
  миграция `3.sqm`; Desktop — `FileWorkspace` на java.nio, Android — `SafWorkspace` через Storage Access Framework,
  пути вне папки отклоняются), интернет (`HttpWebClient`: HTML-версия DuckDuckGo, страницы → текст `HtmlText`),
  команды (`ProcessCommandRunner`, только Desktop: `cmd /c`). Запись/правка/скачивание ждут разрешения
  (`PendingAction`, разница «было/стало»), если не включено «Править файлы без подтверждения»; команды — всегда.
  Шаги ответа хранятся в `message_step` и показываются под ответом.
- **Настройки ассистента:** контекст (`AppSettings.contextLength`: 0 — по каталогу, 4K–108K; `withChosenContext` перед
  `engine.load`, движок уменьшает до `n_ctx_train` и перезагружает модель при смене), шаги агента (`agentSteps`: 25, 50,
  100, 250, 0 — без ограничения; по умолчанию 50) и глубина `list_files` (`folderDepth`: 10, 20, 50, 0 — без ограничения).
- **Черновики:** неотправленный текст и фото диалога хранятся в `DraftRepository` (`KeyValueDraftRepository`:
  JSON в `KeyValueStore`, ключ `chat_drafts`) — переживают уход из диалога и перезапуск; фото черновиков не удаляются
  как брошенные, черновики удалённых диалогов чистятся при запуске. Файлы (PDF, DOCX…) прикладываются к диалогу сразу.

- **Видео (Этап 6):** Wan в stable-diffusion.cpp через тот же процесс `prism_imagegen`: команда `generate_video`
  (`generate_video()` C API), ролик пишется в MJPEG AVI (`write_mjpeg_avi`: кадры JPEG, индекс idx1) — его открывают
  плееры, а приложение проигрывает по кадрам (`MjpegAvi` → `VideoPlayer`). Видео — вложение сообщения в той же
  таблице `message_image` (файл `….avi`, `ImageAttachment.isVideo`). Каталог: `kind: video`, поле `video` (размер,
  кадры 4k+1, fps, шаги, cfg, flow shift, отрицательное описание, `offloadToCpu` — веса в ОЗУ через
  `params_backend="*=cpu"`, `imageToVideo`). Windows — Wan 2.2 TI2V 5B Q4_0 + umt5 Q4_K_M + Wan2.2 VAE (умеет оживлять
  фото), Android — экспериментально Wan 2.1 T2V 1.3B Q4_K_M + umt5 Q3_K_S + Wan 2.1 VAE (12 ГБ, без продолжения)
  и Wan 2.2 TI2V 5B 512×288 (16 ГБ, с продолжением; замер на 4 ядрах ARM: ~68 мин на 17 кадров, пик 10,4 ГБ, шов 1,1).
  `prism_imagegen` для Android собирается дважды: ARMv8.2 (dotprod, fp16) и с i8mm (`libprism_imagegen_i8mm.so`),
  `AndroidImageGenBinary` выбирает по /proc/cpuinfo (i8mm должен быть у всех ядер). Описание переводится
  моделью чата, как для рисования. Тест: `PRISM_TEST_WAN_DIR` (задание «Video generation (Wan 2.1 1.3B)»).

- **Длительность и LTX-2.5 (после этапа 7):** у моделей видео в каталоге `frameStep` (Wan — 4, LTX — 8), `maxFrames`
  (кадров за раз), `durations` (секунды на выбор) и `audio`. Длительность выбирается в чате (режим «Видео»,
  `AppSettings.videoSeconds`); длиннее одного куска — продолжением: `VideoPlan` делит ролик на куски, следующий
  кусок снимается от последнего кадра предыдущего (оживление фото), `VideoClipBuilder` склеивает их без повтора кадра,
  при остановке сохраняет снятое. LTX-2.5 distilled (unsloth GGUF UD-Q4_K_XL, 8 шагов, CFG 1) — вторая модель видео
  на Desktop, со звуком, до 30 секунд продолжением: `prism_imagegen` получает `llm` (Gemma 4 12B с проекцией, bf16 — у сжатого GGUF репозиторий
  закрыт), `audio_vae`, пишет звук в WAV рядом с AVI, `ProcessImageGenerator` вшивает его вторым потоком
  (`MjpegAvi.write`, PCM 16 бит), плеер раскодирует кадры на лету и играет звук. Выбор модели — «Настройки → Видео».
  Лицензия LTX-2 Community License: бесплатно при доходе до $10 млн в год.
  Текстовый энкодер (роль `llm`, bf16 26 ГБ) на выбор — «Настройки → Видео» (`TextEncoderMode`: полный, Q8, Q4;
  по умолчанию по объёму ОЗУ: ≥60 ГБ полный, ≥44 ГБ Q8, иначе Q4): сжатая копия `<файл>.q4_k.gguf` создаётся один раз
  командой `convert` процесса `prism_imagegen` (`convert()` из stable-diffusion.cpp, по одному тензору; отмена завершает
  процесс), удаляется вместе с моделью (`FileModelStorage`).

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
  Голоса Piper из каталога убраны (движок остаётся, тест — голос «Дмитрий» по прямой ссылке в
  `.github/download_speech_models.py`). Голос по умолчанию — **Supertonic 3** (Prism Voice 1.5) (`engine: supertonic`, OpenRAIL-M): без espeak, правильнее ударения и английские
  слова, 10 голосов в `voice.bin` (`speech.speakers`: 0–4 женские, 5–9 мужские; выбор — `AppSettings.voiceSpeaker`),
  `lang` передаётся в `GenerationConfig.extra`, 5 шагов. Piper остаётся лёгким вариантом. Перед синтезом
  `SpeechNormalizer` (только русский) заменяет термины латиницей на русское произношение (Android → андроид),
  читает аббревиатуры по буквам (API → эй-пи-ай), проценты и градусы — словами с нужным окончанием.
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
  - **Android APK** — `:androidApp:assembleRelease` и `:store:androidApp:assembleRelease` (релизная сборка без
    отладки — Play Защита строже к отладочным APK, ставящим приложения; R8 выключен, lintVital выключен),
    артефакты `PrismAI-android-apk` и `PrismStore-android-apk`;
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
- Видео на телефоне: коммит со словом `[phone-video-bench]` запускает задание «Phone video benchmark (ARM)»
  (`.github/phone_video_bench.py`, раннер `ubuntu-24.04-arm`, сборка ARMv8.2 как на Android): время, пик памяти
  и шов продолжения для Wan 2.1 1.3B, Wan 2.1 Fun InP 1.3B (`clip_vision`, `weight_type`) и Wan 2.2 TI2V 5B.
- **Подпись APK:** все сборки подписываются одним ключом из секретов репозитория (`PRISM_KEYSTORE_BASE64`,
  `PRISM_KEYSTORE_PASSWORD`, `PRISM_KEY_ALIAS`, `PRISM_KEY_PASSWORD`; Gradle читает `PRISM_KEYSTORE_FILE` и остальные из
  окружения). Иначе Android не ставит новую версию поверх старой, и при удалении пропадают модели. Без секретов
  ветка собирается отладочным ключом, а релиз падает. Ключ не терять: с другим ключом обновление поверх невозможно.
- **Обновление из приложения (OTA):** «Настройки → Обновления» → `UpdateService` + `AppUpdater`: `GitHubAppUpdater`
  берёт последний релиз (API GitHub), файл платформы (`-android-arm64.apk` / `-windows-x64.zip`) и его SHA-256
  (поле `digest` или `SHA256SUMS.txt`), качает через `HttpModelDownloader` с докачкой. Android — `AndroidAppUpdater`:
  PackageInstaller, разрешение REQUEST_INSTALL_PACKAGES, ответ — `UpdateInstallReceiver`. Windows — `DesktopAppUpdater`:
  zip распаковывается в `%LOCALAPPDATA%\PrismAI\updates`, скрипт PowerShell ждёт выхода программы, копирует файлы
  (robocopy) в папку `jpackage.app-path` и запускает её снова. Модели и данные не затрагиваются.
  Перед установкой на Android сравниваются ключи подписи APK и установленного приложения (`ApkSignatures`, так же
  в Prism Store): при несовпадении — ошибка `SIGNATURE` с советом удалить приложение один раз (установщик OnePlus
  и других телефонов в этом случае просто висит). Сборки CI до постоянного ключа (0.9.0 из артефактов) подписаны
  временным ключом.
- **Папка моделей (Android 11+):** «Настройки → Хранение моделей» — `SwitchableModelFolder` переносит файлы
  моделей между `filesDir/models` и `Download/PrismAI/models` (разрешение MANAGE_EXTERNAL_STORAGE,
  `AndroidSharedStorageAccess`); общая папка остаётся после удаления приложения. Перенос по одному файлу
  (копия через `.copy`, затем удаление), уже лежащие файлы того же размера не копируются — после переустановки
  включённая снова настройка находит модели. Выбор хранится в `models_shared_folder`; без доступа — внутренняя папка.
  Desktop: `ChoosableModelFolder` — любая папка (например, другой диск), внутри неё своя `PrismAI Models`;
  ключ `models_folder`, недоступная папка при запуске → стандартная и ошибка `FOLDER_UNAVAILABLE`. Перед переносом
  модели выгружаются (Windows не перемещает открытые файлы). Перенос — `ModelFolderMover` (на одном диске
  переименованием, между дисками — копией по файлу, место проверяется заранее).
- **Prism Store** (модули `:store:shared`, `:store:androidApp` — `ai.prism.store`, `:store:desktopApp` — PrismStore.exe;
  версия `store-versionName`): отдельное приложение, ставит и обновляет приложения Prism. Каталог
  `prism-store-catalog.json` собирает задание release (`.github/store_catalog.py` из `store/apps.json` и файлов релиза:
  версии, ссылки, размеры, SHA-256). `StoreController` + `GitHubCatalogSource` (последний релиз) + `HttpPackageDownloader`
  (докачка, SHA-256) + `AppInstaller`: Android — PackageInstaller, версия из PackageManager (`<queries>`), Windows —
  `%LOCALAPPDATA%\Programs\Prism\<папка>`, версия в `prism-version.txt`, ярлык в «Пуск», себя обновляет скриптом
  PowerShell. Если в релизе нет каталога (релизы до Store), `GitHubCatalogSource.fromAssets` находит приложения по
  именам файлов и SHA-256 из поля `digest`. Всё бесплатно, без аккаунтов. Следующий этап — дополнения.
- Релиз: коммит со словом `[release]` (или тег `vX.Y.Z`) собирает приложение и публикует релиз GitHub
  с тегом `v<prism-versionName>` (APK, zip для Windows, SHA256SUMS); текст — `.github/release-notes.md`.
  Версия — три или четыре числа (`0.9.2.1` — исправления к 0.9.2); `AppVersion`/`Versions` сравнивают по числам.
  Пометка в заголовке релиза — первая строка `.github/release-label.txt` («открытая бета»; пустой файл — без пометки).
  Слово `[hide-old-releases]` в коммите релиза после публикации делает все прежние релизы черновиками (видны только
  владельцам). Релиз не помечается как pre-release: иначе его не увидят OTA и Prism Store (API «последний релиз»).
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
