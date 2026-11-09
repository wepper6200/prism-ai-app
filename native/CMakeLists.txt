# Нативная библиотека Prism AI: llama.cpp + JNI-мост (prism_llama).
#
# Android: собирается Gradle (androidApp → externalNativeBuild), ABI arm64-v8a.
# Desktop: собирается задачами модуля :native (cmake), результат кладётся в ресурсы приложения.
#
# libmtmd (мультимодальность llama.cpp) — понимание изображений vision-моделями через проектор (mmproj).
#
# Бэкенды ggml собираются как отдельные динамические библиотеки (GGML_BACKEND_DL):
# CPU — во всех вариантах набора инструкций (выбирается лучший при запуске),
# Vulkan — по флагу PRISM_VULKAN (Windows). Если Vulkan-драйвера нет, остаётся CPU.

cmake_minimum_required(VERSION 3.22)
project(prism_llama C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

option(PRISM_VULKAN "Build the Vulkan backend" OFF)

# Все библиотеки — в один каталог, без версионных суффиксов и с поиском зависимостей рядом с собой.
set(CMAKE_PLATFORM_NO_VERSIONED_SONAME ON)
set(CMAKE_BUILD_WITH_INSTALL_RPATH ON)
if(APPLE)
    set(CMAKE_INSTALL_RPATH "@loader_path")
else()
    set(CMAKE_INSTALL_RPATH "$ORIGIN")
endif()
if(NOT ANDROID)
    foreach(kind RUNTIME LIBRARY ARCHIVE)
        set(CMAKE_${kind}_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)
        foreach(config DEBUG RELEASE RELWITHDEBINFO MINSIZEREL)
            set(CMAKE_${kind}_OUTPUT_DIRECTORY_${config} ${CMAKE_BINARY_DIR}/bin)
        endforeach()
    endforeach()
endif()

# Настройки llama.cpp: только библиотека, без сети, инструментов и тестов.
set(BUILD_SHARED_LIBS ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
# libmtmd без инструментов и без видео (видео требует внешний ffmpeg).
set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
set(LLAMA_SUBPROCESS OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL ON CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
# Свой пул потоков ggml вместо OpenMP: никаких внешних рантаймов (libomp, vcomp140).
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ${PRISM_VULKAN} CACHE BOOL "" FORCE)
if(ANDROID)
    set(GGML_SYSTEM_ARCH "ARM" CACHE STRING "" FORCE)
endif()

add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/llama.cpp ${CMAKE_BINARY_DIR}/llama.cpp)

add_library(prism_llama SHARED ${CMAKE_CURRENT_LIST_DIR}/prism_llama.cpp)
target_link_libraries(prism_llama PRIVATE mtmd llama ggml)

if(ANDROID)
    target_link_libraries(prism_llama PRIVATE log)
else()
    find_package(JNI REQUIRED)
    target_include_directories(prism_llama PRIVATE ${JNI_INCLUDE_DIRS})
endif()
