package com.aveharrisan.tgwsproxy.core

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Режет поток MTProto-транспорта на отдельные пакеты, чтобы каждый ушёл своим WS-кадром
 * (сервер Telegram ждёт ровно так).
 */
class MsgSplitter(relayInit: ByteArray, private val proto: Int) {
    private val dec = AesCtr(relayInit.copyOfRange(8, 40), relayInit.copyOfRange(40, 56)).also { it.update(ByteArray(64)) }
    private var cipherBuf = ByteArray(0)
    private var plainBuf = ByteArray(0)
    private var disabled = false

    fun split(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        if (disabled) return listOf(chunk)
        cipherBuf += chunk
        plainBuf += dec.update(chunk)
        val parts = ArrayList<ByteArray>()
        var offset = 0
        val len = cipherBuf.size
        while (offset < len) {
            val pl = nextPacketLen(offset, len - offset) ?: break
            if (pl <= 0) {
                parts += cipherBuf.copyOfRange(offset, len)
                offset = len
                disabled = true
                break
            }
            parts += cipherBuf.copyOfRange(offset, offset + pl)
            offset += pl
        }
        if (offset > 0) {
            cipherBuf = cipherBuf.copyOfRange(offset, len)
            plainBuf = plainBuf.copyOfRange(offset, len)
        }
        return parts
    }

    fun flush(): List<ByteArray> {
        if (cipherBuf.isEmpty()) return emptyList()
        val tail = cipherBuf
        cipherBuf = ByteArray(0); plainBuf = ByteArray(0)
        return listOf(tail)
    }

    private fun nextPacketLen(off: Int, avail: Int): Int? {
        if (avail <= 0) return null
        return when (proto) {
            Proto.ABRIDGED -> {
                val first = plainBuf[off].toInt() and 0xFF
                val (payload, header) = if (first == 0x7F || first == 0xFF) {
                    if (avail < 4) return null
                    val l = (plainBuf[off + 1].toInt() and 0xFF) or ((plainBuf[off + 2].toInt() and 0xFF) shl 8) or
                        ((plainBuf[off + 3].toInt() and 0xFF) shl 16)
                    l * 4 to 4
                } else (first and 0x7F) * 4 to 1
                if (payload <= 0) return 0
                if (avail < header + payload) null else header + payload
            }
            Proto.INTERMEDIATE, Proto.PADDED_INTERMEDIATE -> {
                if (avail < 4) return null
                val payload = Obfuscation.leInt(plainBuf, off) and 0x7FFFFFFF
                if (payload <= 0) return 0
                if (avail < 4 + payload) null else 4 + payload
            }
            else -> 0
        }
    }
}

/** Сторона клиента (Telegram): обычный сокет или поток внутри фейкового TLS. */
class ClientConn(val socket: Socket, val input: InputStream, val output: OutputStream) {
    fun close() { runCatching { socket.close() } }
}

class Bridge(private val cfg: () -> ProxyConfig, private val cfWorkerPool: CfWorkerPool) {

    fun doFallback(clt: ClientConn, relayInit: ByteArray, label: String, dc: Int, isTestDc: Boolean,
                   isMedia: Boolean, ctx: CryptoCtx, splitter: MsgSplitter?): Boolean {
        val c = cfg()
        val mediaTag = if (isMedia) " media" else ""
        val dst = (if (isTestDc) Proto.DC_TEST_IPS else Proto.DC_DEFAULT_IPS)[dc]
        val methods = buildList {
            if (c.cfProxyWorkerDomains.isNotEmpty() && dst != null) add("cf_worker")
            if (c.fallbackCfProxy && !isTestDc) add("cf")
            if (dst != null) add("tcp")
        }
        for (m in methods) {
            val ok = when (m) {
                "cf_worker" -> cfWorkerFallback(clt, relayInit, label, ctx, dc, isTestDc, isMedia, dst!!)
                "cf" -> cfProxyFallback(clt, relayInit, label, ctx, dc, isMedia, splitter)
                else -> {
                    Log.i("[$label] DC$dc$mediaTag -> TCP fallback to $dst:443")
                    tcpFallback(clt, dst!!, 443, relayInit, label, ctx)
                }
            }
            if (ok) return true
        }
        return false
    }

    private fun cfWorkerFallback(clt: ClientConn, relayInit: ByteArray, label: String, ctx: CryptoCtx,
                                 dc: Int, isTestDc: Boolean, isMedia: Boolean, dst: String): Boolean {
        val mediaTag = if (isMedia) " media" else ""
        val domains = cfg().cfProxyWorkerDomains
        val pooled = if (isTestDc) null else cfWorkerPool.get(dc, dst, domains)
        val ws = if (pooled != null) {
            Log.i("[$label] DC$dc$mediaTag -> CF worker pool hit via ${pooled.second} for $dst")
            pooled.first
        } else {
            Log.i("[$label] DC$dc$mediaTag -> trying CF worker for $dst")
            cfWorkerPool.connectOne(domains, dst, dc, timeoutMs = 10_000)?.first ?: return false
        }
        Stats.connectionsCfProxy.incrementAndGet()
        ws.send(relayInit)
        bridgeWs(clt, ws, label, ctx, dc, isMedia, null)
        return true
    }

