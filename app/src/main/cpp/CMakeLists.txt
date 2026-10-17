cmake_minimum_required(VERSION 3.22.1)
project(nettools C)

add_library(nettools SHARED traceroute.c)
target_compile_options(nettools PRIVATE -Wall -Wextra -Wno-unused-parameter)
