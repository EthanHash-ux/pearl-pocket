#include <jni.h>
#include <stdlib.h>
#include <string.h>
extern char *PearlExecute(char *input);
JNIEXPORT jstring JNICALL Java_ai_pearl_wallet_NativeCore_execute(JNIEnv *env, jclass type, jstring input) {
    (void)type;
    const char *request = (*env)->GetStringUTFChars(env, input, NULL);
    if (request == NULL) return NULL;
    char *response = PearlExecute((char *)request);
    (*env)->ReleaseStringUTFChars(env, input, request);
    if (response == NULL) return NULL;
    jstring result = (*env)->NewStringUTF(env, response);
    size_t length = strlen(response);
    volatile char *memory = response;
    for (size_t i = 0; i < length; i++) memory[i] = 0;
    free(response);
    return result;
}