    private fun cfProxyFallback(clt: ClientConn, relayInit: ByteArray, label: String, ctx: CryptoCtx,
                                dc: Int, isMedia: Boolean, splitter: MsgSplitter?): Boolean {
        val mediaTag = if (isMedia) " media" else ""
        Log.i("[$label] DC$dc$mediaTag -> trying CF proxy")
        var ws: RawWebSocket? = null
        var chosen: String? = null
        for (base in Balancer.domainsForDc(dc)) {
            val domain = "kws$dc.$base"
            try {
                ws = RawWebSocket.connect(domain, domain, 10_000, secure = !cfg().disableSecure)
                chosen = base
                break
            } catch (e: Exception) {
                Log.w("[$label] DC$dc$mediaTag CF proxy failed: $e")
            }
        }
        if (ws == null) return false
        if (chosen != null && Balancer.updateDomainForDc(dc, chosen)) Log.i("[$label] Switched active CF domain")
        Stats.connectionsCfProxy.incrementAndGet()
        ws.send(relayInit)
        bridgeWs(clt, ws, label, ctx, dc, isMedia, splitter)
        return true
    }

    private fun tcpFallback(clt: ClientConn, dst: String, port: Int, relayInit: ByteArray, label: String, ctx: CryptoCtx): Boolean {
        val remote = try {
            Socket().also { Net.tune(it); it.connect(InetSocketAddress(dst, port), 10_000) }
        } catch (e: Exception) {
            Log.w("[$label] TCP fallback to $dst:$port failed: $e")
            return false
        }
        Stats.connectionsTcpFallback.incrementAndGet()
        val rOut = remote.getOutputStream()
        rOut.write(relayInit); rOut.flush()
        val rIn = remote.getInputStream()
        val up = Thread({
            pump(clt.input, rOut, true, ctx)
            runCatching { remote.close() }; clt.close()
        }, "tcp-up-$label")
        up.isDaemon = true
        up.start()
        pump(rIn, clt.output, false, ctx)
        runCatching { remote.close() }; clt.close()
        up.join(1000)
        return true
    }

    private fun pump(src: InputStream, dst: OutputStream, isUp: Boolean, ctx: CryptoCtx) {
        val buf = ByteArray(65536)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                if (n == 0) continue
                if (isUp) {
                    Stats.bytesUp.addAndGet(n.toLong())
                    ctx.cltDec.updateInPlace(buf, 0, n); ctx.tgEnc.updateInPlace(buf, 0, n)
                } else {
                    Stats.bytesDown.addAndGet(n.toLong())
                    ctx.tgDec.updateInPlace(buf, 0, n); ctx.cltEnc.updateInPlace(buf, 0, n)
                }
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: Exception) {
        }
    }

    /** Клиент (TCP) <-> Telegram (WS) с перешифровкой в обе стороны. */
    fun bridgeWs(clt: ClientConn, ws: RawWebSocket, label: String, ctx: CryptoCtx,
                 dc: Int?, isMedia: Boolean, splitter: MsgSplitter?) {
        val dcTag = if (dc != null) "DC$dc${if (isMedia) "m" else ""}" else "DC?"
        val start = System.nanoTime()
        var upBytes = 0L; var downBytes = 0L; var upPk = 0; var downPk = 0
        val reason = java.util.concurrent.atomic.AtomicReference("normal")

        val up = Thread({
            val buf = ByteArray(65536)
            try {
                while (true) {
                    val n = clt.input.read(buf)
                    if (n < 0) {
                        reason.compareAndSet("normal", "client: closed")
                        splitter?.flush()?.firstOrNull()?.let { ws.send(it) }
                        break
                    }
                    if (n == 0) continue
                    Stats.bytesUp.addAndGet(n.toLong()); upBytes += n; upPk++
                    ctx.cltDec.updateInPlace(buf, 0, n); ctx.tgEnc.updateInPlace(buf, 0, n)
                    if (splitter != null) {
                        val parts = splitter.split(buf.copyOf(n))
                        when (parts.size) {
                            0 -> {}
                            1 -> ws.send(parts[0])
                            else -> ws.sendBatch(parts)
                        }
                    } else ws.send(buf, 0, n)
                }
            } catch (e: Exception) {
                reason.compareAndSet("normal", "client: ${e.javaClass.simpleName}")
            }
            ws.close()
            clt.close()
        }, "ws-up-$label")
        up.isDaemon = true
        up.start()

        try {
            while (true) {
                val data = ws.recv()
                if (data == null) {
                    reason.compareAndSet("normal", "upstream: ws_close")
                    break
                }
                Stats.bytesDown.addAndGet(data.size.toLong()); downBytes += data.size; downPk++
                ctx.tgDec.updateInPlace(data, 0, data.size); ctx.cltEnc.updateInPlace(data, 0, data.size)
                clt.output.write(data)
                clt.output.flush()
            }
        } catch (e: Exception) {
            reason.compareAndSet("normal", "upstream: ${e.javaClass.simpleName}")
        } finally {
            ws.close()
            clt.close()
            up.join(1000)
            val elapsed = (System.nanoTime() - start) / 1e9
            Log.i("[$label] $dcTag WS session closed (${reason.get()}): ^${humanBytes(upBytes)} ($upPk pkts) " +
                "v${humanBytes(downBytes)} ($downPk pkts) in ${"%.1f".format(elapsed)}s")
        }
    }
}
