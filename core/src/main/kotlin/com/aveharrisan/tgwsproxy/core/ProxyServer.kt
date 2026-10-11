package com.aveharrisan.tgwsproxy.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Локальный MTProto-прокси: Telegram подключается сюда, а мы ведём трафик к датацентрам
 * через WebSocket (kws*.web.telegram.org), при неудаче — через Cloudflare или напрямую по TCP.
 */
class ProxyServer(config: ProxyConfig) {
    @Volatile var config: ProxyConfig = config
        private set

    private val threadNo = AtomicInteger()
    private val io = Executors.newCachedThreadPool(daemon("tgws-io"))
    private val sched: ScheduledExecutorService = Executors.newScheduledThreadPool(2, daemon("tgws-sched"))
    private val wsPool = WsPool({ this.config }, io, sched)
    private val cfWorkerPool = CfWorkerPool({ this.config }, io)
    private val bridge = Bridge({ this.config }, cfWorkerPool)

    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile private var server: ServerSocket? = null
    @Volatile private var running = false
    private var acceptThread: Thread? = null

    val isRunning: Boolean get() = running

    private fun daemon(name: String) = ThreadFactory { r ->
        Thread(r, "$name-${threadNo.incrementAndGet()}").apply {
            isDaemon = true
            // Страховка: ошибка в фоновом потоке прокси пишется в журнал, а не роняет всё приложение.
            setUncaughtExceptionHandler { t, e -> Log.e("Ошибка в потоке ${t.name}", e) }
        }
    }

    private fun now(): Double = System.nanoTime() / 1e9

    /** Открывает порт синхронно (ошибка «порт занят» вылетит сразу), дальше работает в фоне. */
    @Synchronized
    fun start() {
        check(!running) { "Уже запущен" }
        config.validate()
        Net.bufferSize = config.bufferSize
        Stats.reset()
        bridge.reset()
        server = bind()
        running = true

        CfDomains.startRefresh(sched, config)
        logBanner()
        sched.scheduleWithFixedDelay({
            Log.d("stats: ${Stats.summary()}")
        }, 60, 60, TimeUnit.SECONDS)
        wsPool.warmup()
        cfWorkerPool.warmup()

        acceptThread = Thread(::acceptLoop, "tgws-accept").apply { isDaemon = true; start() }
    }

