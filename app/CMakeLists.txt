
cmake_minimum_required(VERSION 3.13)

###############################################################################
# vcpkg setup
# This must be done before project() is called.
###############################################################################
if (ANDROID_ABI STREQUAL "arm64-v8a")
    set(VCPKG_TARGET_TRIPLET "arm64-android")
elseif (ANDROID_ABI STREQUAL "armeabi-v7a")
    set(VCPKG_TARGET_TRIPLET "arm-neon-android")
elseif (ANDROID_ABI STREQUAL "x86")
    set(VCPKG_TARGET_TRIPLET "x86-android")
elseif (ANDROID_ABI STREQUAL "x86_64")
    set(VCPKG_TARGET_TRIPLET "x64-android")
else ()
    message(FATAL_ERROR "Unknown ANDROID_ABI: ${ANDROID_ABI}")
endif ()

set(VCPKG_BOOTSTRAP_OPTIONS "-disableMetrics")
set(ENV{ANDROID_NDK_HOME} ${ANDROID_NDK})


###############################################################################
# Project
###############################################################################
project(NativeVNC C CXX)

set(AVNC_EXTERN_DIR ${PROJECT_SOURCE_DIR}/../extern)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "Build shared Libs" FORCE)

###############################################################################
# Utilities
###############################################################################

# Make sure given submodule is checked out
macro(avnc_check_submodule name)
    if (NOT EXISTS "${AVNC_EXTERN_DIR}/${name}/CMakeLists.txt")
        message(FATAL_ERROR "git submodule for ${name} is not initialized.
                             Please run 'git submodule update --init'.")
    endif ()
endmacro()


# Clear build id in release builds to allow reproducible builds on F-Droid
add_link_options("LINKER:$<$<NOT:$<CONFIG:Debug>>:--build-id=none>")


###############################################################################
# LibVNC
###############################################################################
avnc_check_submodule(libvncserver)

set(AVNC_LIBVNC_SRC_DIR ${CMAKE_BINARY_DIR}/libvncserver-source)
set(AVNC_LIBVNC_BUILD_DIR ${CMAKE_BINARY_DIR}/libvncserver)
set(WITH_LIBSSH2 OFF CACHE BOOL "Find LibSSH" FORCE)

# Keep upstream's submodule clean. Apply our tracked protocol changes to a fresh
# build-local copy on configure, identically for every ABI and clean checkout.
set(AVNC_LIBVNC_PATCHES
    ${PROJECT_SOURCE_DIR}/src/main/cpp/patches/libvncclient-session.patch
    ${PROJECT_SOURCE_DIR}/src/main/cpp/patches/libvncclient-connection.patch)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${AVNC_LIBVNC_PATCHES})
file(REMOVE_RECURSE ${AVNC_LIBVNC_SRC_DIR})
file(MAKE_DIRECTORY ${AVNC_LIBVNC_SRC_DIR})
file(COPY ${AVNC_EXTERN_DIR}/libvncserver/ DESTINATION ${AVNC_LIBVNC_SRC_DIR}
     PATTERN ".git" EXCLUDE)
find_package(Git REQUIRED)
foreach(AVNC_LIBVNC_PATCH IN LISTS AVNC_LIBVNC_PATCHES)
    # The copy is nested inside AVNC's checkout. Stop Git discovering the parent
    # repository, where diff --git paths would otherwise be silently skipped.
    execute_process(COMMAND ${CMAKE_COMMAND} -E env "GIT_CEILING_DIRECTORIES=${CMAKE_BINARY_DIR}"
                            ${GIT_EXECUTABLE} apply ${AVNC_LIBVNC_PATCH}
                    WORKING_DIRECTORY ${AVNC_LIBVNC_SRC_DIR}
                    RESULT_VARIABLE AVNC_LIBVNC_PATCH_RESULT
                    ERROR_VARIABLE AVNC_LIBVNC_PATCH_ERROR)
    if (NOT AVNC_LIBVNC_PATCH_RESULT EQUAL 0)
        message(FATAL_ERROR "Cannot apply ${AVNC_LIBVNC_PATCH}: ${AVNC_LIBVNC_PATCH_ERROR}")
    endif ()
endforeach()

add_subdirectory(${AVNC_LIBVNC_SRC_DIR} ${AVNC_LIBVNC_BUILD_DIR})  # (source dir, build dir)

include_directories(${AVNC_LIBVNC_SRC_DIR}/include ${AVNC_LIBVNC_BUILD_DIR}/include)


###############################################################################
# Native VNC
#
# It contains implementation of JNI native methods, some NDK scaffolding and
# some helpers for OpenGL ES rendering. This is the library loaded from Java.
###############################################################################
set(AVNC_NATIVE_SOURCE src/main/cpp/native-vnc.cpp)

add_library(native-vnc SHARED ${AVNC_NATIVE_SOURCE})
# NativeVNC deliberately builds without a C++ runtime (ANDROID_STL=none).
target_compile_options(native-vnc PRIVATE -fno-exceptions -fno-rtti)

target_link_libraries(native-vnc vncclient)


# Link NDK libraries
find_library(LIB_LOG log)
target_link_libraries(native-vnc ${LIB_LOG})

find_library(LIB_GLES GLESv2)
target_link_libraries(native-vnc ${LIB_GLES})
