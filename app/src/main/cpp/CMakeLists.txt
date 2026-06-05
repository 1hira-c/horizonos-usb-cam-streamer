cmake_minimum_required(VERSION 3.22.1)

project(uvcnative)

add_library(uvcnative SHARED native-lib.cpp)

target_link_libraries(uvcnative log)
