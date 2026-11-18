cmake_minimum_required(VERSION 3.22.1)
project(carlink_usb LANGUAGES CXX)
add_library(carlink_usb SHARED usb_reset.cpp)
