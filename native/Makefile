# Makefile for libslogr-native.so
#
# Phase 0: stub — builds an empty shared library to verify the toolchain.
# Phase 2: twampUdp.c and traceroute.c are added here.
#
# Usage:
#   make                   — build for current platform (Linux amd64 glibc)
#   make ARCH=arm64        — cross-compile for arm64
#   make MUSL=1            — compile against musl libc (for Alpine Docker)
#   make clean             — remove build artifacts
#
# Requires:
#   - JDK 21 at $JAVA_HOME (for JNI headers)
#   - gcc or musl-gcc (for musl builds)

JAVA_HOME  ?= /usr/lib/jvm/java-21-amazon-corretto
CC         ?= gcc
ARCH       ?= $(shell uname -m)
BUILD_DIR  := build
LIB_NAME   := libslogr-native.so
OUT        := $(BUILD_DIR)/$(LIB_NAME)

CFLAGS     := -shared -fPIC -O2 -Wall -Wextra \
              -I$(JAVA_HOME)/include \
              -I$(JAVA_HOME)/include/linux \
              -Isrc/main/c

SRCS       := src/main/c/twampUdp.c src/main/c/traceroute.c

ifeq ($(MUSL),1)
CC := musl-gcc
OUT := $(BUILD_DIR)/libslogr-native-musl.so
endif

.PHONY: all clean

all: $(BUILD_DIR) $(OUT)

$(BUILD_DIR):
	mkdir -p $(BUILD_DIR)

$(OUT): $(SRCS)
ifeq ($(SRCS),)
	# Phase 0 stub: produce an empty shared library
	echo "void slogr_stub(void) {}" | $(CC) -x c -shared -fPIC -o $@ -
else
	$(CC) $(CFLAGS) -o $@ $(SRCS)
endif

clean:
	rm -rf $(BUILD_DIR)
