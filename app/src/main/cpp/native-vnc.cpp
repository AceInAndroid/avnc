/*
 * Copyright (c) 2020  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

#include <jni.h>
#include <GLES2/gl2.h>
#include <rfb/rfbclient.h>

#include "ClientEx.h"
#include "Utility.h"


/******************************************************************************
 * Library Initialization
 *****************************************************************************/

struct JniContext {
    JavaVM *vm;                     //JVM Instance
    jclass managedCls;              //Managed `VncClient` class
    jmethodID cbFramebufferUpdated; //Cached reference to managed callback

    [[nodiscard]]
    JNIEnv *getEnvUnchecked() const {
        JNIEnv *env = nullptr;

        if (vm != nullptr && vm->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_OK)
            return env;

        return nullptr;
    }

    [[nodiscard]]
    JNIEnv *getEnv() const {
        JNIEnv *env = getEnvUnchecked();
        if (env)
            return env;

        abort(); // Just crash if we can't get JNIEnv
    }
};

static JniContext context{};

static const char *presetName(int preset) {
    return preset == 1 ? "smooth" : preset == 2 ? "hd" : "legacy";
}

static void notifyStreamingPreset(rfbClient *client, int preset, bool success) {
    auto env = context.getEnv();
    auto name = env->NewStringUTF(presetName(preset));
    if (!name) return;
    auto mid = env->GetMethodID(context.managedCls, "cbStreamingPresetApplied", "(Ljava/lang/String;Z)V");
    env->CallVoidMethod(getManagedClient(client), mid, name, success);
    env->DeleteLocalRef(name);
}

static void configureEncodingPreferences(rfbClient *client) {
    auto ex = getClientExtension(client);
    client->appData.enableJPEG = ex->activePreset == 0 ? ex->legacyJPEG : TRUE;
    client->appData.qualityLevel = ex->activePreset == 1 ? 3
                                   : ex->activePreset == 2 ? 9 : ex->legacyQuality;
    client->appData.compressLevel = ex->activePreset == 1 ? 1
                                    : ex->activePreset == 2 ? 6 : ex->legacyCompression;
    if (ex->activePreset == 1)
        client->appData.encodingsString = "tight zrle hextile raw";
    else if (ex->activePreset == 2)
        client->appData.encodingsString = "zrle tight hextile raw";
    else if (!client->isUpdateRectManagedByLib && !ex->legacyEncodings)
        // CopyRect can refer to stale pixels outside the requested monitor.
        client->appData.encodingsString = "tight hextile zlib zrle zywrle ultra corre rre raw";
    else
        client->appData.encodingsString = ex->legacyEncodings;
}

/** Only called by the receiver, between complete RFB server messages. */
static bool applyPendingSessionSettings(rfbClient *client) {
    auto ex = getClientExtension(client);
    int preset, x, y, width, height, desktopWidth, desktopHeight;
    bool region, refresh;
    {
        ScopedMutexLock lock(ex->settingsMutex);
        preset = ex->pendingPreset;
        region = ex->pendingRegion;
        refresh = ex->pendingRefresh;
        x = ex->regionX;
        y = ex->regionY;
        width = ex->regionWidth;
        height = ex->regionHeight;
        desktopWidth = ex->desktopWidth;
        desktopHeight = ex->desktopHeight;
        ex->pendingPreset = -1;
        ex->pendingRegion = ex->pendingRefresh = false;
        ex->desktopWidth = ex->desktopHeight = 0;
    }

    if (preset < 0 && !region && !refresh && desktopWidth <= 0)
        return true;

    const bool wasCropped = !client->isUpdateRectManagedByLib;
    if (region) {
        if (width <= 0 || height <= 0 ||
            (x <= 0 && y <= 0 && width >= client->width && height >= client->height)) {
            rfbClientSetUpdateRect(client, nullptr);
        } else {
            x = x < 0 ? 0 : x >= client->width ? client->width - 1 : x;
            y = y < 0 ? 0 : y >= client->height ? client->height - 1 : y;
            rfbRectangle rect{static_cast<uint16_t>(x), static_cast<uint16_t>(y),
                              static_cast<uint16_t>(width < client->width - x ? width : client->width - x),
                              static_cast<uint16_t>(height < client->height - y ? height : client->height - y)};
            rfbClientSetUpdateRect(client, &rect);
        }
    }
    if (preset >= 0)
        ex->activePreset = preset;

    bool success = true;
    if (preset >= 0 || wasCropped != !client->isUpdateRectManagedByLib) {
        // Decoder quality fields are mutable; rebuild them from desired settings.
        configureEncodingPreferences(client);
        success = SetFormatAndEncodings(client);
    }
    if (success && desktopWidth > 0)
        success = SendExtDesktopSize(client, desktopWidth, desktopHeight);
    if (success && !client->pauseFramebufferUpdates) {
        success = SendFramebufferUpdateRequest(client, client->updateRect.x, client->updateRect.y,
                                                client->updateRect.w, client->updateRect.h, FALSE);
    }
    if (preset >= 0)
        notifyStreamingPreset(client, preset, success);
    return success;
}

