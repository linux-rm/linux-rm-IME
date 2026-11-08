set(BOOST_DIR "${CMAKE_SOURCE_DIR}/boost")

if(NOT EXISTS "${BOOST_DIR}/CMakeLists.txt")
  message(FATAL_ERROR
    "Local Boost not found at: ${BOOST_DIR}\n"
    "Please manually extract the boost-*-cmake.tar.xz "
    "into ${CMAKE_SOURCE_DIR}/boost (must contain CMakeLists.txt).")
endif()

set(BOOST_INCLUDE_LIBRARIES
    algorithm
    crc
    dll
    interprocess
    range
    regex
    scope_exit
    signals2
    utility
    uuid)

add_subdirectory("${BOOST_DIR}" boost EXCLUDE_FROM_ALL)