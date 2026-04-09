CXX = g++

ifeq ($(OS),Windows_NT)
  LIB_DIR  = ../../zeta-sdk/zeta-sdk/build/bin/mingwX64/debugShared
  BIN_HTTP = hello-zeta-http.exe
  BIN_WS   = hello-zeta-ws.exe
  RPATH    =
  SHELL    = cmd
  RUN_HTTP = $(foreach line,$(shell type .env),set "$(line)" &&) $(BIN_HTTP)
  RUN_WS   = $(foreach line,$(shell type .env),set "$(line)" &&) $(BIN_WS)
else
  CXX = clang++
  UNAME := $(shell uname -s)
  ifeq ($(UNAME),Darwin)
    LIB_DIR = ../../zeta-sdk/zeta-sdk/build/bin/macosArm64/debugShared
  else
    LIB_DIR = ../../zeta-sdk/zeta-sdk/build/bin/linuxX64/debugShared
  endif
  BIN_HTTP = hello-zeta-http
  BIN_WS   = hello-zeta-ws
  RPATH    = -Wl,-rpath,@executable_path -Wl,-rpath,$(LIB_DIR)
  RUN_HTTP = export $(shell cat .env | xargs) && ./$(BIN_HTTP)
  RUN_WS   = export $(shell cat .env | xargs) && ./$(BIN_WS)
endif

build-http:
	$(CXX) hello-http.cpp -Wno-write-strings -I $(LIB_DIR) -L $(LIB_DIR) -lzeta_sdk $(RPATH) -o $(BIN_HTTP)
ifeq ($(OS),Windows_NT)
	copy $(subst /,\,$(LIB_DIR))\zeta_sdk.dll .
endif

build-ws:
	$(CXX) hello-ws.cpp -Wno-write-strings -I $(LIB_DIR) -L $(LIB_DIR) -lzeta_sdk $(RPATH) -o $(BIN_WS)
ifeq ($(OS),Windows_NT)
	copy $(subst /,\,$(LIB_DIR))\zeta_sdk.dll .
endif

run-http: build-http
	$(RUN_HTTP) ./$(BIN_HTTP)

run-ws: build-ws
	$(RUN_WS) ./$(BIN_WS)

ifeq ($(OS),Windows_NT)
clean:
	del /f $(BIN_HTTP) $(BIN_WS)
else
clean:
	rm -f $(BIN_HTTP) $(BIN_WS)
endif