/**
 * Called when our library is loaded.
 */
JNIEXPORT jint
JNI_OnLoad(JavaVM *vm, void *) {
    context.vm = vm;

    if (context.getEnvUnchecked() == nullptr)
        return JNI_ERR;

    return JNI_VERSION_1_6;
}

JNIEXPORT void
JNI_OnUnload(JavaVM *, void *) {
    if (context.managedCls) {
        context.getEnv()->DeleteGlobalRef(context.managedCls);
        context.managedCls = nullptr;
    }
}


extern "C"
JNIEXPORT void JNICALL
Java_com_gaurav_avnc_vnc_VncClient_initLibrary(JNIEnv *env, jclass clazz) {
    if (context.managedCls)
        context.getEnv()->DeleteGlobalRef(context.managedCls);

    context.managedCls = (jclass) env->NewGlobalRef(clazz);
    context.cbFramebufferUpdated = env->GetMethodID(context.managedCls, "cbFinishedFrameBufferUpdate", "()V");
    //TODO: Cache more method IDs so we don't have to repeatedly search them

    rfbClientLog = &log_info;
    rfbClientErr = &log_error;
}


/******************************************************************************
 * rfbClient Callbacks
 *****************************************************************************/

static char *onGetPassword(rfbClient *client) {
    auto obj = getManagedClient(client);
    auto env = context.getEnv();
    auto cls = context.managedCls;

    auto mid = env->GetMethodID(cls, "cbGetPassword", "()Ljava/lang/String;");
    auto jPassword = (jstring) env->CallObjectMethod(obj, mid);
    if (env->ExceptionCheck())
        return nullptr;

    return getNativeStrCopy(env, jPassword);
}

static rfbCredential *onGetCredential(rfbClient *client, int credentialType) {
    if (credentialType == rfbCredentialTypeX509) {
        // Return empty credentials here, server certificate will be verified later
        auto credential = (rfbCredential *) malloc(sizeof(rfbCredential));
        memset(credential, 0, sizeof(rfbCredential));
        return credential;
    }

    auto obj = getManagedClient(client);
    auto env = context.getEnv();
    auto cls = context.managedCls;

    //Retrieve credentials
    jmethodID mid = env->GetMethodID(cls, "cbGetCredential",
                                     "()Lcom/gaurav/avnc/vnc/UserCredential;");
    jobject jCredential = env->CallObjectMethod(obj, mid);
    if (!jCredential || env->ExceptionCheck()) {
        return nullptr;
    }

    //Extract username & password
    auto jCredentialCls = env->GetObjectClass(jCredential);
    auto usernameField = env->GetFieldID(jCredentialCls, "username", "Ljava/lang/String;");
    auto jUsername = env->GetObjectField(jCredential, usernameField);

    auto passwordField = env->GetFieldID(jCredentialCls, "password", "Ljava/lang/String;");
    auto jPassword = env->GetObjectField(jCredential, passwordField);

    //Create native rfbCredential
    auto credential = (rfbCredential *) malloc(sizeof(rfbCredential));
    credential->userCredential.username = getNativeStrCopy(env, (jstring) jUsername);
    credential->userCredential.password = getNativeStrCopy(env, (jstring) jPassword);

    return credential;
}

