# Custom FindPython3 for Android cross-compilation
# Points to Anaconda Python on Windows host

set(Python3_FOUND TRUE)
set(Python3_EXECUTABLE "C:/ProgramData/anaconda3/python.exe" CACHE FILEPATH "" FORCE)
message(STATUS "Python3 found (host): ${Python3_EXECUTABLE}")
