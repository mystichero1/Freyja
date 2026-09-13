cmake_minimum_required(VERSION 3.22.1)

project("freyja")

add_library(
    freyja
    SHARED
    freyja-jni.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    freyja
    ${log-lib}
)
