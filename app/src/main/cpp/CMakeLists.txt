cmake_minimum_required(VERSION 3.22.1)

project("threadaffinity")

add_library(${CMAKE_PROJECT_NAME} SHARED
    native-lib.cpp
    affinity_manager.cpp
    cpu_monitor.cpp
    root_shell.cpp
)

target_link_libraries(${CMAKE_PROJECT_NAME}
    android
    log
)
