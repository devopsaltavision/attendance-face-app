cmake_minimum_required(VERSION 3.22.1)

project(x05_safe_probe LANGUAGES C)

add_library(x05_safe_probe SHARED x05_safe_probe.c)

target_compile_options(x05_safe_probe PRIVATE -Werror)