static rfbBool onVerifyServerCertificate(rfbClient *client, const unsigned char *der, int der_len) {
    auto obj = getManagedClient(client);
    auto env = context.getEnv();
    auto cls = context.managedCls;

    jbyteArray bytes = env->NewByteArray(der_len);
    if (!bytes) {
        rfbClientErr("onVerifyServerCertificate: Unable to allocate memory for DER");
        return FALSE;
    }
    env->SetByteArrayRegion(bytes, 0, der_len, reinterpret_cast<const jbyte *>(der));

    jmethodID mid = env->GetMethodID(cls, "cbVerifyServerCertificate", "([B)Z");
    auto result = env->CallBooleanMethod(obj, mid, bytes);
    env->DeleteLocalRef(bytes);
    return result ? TRUE : FALSE;
}

static void onBell(rfbClient *client) {
    auto obj = getManagedClient(client);
    auto env = context.getEnv();
    auto cls = context.managedCls;

    jmethodID mid = env->GetMethodID(cls, "cbBell", "()V");
    env->CallVoidMethod(obj, mid);
}

static void onGotXCutText(rfbClient *client, const char *text, int len, bool is_utf8) {
    if (!text || len <= 0) return;

    auto obj = getManagedClient(client);
    auto env = context.getEnv();
    auto cls = context.managedCls;

    jbyteArray bytes = env->NewByteArray(len);
    if (!bytes) {
        rfbClientErr("onGotXCutText: Unable to allocate memory for cut text");
        return;
    }
    env->SetByteArrayRegion(bytes, 0, len, reinterpret_cast<const jbyte *>(text));

    jmethodID mid = env->GetMethodID(cls, "cbGotXCutText", "([BZ)V");
    env->CallVoidMethod(obj, mid, bytes, is_utf8);
    env->DeleteLocalRef(bytes);
}

static void onGotXCutTextLatin1(rfbClient *client, const char *text, int len) {
    onGotXCutText(client, text, len, false);
}

static void onGotXCutTextUTF8(rfbClient *client, const char *text, int len) {
    if (text && len > 0 && text[len - 1] == '\0')
        --len; // LibVNCClient includes terminating NULL in length for UTF8
    onGotXCutText(client, text, len, true);
}

static rfbBool onHandleCursorPos(rfbClient *client, int x, int y) {
    auto obj = getManagedClient(client);
    auto env = context.getEnv();
    auto cls = context.managedCls;

    jmethodID mid = env->GetMethodID(cls, "cbHandleCursorPos", "(II)V");
    env->CallVoidMethod(obj, mid, x, y);

    return TRUE;
}

static void onFinishedFrameBufferUpdate(rfbClient *client) {
    auto obj = getManagedClient(client);
    auto env = context.getEnv();

    env->CallVoidMethod(obj, context.cbFramebufferUpdated);
}

static void onDesktopLayout(rfbClient *client, int width, int height, int count,
                            const rfbExtDesktopScreen *screens) {
    auto env = context.getEnv();
    auto values = env->NewIntArray(count * 5);
    if (!values) return;
    jint tuples[255 * 5];
    for (int i = 0; i < count; ++i) {
        tuples[i * 5] = static_cast<jint>(screens[i].id);
        tuples[i * 5 + 1] = screens[i].x;
        tuples[i * 5 + 2] = screens[i].y;
        tuples[i * 5 + 3] = screens[i].width;
        tuples[i * 5 + 4] = screens[i].height;
    }
    if (count > 0)
        env->SetIntArrayRegion(values, 0, count * 5, tuples);
    auto mid = env->GetMethodID(context.managedCls, "cbDisplayLayoutChanged", "(II[I)V");
    env->CallVoidMethod(getManagedClient(client), mid, width, height, values);
    env->DeleteLocalRef(values);
}

/**
 * We need to use our own allocator to know when frame size has changed.
 * and to acquire framebuffer lock during modification.
 */
