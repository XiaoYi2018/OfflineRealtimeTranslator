# Custom FindOpenCL for Android cross-compilation (Adreno)
# Uses pre-set OpenCL_INCLUDE_DIRS and OpenCL_LIBRARIES variables

if (OpenCL_INCLUDE_DIRS AND OpenCL_LIBRARIES)
    set(OpenCL_FOUND TRUE)
    if (NOT TARGET OpenCL::OpenCL)
        add_library(OpenCL::OpenCL SHARED IMPORTED)
        set_target_properties(OpenCL::OpenCL PROPERTIES
            IMPORTED_LOCATION "${OpenCL_LIBRARIES}"
            INTERFACE_INCLUDE_DIRECTORIES "${OpenCL_INCLUDE_DIRS}"
        )
    endif()
    message(STATUS "OpenCL found (Android/Adreno): ${OpenCL_LIBRARIES}")
else()
    set(OpenCL_FOUND FALSE)
    message(FATAL_ERROR "OpenCL_INCLUDE_DIRS and OpenCL_LIBRARIES must be set for Android builds")
endif()
