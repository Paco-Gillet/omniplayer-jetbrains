package com.github.pacogillet.omniplayer

import com.sun.jna.platform.win32.BaseTSD
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser

object WindowsMediaController {

    private const val VK_MEDIA_PLAY_PAUSE = 0xB3
    private const val VK_MEDIA_NEXT_TRACK = 0xB0
    private const val VK_MEDIA_PREV_TRACK = 0xB1

    private const val KEYEVENTF_EXTENDEDKEY = 0x0001
    private const val KEYEVENTF_KEYUP = 0x0002

    fun playPause() {
        sendMediaKey(VK_MEDIA_PLAY_PAUSE)
    }

    fun nextTrack() {
        sendMediaKey(VK_MEDIA_NEXT_TRACK)
    }

    fun previousTrack() {
        sendMediaKey(VK_MEDIA_PREV_TRACK)
    }

    private fun sendMediaKey(keyCode: Int) {
        val input = WinUser.INPUT()
        input.type = WinDef.DWORD(WinUser.INPUT.INPUT_KEYBOARD.toLong())

        // Very important with JNA : we need to explicitly tell which part of the "Union" (C structure) we are using.
        input.input.setType("ki")

        input.input.ki.wVk = WinDef.WORD(keyCode.toLong())
        input.input.ki.wScan = WinDef.WORD(0)
        input.input.ki.time = WinDef.DWORD(0)
        input.input.ki.dwExtraInfo = BaseTSD.ULONG_PTR(0)

        // Press key (with the flag EXTENDEDKEY because it's a multimedia key)
        input.input.ki.dwFlags = WinDef.DWORD(KEYEVENTF_EXTENDEDKEY.toLong())

        val count = WinDef.DWORD(1)
        User32.INSTANCE.SendInput(count, arrayOf(input), input.size())

        // Release key
        input.input.ki.dwFlags = WinDef.DWORD((KEYEVENTF_EXTENDEDKEY or KEYEVENTF_KEYUP).toLong())
        User32.INSTANCE.SendInput(count, arrayOf(input), input.size())
    }
}