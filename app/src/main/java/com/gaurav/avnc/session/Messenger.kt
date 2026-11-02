/*
 * Copyright (c) 2026  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.session

import android.graphics.PointF
import android.os.SystemClock
import android.util.Log
import com.gaurav.avnc.vnc.PointerButton
import com.gaurav.avnc.vnc.VncClient
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Allows sending different types of messages to remote server.
 */
class Messenger(private val client: VncClient, private val onError: (Throwable) -> Unit = {}) {

    /**************************************************************************
     * Sender thread
     **************************************************************************/
    private val sender = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(512))
    private val failed = AtomicBoolean(false)

    private val queueLock = Any()
    private data class PointerMove(var x: Int, var y: Int)
    private var pendingMove: PointerMove? = null
    private var refreshPending = false

    private fun execute(action: Runnable): Boolean = synchronized(queueLock) {
        // Every non-movement message is an ordering barrier.
        pendingMove = null
        enqueue(action)
    }

    private fun enqueue(action: Runnable): Boolean {
        try {
            if (client.connected && !sender.isShutdown) {
                val queuedAt = SystemClock.elapsedRealtime()
                sender.execute send@{
                    if (!client.connected || failed.get()) return@send
                    try {
                        // Disconnect rather than replay stale key/button transitions or drop an up.
                        if (SystemClock.elapsedRealtime() - queuedAt > 2000)
                            throw IOException("Remote input queue stalled")
                        action.run()
                    } catch (error: Exception) {
                        fail(error)
                    }
                }
                return true
            }
        } catch (e: Exception) {
            if (!sender.isShutdown) fail(IOException("Remote input queue is full", e))
        }
        return false
    }

    private fun fail(error: Throwable) {
        if (!failed.compareAndSet(false, true)) return
        sender.shutdownNow()
        runCatching { onError(error) }.onFailure { Log.e("Messenger", "Error reporting send failure", it) }
        client.cancel()
    }

    fun shutdown() {
        runCatching {
            sender.shutdownNow()
            sender.awaitTermination(2, TimeUnit.SECONDS)
        }
        if (!sender.isTerminated)
            Log.w("Messenger", "Unable to shutdown messenger thread")
    }


    /**************************************************************************
     * Input events
     **************************************************************************/

    /**
     * Keeps track of current pointer button state.
     */
    private var pointerButtonMask: Int = 0
    val hasPressedPointerButtons get() = pointerButtonMask and 7 != 0

    private fun sendPointerEvent(mask: Int, p: PointF) {
        val x = p.x.toInt()
        val y = p.y.toInt()
        client.moveClientPointer(x, y)
        execute { client.sendPointerEvent(x, y, mask) }
    }

    fun sendPointerMove(p: PointF) {
        val x = p.x.toInt()
        val y = p.y.toInt()
        client.moveClientPointer(x, y)
        synchronized(queueLock) {
            if (pointerButtonMask != 0) {
                pendingMove = null
                val mask = pointerButtonMask
                enqueue { client.sendPointerEvent(x, y, mask) }
            } else {
                val existing = pendingMove
                if (existing != null) { existing.x = x; existing.y = y }
                else {
                    val move = PointerMove(x, y)
                    pendingMove = move
                    if (!enqueue {
                        val point = synchronized(queueLock) {
                            if (pendingMove === move) pendingMove = null
                            move.x to move.y
                        }
                        client.sendPointerEvent(point.first, point.second, 0)
                    }) pendingMove = null
                }
            }
        }
    }

    fun sendPointerButtonDown(button: PointerButton, p: PointF) {
        if (button == PointerButton.None) { sendPointerMove(p); return }
        pointerButtonMask = pointerButtonMask or button.bitMask
        sendPointerEvent(pointerButtonMask, p)
    }

    fun sendPointerButtonUp(button: PointerButton, p: PointF) {
        pointerButtonMask = pointerButtonMask and button.bitMask.inv()
        sendPointerEvent(pointerButtonMask, p)
    }

    fun sendPointerButtonRelease(p: PointF) {
        if (pointerButtonMask != 0) {
            pointerButtonMask = 0
            sendPointerEvent(pointerButtonMask, p)
        }
    }

    fun sendKey(keySym: Int, xtCode: Int, isDown: Boolean): Boolean {
        return execute { client.sendKeyEvent(keySym, xtCode, isDown) }
    }

    fun setInputDisabled(disabled: Boolean) {
        // Release messages queued by UI observers must arrive before the view-only gate closes.
        execute { client.setInputDisabled(disabled) }
    }

    fun insertButtonUpDelay() {
        execute { runCatching { Thread.sleep(200) } }
    }

    /**************************************************************************
     * Misc
     **************************************************************************/

    fun sendClipboardText(text: String) {
        execute { client.sendCutText(text) }
    }

    fun setDesktopSize(width: Int, height: Int) {
        execute { client.setDesktopSize(width, height) }
    }

    fun refreshFrameBuffer() {
        synchronized(queueLock) {
            if (refreshPending) return
            refreshPending = true
            if (!execute {
                synchronized(queueLock) { refreshPending = false }
                client.refreshFrameBuffer()
            }) refreshPending = false
        }
    }

    fun setDesktopRegion(x: Int, y: Int, width: Int, height: Int) {
        execute { client.setDesktopRegion(x, y, width, height) }
    }

    fun setStreamingPreset(preset: String) {
        execute { client.setStreamingPreset(preset) }
    }

    fun setFrameBufferUpdatesPaused(pause: Boolean) {
        execute { client.setFrameBufferUpdatesPaused(pause) }
    }
}
