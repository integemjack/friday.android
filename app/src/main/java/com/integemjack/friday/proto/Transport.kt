package com.integemjack.friday.proto

/**
 * 一条到远程 Friday 的传输（REMOTE.md §2）：协议本身只要「双向、有序、可靠的字节流」。
 *
 * 现在只有一种传输：SChat 的 WebRTC 工作通道（[WorkTransport]，DataChannel `friday-work`），
 * 和桌面端 friday-decstop-work 0.3 起走的是同一条路。
 */
interface Transport {
    /** 发一条 JSON-RPC 消息（整条，不带换行；分帧由实现自己处理） */
    fun send(text: String)

    fun close()
}