static rfbBool onMallocFrameBuffer(rfbClient *client) {

    const auto width = client->width;
    const auto height = client->height;
    if (width <= 0 || height <= 0)
        return FALSE;
    const auto requestedSize = (uint64_t) width * height * client->format.bitsPerPixel / 8;

    if (requestedSize >= SIZE_MAX) {
        rfbClientErr("CRITICAL: cannot allocate frameBuffer, requested size is too large\n");
        return FALSE;
    }

    auto allocSize = (size_t) requestedSize;
    auto ex = getClientExtension(client);

    LOCK(ex->mutex);
    {

        if (client->frameBuffer)
            free(client->frameBuffer);

        client->frameBuffer = static_cast<uint8_t *>(malloc(allocSize));

        if (client->frameBuffer) {
            ex->fbRealWidth = width;
            ex->fbRealHeight = height;
            memset(client->frameBuffer, 0, allocSize); //Clear any garbage
        } else {
            ex->fbRealWidth = 0;
            ex->fbRealHeight = 0;
        }
    }
    UNLOCK(ex->mutex);

    if (client->frameBuffer == nullptr) {
        rfbClientErr("CRITICAL: frameBuffer allocation failed\n");
        return FALSE;
    }

    auto obj = getManagedClient(client);
    auto env = context.getEnv();
    auto cls = context.managedCls;

    auto mid = env->GetMethodID(cls, "cbFramebufferSizeChanged", "(II)V");
    env->CallVoidMethod(obj, mid, width, height);

    return TRUE;
}

static void onGotCursorShape(rfbClient *client, int xHot, int yHot, int width, int height, int /*bytesPerPixel*/) {
    auto ex = getClientExtension(client);
    auto obj = getManagedClient(client);
    auto env = context.getEnv();
    auto cls = context.managedCls;

    LOCK(ex->mutex);
    updateCursor(ex->cursor, client->rcSource, client->rcMask, (uint16_t) width, (uint16_t) height,
                 (uint16_t) xHot, (uint16_t) yHot);
    UNLOCK(ex->mutex);

    jmethodID mid = env->GetMethodID(cls, "cbHandleCursorInfo", "(IIII)V");
    env->CallVoidMethod(obj, mid, width, height, xHot, yHot);
}

/**
 * Hooks callbacks to rfbClient.
 */
static void setCallbacks(rfbClient *client) {
    client->GetPassword = onGetPassword;
    client->GetCredential = onGetCredential;
    client->VerifyServerCertificate = onVerifyServerCertificate;
    client->Bell = onBell;
    client->GotXCutText = onGotXCutTextLatin1;
    client->GotXCutTextUTF8 = onGotXCutTextUTF8;
    client->HandleCursorPos = onHandleCursorPos;
    client->FinishedFrameBufferUpdate = onFinishedFrameBufferUpdate;
    client->MallocFrameBuffer = onMallocFrameBuffer;
    client->GotCursorShape = onGotCursorShape;
    client->GotDesktopLayout = onDesktopLayout;
    client->LockWriteToServer = [](rfbClient *c) { pthread_mutex_lock(&getClientExtension(c)->writeMutex); };
    client->UnlockWriteToServer = [](rfbClient *c) { pthread_mutex_unlock(&getClientExtension(c)->writeMutex); };
}


/******************************************************************************
 * Native method Implementation
 *****************************************************************************/

