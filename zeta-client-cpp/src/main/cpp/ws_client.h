#ifndef WS_CLIENT_H
#define WS_CLIENT_H

typedef struct {
    void* zetaSdkWsSession;
} ZetaSdk_WSSession;

typedef enum {
    WS_TEXT,
    WS_BINARY,
    WS_CLOSE
} ZetaSdk_WsMessageType;

typedef struct {
    char* text;
    int size;
} ZetaSdk_WSMessage_Text;

typedef struct {
    char* bytes;
    int size;
} ZetaSdk_WSMessage_Binary;

typedef struct {
    ZetaSdk_WsMessageType type;
    union {
        ZetaSdk_WSMessage_Text text;
        ZetaSdk_WSMessage_Binary binary;
    } data;
} ZetaSdk_WSMessage;

typedef void (ZetaSdk_WSHandler)(ZetaSdk_WSSession*);

#endif
