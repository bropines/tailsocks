LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE := byedpi
LOCAL_C_INCLUDES := $(LOCAL_PATH)/byedpi_core

# Find all files and exclude win_service.c
ALL_SRC := $(wildcard $(LOCAL_PATH)/byedpi_core/*.c)
SRC_EXCLUDE := $(LOCAL_PATH)/byedpi_core/win_service.c
FILTERED_SRC := $(filter-out $(SRC_EXCLUDE), $(ALL_SRC))

LOCAL_SRC_FILES := $(FILTERED_SRC:$(LOCAL_PATH)/%=%) byedpi-jni.c

LOCAL_CFLAGS := -std=c99 -O3 -Wall -Wno-unused -Wextra -Wno-unused-parameter -pedantic -DANDROID_APP
LOCAL_LDLIBS := -llog -landroid

include $(BUILD_SHARED_LIBRARY)