extern "C"
JNIEXPORT jlong JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeClientCreate(JNIEnv *env, jobject thiz) {
    rfbClient *client = rfbGetClient(8, 3, 4);
    if (client == nullptr)
        return 0;

    auto ex = assignClientExtension(client);
    if (!ex) {
        rfbClientCleanup(client);
        return 0;
    }

    setCallbacks(client);
    client->canHandleNewFBSize = TRUE;

    //Attach reference to managed object
    auto obj = env->NewGlobalRef(thiz);
    setManagedClient(client, obj);

    return (jlong) client;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeConfigure(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr,
                                                   jint securityType, jboolean use_local_cursor, jint image_quality,
                                                   jboolean use_raw_encoding) {
    auto client = (rfbClient *) client_ptr;

    // 0 means all auth types
    if (securityType != 0) {
        uint32_t auth[1] = {static_cast<uint32_t>(securityType)};
        SetClientAuthSchemes(client, auth, 1);
    }

    if (use_local_cursor) {
        client->appData.useRemoteCursor = TRUE;
        getClientExtension(client)->cursor = newCursor();
    }

    client->appData.qualityLevel = image_quality;
    if (use_raw_encoding)
        client->appData.encodingsString = "raw";

    auto ex = getClientExtension(client);
    ex->legacyQuality = image_quality;
    ex->legacyCompression = client->appData.compressLevel;
    ex->legacyJPEG = client->appData.enableJPEG;
    ex->legacyEncodings = client->appData.encodingsString;

    // Change pixel format to match with the default format used by most VNC
    // servers. Technically, we should not have to this as VNC servers have to
    // respect whatever pixel format client prefers. But there are some wierd
    // servers which ignore pixel format sent by client. As a result, users
    // blame AVNC for not rendering the colors properly.
    // Note: This change affects how OpenGL Texture for framebuffer is rendered,
    // and it only works for 24 bit-per-pixel density.
    client->format.redShift = 16;
    client->format.greenShift = 8;
    client->format.blueShift = 0;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeSetDest(JNIEnv *env, jobject /*thiz*/, jlong client_ptr,
                                                 jstring host, jint port) {
    auto client = (rfbClient *) client_ptr;
    client->destHost = getNativeStrCopy(env, host);
    client->destPort = port;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeInit(JNIEnv *env, jobject /*thiz*/, jlong client_ptr,
                                              jstring host, jint port) {
    auto client = (rfbClient *) client_ptr;

    client->serverHost = getNativeStrCopy(env, host);
    client->serverPort = port < 100 ? port + 5900 : port;

    if (rfbClientConnect(client) && rfbClientInitialise(client)) {
        return JNI_TRUE;
    }

    return JNI_FALSE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeCleanup(JNIEnv *env, jobject /*thiz*/,
                                                 jlong client_ptr) {
    auto client = (rfbClient *) client_ptr;

    if (client->frameBuffer) {
        free(client->frameBuffer);
        client->frameBuffer = nullptr;
    }

    auto managedClient = getManagedClient(client);
    env->DeleteGlobalRef(managedClient);

    client->LockWriteToServer = nullptr;
    client->UnlockWriteToServer = nullptr;
    freeClientExtension(client);
    rfbClientCleanup(client);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeProcessServerMessage(JNIEnv * /*env*/, jobject /*thiz*/,
                                                              jlong client_ptr) {
    auto client = (rfbClient *) client_ptr;

    if (!applyPendingSessionSettings(client))
        return JNI_FALSE;

    auto waitResult = WaitForMessage(client, 100000);

    if (waitResult == 0) // Timeout
        return JNI_TRUE;

    if (waitResult > 0 && HandleRFBServerMessage(client))
        return JNI_TRUE;

    return JNI_FALSE;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeGetLastErrorStr(JNIEnv *env, jobject/*thiz*/) {
    auto str = errnoToStr(errno);
    return env->NewStringUTF(str);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeSendKeyEvent(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr,
                                                      jint key_sym, jint xt_code, jboolean is_down) {
    auto client = (rfbClient *) client_ptr;
    rfbBool down = is_down ? TRUE : FALSE;

    // Remap Alt key to Meta key for MacOS
    auto isServerMacOS = client->serverMajor == 3 && client->serverMinor == 889;
    if (isServerMacOS) {
        if (key_sym == 0xffe9  /* Left alt  */) key_sym = 0xffe7  /* Left meta  */;
        if (key_sym == 0xffea  /* Right alt */) key_sym = 0xffe8  /* Right meta */;
    }

    if (xt_code > 0 && SendExtendedKeyEvent(client, key_sym, xt_code, down))
        return JNI_TRUE;
    else
        return SendKeyEvent(client, key_sym, down);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeSendPointerEvent(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr,
                                                          jint x, jint y, jint mask) {
    return (jboolean) SendPointerEvent((rfbClient *) client_ptr, x, y, mask);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeSendCutText(JNIEnv *env, jobject /*thiz*/, jlong client_ptr, jbyteArray bytes,
                                                     jboolean is_utf8) {
    auto client = (rfbClient *) client_ptr;
    auto textBuffer = env->GetByteArrayElements(bytes, nullptr);
    auto textLen = env->GetArrayLength(bytes);
    auto textChars = reinterpret_cast<char *>(textBuffer);

    rfbBool result = is_utf8
                     ? SendClientCutTextUTF8(client, textChars, textLen)
                     : SendClientCutText(client, textChars, textLen);

    env->ReleaseByteArrayElements(bytes, textBuffer, JNI_ABORT);
    return (jboolean) result;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeIsUTF8CutTextSupported(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr) {
    return (jboolean) (((rfbClient *) client_ptr)->extendedClipboardServerCapabilities != 0);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeSetDesktopSize(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr,
                                                        jint width, jint height) {
    auto ex = getClientExtension((rfbClient *) client_ptr);
    ScopedMutexLock lock(ex->settingsMutex);
    ex->desktopWidth = width;
    ex->desktopHeight = height;
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeSetDesktopRegion(JNIEnv *, jobject, jlong client_ptr,
                                                          jint x, jint y, jint width, jint height) {
    auto ex = getClientExtension((rfbClient *) client_ptr);
    ScopedMutexLock lock(ex->settingsMutex);
    ex->regionX = x;
    ex->regionY = y;
    ex->regionWidth = width;
    ex->regionHeight = height;
    ex->pendingRegion = true;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeSetStreamingPreset(JNIEnv *env, jobject, jlong client_ptr,
                                                            jstring preset) {
    auto value = env->GetStringUTFChars(preset, nullptr);
    if (!value) return JNI_FALSE;
    int id = strcmp(value, "legacy") == 0 ? 0 : strcmp(value, "smooth") == 0 ? 1
                                               : strcmp(value, "hd") == 0 ? 2 : -1;
    env->ReleaseStringUTFChars(preset, value);
    if (id < 0) return JNI_FALSE;
    auto ex = getClientExtension((rfbClient *) client_ptr);
    ScopedMutexLock lock(ex->settingsMutex);
    ex->pendingPreset = id;
    return JNI_TRUE;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeRefreshFrameBuffer(JNIEnv * /*env*/, jobject /*thiz*/, jlong clientPtr) {
    auto ex = getClientExtension((rfbClient *) clientPtr);
    ScopedMutexLock lock(ex->settingsMutex);
    ex->pendingRefresh = true;
    return JNI_TRUE;
}

extern "C"
JNIEXPORT void JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativePauseFramebufferUpdates(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr,
                                                                 jboolean pause) {
    ((rfbClient *) client_ptr)->pauseFramebufferUpdates = pause;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeGetDesktopName(JNIEnv *env, jobject /*thiz*/, jlong client_ptr) {
    auto client = (rfbClient *) client_ptr;
    return env->NewStringUTF(client->desktopName ? client->desktopName : "");
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeIsAppleServer(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr) {
    auto client = (rfbClient *) client_ptr;
    return client->serverMajor == 3 && client->serverMinor == 889;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeGetWidth(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr) {
    return ((rfbClient *) client_ptr)->width;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeGetHeight(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr) {
    return ((rfbClient *) client_ptr)->height;
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeIsEncrypted(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr) {
    return static_cast<jboolean>(((rfbClient *) client_ptr)->tlsSession ? JNI_TRUE : JNI_FALSE);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeUploadFrameTexture(JNIEnv * /*env*/, jobject /*thiz*/,
                                                            jlong client_ptr) {
    auto client = (rfbClient *) client_ptr;
    auto ex = getClientExtension(client);

    LOCK(ex->mutex);

    if (client->frameBuffer) {
        glTexImage2D(GL_TEXTURE_2D,
                     0,
                     GL_RGBA,
                     ex->fbRealWidth,
                     ex->fbRealHeight,
                     0,
                     GL_RGBA,
                     GL_UNSIGNED_BYTE,
                     client->frameBuffer);

        // Note: client->frameBuffer data is actually in 'BGRA' format, instead of 'RGBA'.
        // But OpenGL ES doesn't support that directly. So we use 'GL_RGBA' here, and flip
        // the components to correct order inside fragment shader.
    }

    UNLOCK(ex->mutex);
}

extern "C"
JNIEXPORT void JNICALL
Java_com_gaurav_avnc_vnc_VncClient_nativeUploadCursorTexture(JNIEnv * /*env*/, jobject /*thiz*/, jlong client_ptr) {

    auto client = (rfbClient *) client_ptr;
    auto ex = getClientExtension(client);
    auto cursor = ex->cursor;

    if (!cursor || !cursor->buffer)
        return;

    LOCK(ex->mutex);

    glTexImage2D(GL_TEXTURE_2D,
                 0,
                 GL_RGBA,
                 cursor->width,
                 cursor->height,
                 0,
                 GL_RGBA,
                 GL_UNSIGNED_BYTE,
                 cursor->buffer);

    UNLOCK(ex->mutex);
}
