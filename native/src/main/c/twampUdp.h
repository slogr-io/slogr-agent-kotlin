#ifndef SLOGR_TWAMP_UDP_H
#define SLOGR_TWAMP_UDP_H

#include <jni.h>

#ifdef __cplusplus
extern "C" {
#endif

/* UDP socket lifecycle */
JNIEXPORT jint  JNICALL Java_io_slogr_agent_native_SlogrNative_createSocket(JNIEnv *, jobject);
JNIEXPORT jint  JNICALL Java_io_slogr_agent_native_SlogrNative_createSocket6(JNIEnv *, jobject);
JNIEXPORT void  JNICALL Java_io_slogr_agent_native_SlogrNative_closeSocket(JNIEnv *, jobject, jint);
JNIEXPORT jint  JNICALL Java_io_slogr_agent_native_SlogrNative_bindSocket(JNIEnv *, jobject, jint, jint, jint);
JNIEXPORT jint  JNICALL Java_io_slogr_agent_native_SlogrNative_bindSocket6(JNIEnv *, jobject, jint, jbyteArray, jshort);
JNIEXPORT jint  JNICALL Java_io_slogr_agent_native_SlogrNative_connectSocket(JNIEnv *, jobject, jint, jbyteArray, jshort);
JNIEXPORT jint  JNICALL Java_io_slogr_agent_native_SlogrNative_setReusePort(JNIEnv *, jobject, jint);

/* Socket options */
JNIEXPORT jint  JNICALL Java_io_slogr_agent_native_SlogrNative_setSocketOption(JNIEnv *, jobject, jint, jint);
JNIEXPORT jint  JNICALL Java_io_slogr_agent_native_SlogrNative_setSocketOption6(JNIEnv *, jobject, jint, jint);
JNIEXPORT jint  JNICALL Java_io_slogr_agent_native_SlogrNative_setSocketTos(JNIEnv *, jobject, jint, jshort);
JNIEXPORT jint  JNICALL Java_io_slogr_agent_native_SlogrNative_setSocketTos6(JNIEnv *, jobject, jint, jshort);
JNIEXPORT jint  JNICALL Java_io_slogr_agent_native_SlogrNative_setSocketTimeout(JNIEnv *, jobject, jint, jint);

/* Packet I/O */
JNIEXPORT jint  JNICALL Java_io_slogr_agent_native_SlogrNative_getLocalPort(JNIEnv *, jobject, jint);
JNIEXPORT jint  JNICALL Java_io_slogr_agent_native_SlogrNative_enableTimestamping(JNIEnv *, jobject, jint);
JNIEXPORT jint  JNICALL Java_io_slogr_agent_native_SlogrNative_sendTo(JNIEnv *, jobject, jint, jbyteArray, jshort, jbyteArray, jint);
JNIEXPORT jint  JNICALL Java_io_slogr_agent_native_SlogrNative_recvMsg(JNIEnv *, jobject, jint, jbyteArray, jint, jintArray, jshortArray, jshortArray, jshortArray, jlongArray, jintArray);

#ifdef __cplusplus
}
#endif
#endif /* SLOGR_TWAMP_UDP_H */
