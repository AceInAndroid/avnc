/*
 * Copyright (c) 2022  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

#ifndef AVNC_CLIENTEX_H
#define AVNC_CLIENTEX_H

#include <jni.h>
#include <pthread.h>
#include "Cursor.h"

// This target deliberately uses ANDROID_STL=none.
class ScopedMutexLock {
    pthread_mutex_t *mutex;
public:
    explicit ScopedMutexLock(pthread_mutex_t &value) : mutex(&value) {
        pthread_mutex_lock(mutex);
    }
    ~ScopedMutexLock() { pthread_mutex_unlock(mutex); }
    ScopedMutexLock(const ScopedMutexLock &) = delete;
    ScopedMutexLock &operator=(const ScopedMutexLock &) = delete;
};

/**
 * We attach some additional data to every rfbClient.
 * ClientEx is used as wrapper for this data.
 */
struct ClientEx {
    // Reference to managed `VncClient`
    jobject managedClient;

    // Although frame width & height are maintained in rfbClient, those values
    // are modified before our MallocFrameBuffer callback is triggered, and
    // we cannot protect them with a mutex. So we maintain the framebuffer
    // size here, protected with fbMutex.
    int fbRealWidth;
    int fbRealHeight;

    // Cursor data used for client-side cursor rendering
    Cursor *cursor;

    // Protects modification to framebuffer & cursor
    MUTEX(mutex);

    // Network output is serialized independently from framebuffer decoding.
    pthread_mutex_t writeMutex;
    pthread_mutex_t settingsMutex;
    int legacyQuality;
    int legacyCompression;
    bool legacyJPEG;
    const char *legacyEncodings;
    int activePreset; // 0: legacy, 1: smooth, 2: hd
    int pendingPreset;
    bool pendingRegion;
    int regionX, regionY, regionWidth, regionHeight;
    bool pendingRefresh;
    int desktopWidth, desktopHeight;
};

const int ClientExTag = 1;

ClientEx *getClientExtension(rfbClient *client) {
    return (ClientEx *) rfbClientGetClientData(client, (void *) &ClientExTag);
}

void setClientExtension(rfbClient *client, ClientEx *ex) {
    rfbClientSetClientData(client, (void *) &ClientExTag, ex);
}

/**
 * Returns reference to managed `VncClient` associated with given rfbClient.
 */
jobject getManagedClient(rfbClient *client) {
    return getClientExtension(client)->managedClient;
}

/**
 * Associate given rfbClient & managed `VncClient`.
 */
void setManagedClient(rfbClient *client, jobject managedClient) {
    getClientExtension(client)->managedClient = managedClient;
}

/**
 * Create new ClientEx and assign it to given client.
 */
ClientEx *assignClientExtension(rfbClient *client) {
    auto ex = (ClientEx *) calloc(1, sizeof(ClientEx));
    if (!ex) return nullptr;
    pthread_mutexattr_t attributes;
    if (pthread_mutexattr_init(&attributes) != 0) {
        free(ex);
        return nullptr;
    }
    const int recursiveResult = pthread_mutexattr_settype(&attributes, PTHREAD_MUTEX_RECURSIVE);
    const int writeResult = recursiveResult == 0 ? pthread_mutex_init(&ex->writeMutex, &attributes) : recursiveResult;
    pthread_mutexattr_destroy(&attributes);
    if (writeResult != 0) {
        free(ex);
        return nullptr;
    }
    if (pthread_mutex_init(&ex->settingsMutex, nullptr) != 0) {
        pthread_mutex_destroy(&ex->writeMutex);
        free(ex);
        return nullptr;
    }
    INIT_MUTEX(ex->mutex);
    ex->legacyQuality = 5;
    ex->legacyCompression = 1;
    ex->legacyJPEG = true;
    ex->pendingPreset = -1;
    setClientExtension(client, ex);
    return ex;
}

/**
 * Free all resources related to client extension.
 */
void freeClientExtension(rfbClient *client) {
    auto ex = getClientExtension(client);
    if (ex) {
        TINI_MUTEX(ex->mutex);
        pthread_mutex_destroy(&ex->settingsMutex);
        pthread_mutex_destroy(&ex->writeMutex);
        freeCursor(ex->cursor);
        free(ex);
        setClientExtension(client, nullptr);
    }
}

#endif //AVNC_CLIENTEX_H
