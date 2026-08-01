cmake_minimum_required(VERSION 3.22.1)
project(node_bridge CXX)

# Prebuilt Node.js runtime for this ABI (shipped under jniLibs).
set(NODE_LIB_DIR ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})
set(NODE_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/node-headers/node)

add_library(node SHARED IMPORTED)
set_target_properties(node PROPERTIES
        IMPORTED_LOCATION ${NODE_LIB_DIR}/libnode.so
)

add_library(node_bridge SHARED node_bridge.cpp)

target_include_directories(node_bridge PRIVATE ${NODE_INCLUDE_DIR})
target_link_libraries(node_bridge node)
