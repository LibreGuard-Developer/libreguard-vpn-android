cmake_minimum_required(VERSION 3.22.1)

project("strongswan_bridge")

# Add your library
add_library(strongswan_bridge SHARED strongswan_bridge.cpp)

# Find required libraries
find_library(log-lib log)

# Link pre-built StrongSwan libraries - CORRECTED PATHS
add_library(strongswan SHARED IMPORTED)
set_target_properties(strongswan PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../libs/${ANDROID_ABI}/libstrongswan.so)

add_library(charon SHARED IMPORTED)
set_target_properties(charon PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../libs/${ANDROID_ABI}/libcharon.so)

add_library(androidbridge SHARED IMPORTED)
set_target_properties(androidbridge PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../libs/${ANDROID_ABI}/libandroidbridge.so)

# Add other StrongSwan libraries you have
add_library(ipsec SHARED IMPORTED)
set_target_properties(ipsec PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../libs/${ANDROID_ABI}/libipsec.so)

add_library(imcv SHARED IMPORTED)
set_target_properties(imcv PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../libs/${ANDROID_ABI}/libimcv.so)

add_library(tnccs SHARED IMPORTED)
set_target_properties(tnccs PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../libs/${ANDROID_ABI}/libtnccs.so)

add_library(tncif SHARED IMPORTED)
set_target_properties(tncif PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../libs/${ANDROID_ABI}/libtncif.so)

add_library(tpmtss SHARED IMPORTED)
set_target_properties(tpmtss PROPERTIES
        IMPORTED_LOCATION ${CMAKE_SOURCE_DIR}/../libs/${ANDROID_ABI}/libtpmtss.so)

# Add this to your target_link_libraries section
target_link_libraries(strongswan_bridge
        ${log-lib}
        -Wl,--start-group
        strongswan
        charon
        androidbridge
        ipsec
        imcv
        tnccs
        tncif
        tpmtss
        -Wl,--end-group
        dl)  # <-- Add this for dlopen/dlsym functions
