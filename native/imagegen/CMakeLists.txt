# Prism AI: отдельная программа генерации изображений (stable-diffusion.cpp).
#
# Работает в отдельном процессе: у stable-diffusion.cpp своя (изменённая) копия ggml, несовместимая с ggml
# из llama.cpp, а нехватка памяти при генерации не должна ронять приложение.
#
# Windows: PRISM_IMAGEGEN_VULKAN=ON — видеокарта через Vulkan (нет видеокарты — процессор).
# Android: собирается тулчейном NDK (arm64-v8a) и упаковывается как libprism_imagegen.so.

cmake_minimum_required(VERSION 3.22)
project(prism_imagegen C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

option(PRISM_IMAGEGEN_VULKAN "Build with the Vulkan backend" OFF)
# Android: набор инструкций ARM и имя файла. Базовая сборка — ARMv8.2 (dotprod, fp16); вторая — с i8mm
# (матричные инструкции int8 у процессоров последних лет), приложение выбирает её по /proc/cpuinfo.
set(PRISM_IMAGEGEN_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "GGML_CPU_ARM_ARCH for Android")
set(PRISM_IMAGEGEN_NAME "prism_imagegen" CACHE STRING "Output name (Android: lib<name>.so)")

if(NOT ANDROID)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)
    foreach(config DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
        set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_${config} ${CMAKE_BINARY_DIR}/bin)
    endforeach()
endif()

# Всё статически в одну программу: никаких DLL рядом с библиотеками llama.cpp.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(SD_WEBP OFF CACHE BOOL "" FORCE)
set(SD_WEBM OFF CACHE BOOL "" FORCE)
set(SD_VULKAN ${PRISM_IMAGEGEN_VULKAN} CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
if(ANDROID)
    # Телефоны с памятью для генерации (8 ГБ+) — ARMv8.2 с dotprod и fp16.
    set(GGML_CPU_ARM_ARCH "${PRISM_IMAGEGEN_ARM_ARCH}" CACHE STRING "" FORCE)
elseif(CMAKE_SYSTEM_PROCESSOR MATCHES "x86_64|AMD64|amd64")
    # Базовый набор для компьютеров последних 10 лет.
    foreach(feature SSE42 AVX AVX2 FMA F16C BMI2)
        set(GGML_${feature} ON CACHE BOOL "" FORCE)
    endforeach()
endif()

add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../stable-diffusion.cpp ${CMAKE_BINARY_DIR}/stable-diffusion.cpp)

add_executable(prism_imagegen ${CMAKE_CURRENT_LIST_DIR}/prism_imagegen.cpp)
target_link_libraries(prism_imagegen PRIVATE stable-diffusion ggml)
if(ANDROID)
    # Android устанавливает и разрешает запускать только файлы lib*.so из каталога нативных библиотек.
    set_target_properties(prism_imagegen PROPERTIES OUTPUT_NAME "${PRISM_IMAGEGEN_NAME}" PREFIX "lib" SUFFIX ".so")
    target_link_libraries(prism_imagegen PRIVATE log)
endif()
if(MSVC)
    target_compile_options(prism_imagegen PRIVATE /utf-8)
endif()
