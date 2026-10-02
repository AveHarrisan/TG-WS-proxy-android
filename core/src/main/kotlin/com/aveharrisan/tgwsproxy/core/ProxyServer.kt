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

    private val wsBlacklist = ConcurrentHashMap.newKeySet<String>()
    private val dcFailUntil = ConcurrentHashMap<String, Double>()
    private val ipFailUntil = ConcurrentHashMap<String, Double>()
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
        wsBlacklist.clear(); dcFailUntil.clear(); ipFailUntil.clear()
        server = bind()
        running = true

        CfDomains.startRefresh(sched, config)
        logBanner()
        sched.scheduleWithFixedDelay({
            val bl = wsBlacklist.sorted().joinToString(", ") { "DC$it" }.ifEmpty { "none" }
            Log.d("stats: ${Stats.summary()} | ws_bl: $bl")
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
        wsBlacklist.clear(); dcFailUntil.clear(); ipFailUntil.clear()
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
            val dcKey = "$dc${if (isTestDc) "t" else ""}${if (isMedia) "m" else ""}"
            val mediaTag = if (isMedia) " media" else ""
            val t = now()
            val wsPath = if (isTestDc) Proto.WS_PATH_TEST else Proto.WS_PATH
            val target = c.dcRedirects[dc]
            val anyCf = c.fallbackCfProxy || c.cfProxyWorkerDomains.isNotEmpty()
            val domains = Proto.wsDomains(dc, isMedia)
            var ws: RawWebSocket? = null
            fun splitter() = runCatching { MsgSplitter(relayInit, hs.protoTag) }.getOrNull()

            if (target == null || dcKey in wsBlacklist || (t < (ipFailUntil[target] ?: 0.0) && anyCf)) {
                when {
                    target == null -> Log.d("[$label] DC$dc нет в настройках -> fallback")
                    dcKey in wsBlacklist -> Log.d("[$label] DC$dc$mediaTag WS в чёрном списке -> fallback")
                    else -> {
                        ws = if (!isTestDc) wsPool.get(dc, isMedia, target, domains) else null
                        Log.d("[$label] DC$dc$mediaTag WS до $target недавно не отвечал" +
                            if (ws == null) " -> fallback" else ", но есть готовое соединение в пуле")
                    }
                }
                if (ws == null) {
                    if (!bridge.doFallback(init.conn, relayInit, label, dc, isTestDc, isMedia, ctx, splitter()))
                        Log.w("[$label] DC$dc$mediaTag обходных путей не осталось")
                    return
                }
            }
            target!!

            val wsTimeout = if (t < (dcFailUntil[dcKey] ?: 0.0)) WS_FAIL_TIMEOUT_MS else 5000
            var failedRedirect = false
            var timedOut = false
            var allRedirects = true

            if (ws == null && !isTestDc) ws = wsPool.get(dc, isMedia, target, domains)
            if (ws != null) Log.d("[$label] DC$dc$mediaTag -> pool hit via $target")
            else for (domain in domains) {
                Log.d("[$label] DC$dc$mediaTag -> wss://$domain$wsPath via $target")
                try {
                    // Напрямую, а при неудаче — фронтингом (раньше фронтинг умел только пул).
                    ws = wsPool.connect(target, domain, wsTimeout, wsPath)
                    allRedirects = false
                    break
                } catch (e: WsHandshakeError) {
                    Stats.wsErrors.incrementAndGet()
                    if (e.isRedirect) {
                        failedRedirect = true
                        Log.w("[$label] DC$dc$mediaTag got ${e.statusCode} from $domain -> ${e.location ?: "?"}")
                        continue
                    }
                    allRedirects = false
                    Log.w("[$label] DC$dc$mediaTag WS handshake: ${e.statusLine}")
                } catch (e: Exception) {
                    Stats.wsErrors.incrementAndGet()
                    if (RawWebSocket.isTimeout(e)) {
                        timedOut = true
                        Log.w("[$label] DC$dc$mediaTag WS connect timed out via $domain")
                        break
                    }
                    allRedirects = false
                    Log.w("[$label] DC$dc$mediaTag WS connect failed: $e")
                }
            }

            if (ws == null) {
                if (timedOut) {
                    ipFailUntil[target] = t + IP_FAIL_COOLDOWN
                    Log.i("[$label] DC$dc$mediaTag $target не отвечает, пауза ${IP_FAIL_COOLDOWN.toInt()}s")
                }
                if (failedRedirect && allRedirects) {
                    wsBlacklist += dcKey
                    Log.w("[$label] DC$dc$mediaTag WS в чёрном списке (все 302)")
                } else {
                    dcFailUntil[dcKey] = t + DC_FAIL_COOLDOWN
                    if (!failedRedirect) Log.i("[$label] DC$dc$mediaTag WS не работает, пауза ${DC_FAIL_COOLDOWN.toInt()}s")
                }
                if (bridge.doFallback(init.conn, relayInit, label, dc, isTestDc, isMedia, ctx, splitter()))
                    Log.i("[$label] DC$dc$mediaTag fallback закрыт")
                return
            }

            ipFailUntil.remove(target)
            wsPool.reportSuccess(dc, isMedia)
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

    companion object {
        const val IP_FAIL_COOLDOWN = 300.0
        const val DC_FAIL_COOLDOWN = 60.0
        const val WS_FAIL_TIMEOUT_MS = 2000
    }
}