    private fun bind(): ServerSocket = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName(config.host), config.port), 128)
    }

    private fun acceptLoop() {
        while (running) {
            val srv = server ?: break
            try {
                val s = srv.accept()
                clients += s
                io.execute {
                    try { handleClient(s) } finally { clients -= s; runCatching { s.close() } }
                }
            } catch (e: Exception) {
                if (!running) break
                Log.w("Слушающий сокет упал ($e), перезапуск")
                runCatching { srv.close() }
                Thread.sleep(1000)
                try {
                    server = bind()
                    Log.w("Сервер снова слушает ${config.host}:${config.port}")
                } catch (e2: Exception) {
                    Log.e("Не удалось перезапустить сервер", e2)
                    Thread.sleep(5000)
                }
            }
        }
    }

    @Synchronized
    /**
     * Сеть сменилась (Wi-Fi ↔ мобильная): соединения, открытые через старую сеть, мертвы, но об этом
     * никто не знает. Делаем то же, что человек, переподключая интернет: закрываем соединения
     * Telegram (он сразу откроет новые), сбрасываем пул, паузы и накопленные отказы доменов.
     */
    fun onNetworkChanged(what: String) {
        if (!running) return
        Log.i("Сеть сменилась ($what) — переподключаю соединения")
        clients.forEach { runCatching { it.close() } }
        bridge.reset()
        wsPool.reset(); cfWorkerPool.reset(); Balancer.resetHealth()
        wsPool.warmup(); cfWorkerPool.warmup()
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { server?.close() }
        server = null
        CfDomains.stopRefresh()
        clients.forEach { runCatching { it.close() } }
        clients.clear()
        wsPool.reset()
        cfWorkerPool.reset()
        bridge.close()
        sched.shutdownNow()
        io.shutdownNow()
        Log.i("Прокси остановлен. Итог: ${Stats.summary()}")
    }

    private fun logBanner() {
        val c = config
        Log.i("=".repeat(48))
        Log.i("  Telegram MTProto WS Bridge Proxy")
        Log.i("  Слушаю        ${c.host}:${c.port}")
        if (c.fakeTlsDomain.isNotEmpty()) Log.i("  Fake TLS:     ${c.fakeTlsDomain}")
        Log.i("  DC -> IP:")
        c.dcRedirects.toSortedMap().forEach { (dc, ip) -> Log.i("    DC$dc: $ip") }
        if (c.fallbackCfProxy) Log.i("  CF proxy:     вкл (${c.cfProxyUserDomains.joinToString(", ").ifEmpty { "авто" }})")
        if (c.cfProxyWorkerDomains.isNotEmpty()) Log.i("  CF worker:    вкл (${c.cfProxyWorkerDomains.joinToString(", ")})")
        Log.i("=".repeat(48))
    }

    private class Init(val handshake: ByteArray, val conn: ClientConn)

    private fun readClientInit(sock: Socket, label: String): Init? {
        val c = config
        val secret = c.secretBytes
        val input: InputStream = BufferedInputStream(sock.getInputStream(), 64 * 1024)
        val output = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
        sock.soTimeout = 10_000
        val first = input.read()
        if (first < 0) { Log.d("[$label] клиент отключился до рукопожатия"); return null }
        val masking = c.fakeTlsDomain

        if (first == FakeTls.RECORD_HANDSHAKE && masking.isNotEmpty()) {
            val hdrRest = Net.readExactly(input, 4)
            val recLen = ((hdrRest[2].toInt() and 0xFF) shl 8) or (hdrRest[3].toInt() and 0xFF)
            val body = Net.readExactly(input, recLen)
            val hello = byteArrayOf(first.toByte()) + hdrRest + body
            val v = FakeTls.verifyClientHello(hello, secret)
            if (v == null) {
                Log.d("[$label] Fake TLS не прошёл проверку -> маскировка")
                proxyToMaskingDomain(sock, input, output, hello, masking, label)
                return null
            }
            output.write(FakeTls.buildServerHello(secret, v.clientRandom, v.sessionId))
            output.flush()
            val tls = FakeTlsStreams(input, output)
            val hs = Net.readExactly(tls.input, Proto.HANDSHAKE_LEN)
            sock.soTimeout = 0
            return Init(hs, ClientConn(sock, tls.input, tls.output))
        }
        if (masking.isNotEmpty()) {
            output.write("HTTP/1.1 301 Moved Permanently\r\nLocation: https://$masking/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            output.flush()
            return null
        }
        val hs = byteArrayOf(first.toByte()) + Net.readExactly(input, Proto.HANDSHAKE_LEN - 1)
        sock.soTimeout = 0
        return Init(hs, ClientConn(sock, input, output))
    }

    private fun proxyToMaskingDomain(sock: Socket, input: InputStream, output: java.io.OutputStream,
                                     initial: ByteArray, domain: String, label: String) {
        val up = try { Socket().also { it.connect(InetSocketAddress(domain, 443), 10_000) } } catch (e: Exception) {
            Log.w("[$label] маскировка: нет связи с $domain:443: $e"); return
        }
        Stats.connectionsMasked.incrementAndGet()
        sock.soTimeout = 0
        up.use {
            it.getOutputStream().write(initial)
            val t = Thread { runCatching { input.copyTo(it.getOutputStream()) }; runCatching { it.shutdownOutput() } }
            t.isDaemon = true; t.start()
            runCatching { it.getInputStream().copyTo(output); output.flush() }
            t.join(1000)
        }
    }

    private fun handleClient(sock: Socket) {
        Stats.connectionsTotal.incrementAndGet()
        Stats.connectionsActive.incrementAndGet()
        Net.tune(sock)
        var label = "${sock.inetAddress.hostAddress}:${sock.port}"
        try {
            val init = readClientInit(sock, label) ?: return
            val c = config
            val secret = c.secretBytes
            val hs = Obfuscation.tryHandshake(init.handshake, secret)
            if (hs == null) {
                Stats.connectionsBad.incrementAndGet()
                Log.w("[$label] плохое рукопожатие (неверный секрет или протокол)")
                runCatching { val b = ByteArray(4096); while (init.conn.input.read(b) >= 0) {} }
                return
            }
            var dc = hs.dc
            val isMedia = hs.isMedia
            val isTestDc = c.forceTestDc || dc >= 10000
            if (dc >= 10000) { Log.i("[$label] test DC$dc -> DC${dc - 10000}"); dc -= 10000 }
            val dcIdx = if (isMedia) -dc else dc
            Log.d("[$label] handshake ok: DC$dc${if (isMedia) " media" else ""} proto=0x%08X".format(hs.protoTag))

            val relayInit = Obfuscation.generateRelayInit(hs.protoTag, dcIdx)
            val ctx = Obfuscation.buildCryptoCtx(hs.prekeyAndIv, secret, relayInit)
            val mediaTag = if (isMedia) " media" else ""
            fun splitter() = runCatching { MsgSplitter(relayInit, hs.protoTag) }.getOrNull()

            // Прямой WS — только готовое соединение из пула. Пусто — сразу обходной путь, не ждём таймаута:
            // раньше на сети, где IP Telegram закрыт, каждое новое соединение висело 5 с («тупит», 12.10.2026).
            // Пул пополняется в фоне и сам реже стучится к адресу, который не отвечает (как у Flowseal с 04.10.2026).
            val target = c.dcRedirects[dc]
            val ws = if (target != null) wsPool.get(dc, isMedia, isTestDc, target) else null
            if (ws == null) {
                Log.d("[$label] DC$dc$mediaTag ${if (target == null) "нет в настройках" else "в пуле нет готового WS"} -> fallback")
                if (bridge.doFallback(init.conn, relayInit, label, dc, isTestDc, isMedia, ctx, splitter(), hs.protoTag))
                    Log.d("[$label] DC$dc$mediaTag fallback закрыт")
                else Log.w("[$label] DC$dc$mediaTag обходных путей не осталось")
                return
            }
            Log.d("[$label] DC$dc$mediaTag -> WS pool hit via $target")
            Stats.connectionsWs.incrementAndGet()
            ws.send(relayInit)
            bridge.bridgeWs(init.conn, ws, label, ctx, dc, isMedia, splitter())
        } catch (e: SocketTimeoutException) {
            Log.w("[$label] таймаут рукопожатия")
        } catch (e: EOFException) {
            Log.d("[$label] клиент отключился")
        } catch (e: SocketException) {
            Log.d("[$label] соединение сброшено: ${e.message}")
        } catch (e: Exception) {
            Log.e("[$label] непредвиденная ошибка", e)
        } finally {
            Stats.connectionsActive.decrementAndGet()
        }
    }

}
