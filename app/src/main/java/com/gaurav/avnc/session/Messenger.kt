/*
 * Copyright (c) 2026  Gaurav Ujjwal.
 *
 * SPDX-License-Identifier:  GPL-3.0-or-later
 *
 * See COPYING.txt for more details.
 */

package com.gaurav.avnc.session

import android.graphics.PointF
import android.util.Log
import com.gaurav.avnc.vnc.PointerButton
import com.gaurav.avnc.vnc.VncClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Allows sending different types of messages to remote server.
 */
class Messenger(private val client: VncClient) {

    /**************************************************************************
     * Sender thread
     **************************************************************************/
    private val sender = Executors.newSingleThreadExecutor()

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
                sender.execute(action)
                return true
            }
        } catch (e: Exception) {
            Log.w("Messenger", "Failed to enqueue action [isShutdown: ${sender.isShutdown}]: ${e.message}")
        }
        return false
    }

    fun shutdown() {
        runCatching {
            sender.shutdown()
            sender.awaitTermination(60, TimeUnit.SECONDS)
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
