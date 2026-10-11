package com.aveharrisan.tgwsproxy.core

import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Медиа через Cloudflare одним HTTP/2-соединением на домен (у Flowseal с 04.10.2026, `cf_h2.py`).
 *
 * Раньше каждое медиа-соединение Telegram (их открывается много — по одному на поток загрузки) шло
 * отдельным WebSocket через CF-прокси: своё TCP+TLS-рукопожатие и свой лимит Cloudflare. Теперь пакеты
 * MTProto уходят POST-запросами на `https://kwsN.<домен>/api` (HTTP-транспорт MTProto), и все они
 * мультиплексируются внутри одного HTTP/2-соединения. Основные соединения остаются на WS.
 *
 * Тонкость HTTP-транспорта: сервер отвечает только на запрос. Если ответ на пакет задержался или сервер
 * хочет прислать что-то ещё, пакет повторяется («replay»): дубликат msg_id сервер не исполняет повторно,
 * но в ответ на него отдаёт накопленные сообщения.
 */
internal object CfH2 {
    const val MAX_PACKET = 4 * 1024 * 1024
    const val MAX_PLAIN_PADDING = 256
    const val MAX_CHANNEL_REQUESTS = 8
    const val MAX_CHANNEL_BYTES = 8L * 1024 * 1024
    const val MAX_LANE_REQUESTS = 64
    const val MAX_LANE_BYTES = 32L * 1024 * 1024
    const val MAX_LANE_REPLY_BYTES = 32L * 1024 * 1024
    const val MAX_QUEUED_REPLIES = 16
    const val DOMAIN_COOLDOWN_MS = 30_000L
    const val REQUEST_TIMEOUT_MS = 65_000L
    const val SETUP_TIMEOUT_MS = 8_000L
    const val REPLAY_IDLE_MS = 1_000L
    const val REPLAY_REQUEST_MS = 3_000L
    const val REPLAY_CHECK_MS = 250L
    const val REPLAY_MAX_AGE_MS = 30_000L
    const val REPLAY_HISTORY_BYTES = 64 * 1024
    const val REPLAY_HISTORY_PACKETS = 16
    const val MAX_CHANNEL_REPLAYS = 2
    const val REPLAY_SLOT_MS = 30_000L
    const val REPLAY_RETRY_MS = 2_000L
    const val REPLAY_MAX_ATTEMPTS = 3

    fun nowMs(): Long = System.nanoTime() / 1_000_000

    /** Ошибка, которую Telegram должен получить кодом (-404 и т.п.), а не просто обрывом. */
    class TransportError(val code: Int, source: String) : IOException("$code ($source)")

    /** Пакет MTProto от клиента: расшифровать, снять рамку транспорта (abridged/intermediate/padded). */
    fun readPacket(input: InputStream, dec: AesCtr, tag: Int): ByteArray {
        fun plain(n: Int): ByteArray = dec.update(readExactly(input, n))
        val length: Int
        if (tag == Proto.ABRIDGED) {
            val first = plain(1)[0].toInt() and 0xFF
            var words = first and 0x7F
            if (words == 0x7F) words = Obfuscation.leInt(plain(3) + byteArrayOf(0), 0)
            length = words * 4
        } else {
            length = Obfuscation.leInt(plain(4), 0) and 0x7FFFFFFF
        }
        val limit = MAX_PACKET + if (tag == Proto.PADDED_INTERMEDIATE) MAX_PLAIN_PADDING else 0
        if (length < 24 || length > limit) throw IOException("длина пакета вне допустимого: $length")
        var body = plain(length)
        if (tag == Proto.PADDED_INTERMEDIATE) {
            val unencrypted = (0 until 8).all { body[it] == 0.toByte() }
            val packetLength: Int
            val maxPadding: Int
            if (unencrypted) {
                packetLength = 20 + Obfuscation.leInt(body, 16)
                maxPadding = MAX_PLAIN_PADDING
            } else {
                packetLength = 24 + ((length - 24) / 16) * 16
                maxPadding = 15
            }
            val padding = length - packetLength
            if (packetLength < 24 || packetLength > length || padding > maxPadding)
                throw IOException("неверное выравнивание пакета: $length/$packetLength")
            body = body.copyOf(packetLength)
        }
        if (body.size % 4 != 0 || body.size > MAX_PACKET) throw IOException("пакет не выровнен по 4 байта")
        return body
    }

    /** Ответ сервера в рамку транспорта клиента (до шифрования). */
    fun encodeReply(body: ByteArray, tag: Int): ByteArray {
        require(body.isNotEmpty() && body.size % 4 == 0 && body.size <= MAX_PACKET) { "неверная длина ответа: ${body.size}" }
        if (tag == Proto.ABRIDGED) {
            val words = body.size / 4
            val prefix = if (words < 0x7F) byteArrayOf(words.toByte())
            else byteArrayOf(0x7F, words.toByte(), (words shr 8).toByte(), (words shr 16).toByte())
            return prefix + body
        }
        val padding = if (tag == Proto.PADDED_INTERMEDIATE) Rnd.bytes((Rnd.bytes(1)[0].toInt() and 0xFF) % 4) else ByteArray(0)
        val n = body.size + padding.size
        return byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()) + body + padding
    }

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r < 0) throw java.io.EOFException()
            off += r
        }
        return out
    }

    /**
     * Клиент (Telegram) <-> канал HTTP/2. Отправка идёт в этом потоке, приём и повторы — в своих.
     * Заканчивается, когда закрылась любая сторона.
     */
    fun bridge(clt: ClientConn, channel: H2Channel, ctx: CryptoCtx, tag: Int) {
        val writeLock = Any()
        val done = AtomicBoolean(false)
        fun writeNative(data: ByteArray) = synchronized(writeLock) {
            clt.output.write(ctx.cltEnc.update(data)); clt.output.flush()
        }
        fun finish() {
            if (!done.compareAndSet(false, true)) return
            channel.close()
            channel.transportError?.let { code ->
                runCatching { writeNative(encodeReply(intLe(code), tag)); Stats.bytesDown.addAndGet(4) }
            }
            clt.close()
        }
        val download = Thread({
            try {
                while (true) {
                    val body = channel.receive()
                    channel.delivering = true
                    try { writeNative(encodeReply(body, tag)) } finally { channel.delivering = false }
                    Stats.bytesDown.addAndGet(body.size.toLong())
                    channel.onDelivered()
                }
            } catch (e: Exception) {
                if (!channel.closed || e !is H2Closed) Log.d("[${channel.label}] H2 channel=${channel.id} приём закончен: $e")
            } finally { finish() }
        }, "h2-down-${channel.id}").apply { isDaemon = true; start() }
        val recover = Thread({
            try { channel.recoverLoop() } catch (e: Exception) { Log.d("[${channel.label}] H2 channel=${channel.id} повторы: $e") }
            finally { finish() }
        }, "h2-recover-${channel.id}").apply { isDaemon = true; start() }
        try {
            while (!channel.closed) {
                val body = readPacket(clt.input, ctx.cltDec, tag)
                if (!channel.send(body, replay = false)) break
            }
        } catch (e: Exception) {
            if (!done.get() && !channel.closed) Log.d("[${channel.label}] H2 channel=${channel.id} отправка закончена: $e")
        } finally {
            finish()
            download.join(1000); recover.join(1000)
        }
    }

    fun intLe(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
}

internal class H2Closed(msg: String = "H2 channel closed") : IOException(msg)

/** Один повторяемый пакет: когда отправлен, сколько раз повторён, какие запросы его несут. */
internal class ReplayPacket(val sentAt: Long, val body: ByteArray, val sequence: Int) {
    var attempts = 0
    var lastAttemptAt = 0L
    var pending: H2Request? = null
    val originals = HashSet<H2Request>()
    var retired = false
    var replyId: ByteArray? = null
    val replayed: Boolean get() = lastAttemptAt > 0 || attempts > 0
}

/** Один POST. sentAt — когда тело ушло целиком, receiving — пришли заголовки ответа, читаем тело. */
internal class H2Request(val body: ByteArray, val replay: Boolean, val packet: ReplayPacket?) {
    val startedAt = CfH2.nowMs()
    @Volatile var sentAt: Long? = null
    @Volatile var receiving = false
    @Volatile var call: Call? = null
    @Volatile var cancelled = false
    @Volatile var done = false

    fun cancel() { cancelled = true; call?.cancel() }
}

/** Общий пул: одна «полоса» (lane) на хост kwsN.<домен>, внутри — одно HTTP/2-соединение. */
internal class CfH2Pool {
    private val exec = Executors.newCachedThreadPool { r ->
        Thread(r, "h2-req").apply {
            isDaemon = true
            setUncaughtExceptionHandler { t, e -> Log.e("Ошибка в потоке ${t.name}", e) }
        }
    }
    val client: OkHttpClient = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
        .proxy(Proxy.NO_PROXY)
        .connectTimeout(CfH2.SETUP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(CfH2.REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .writeTimeout(CfH2.REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .eventListenerFactory {
            object : EventListener() {
                override fun requestBodyEnd(call: Call, byteCount: Long) {
                    call.request().tag(H2Request::class.java)?.sentAt = CfH2.nowMs()
                }
            }
        }
        .build()

    private val lanes = ConcurrentHashMap<String, H2Lane>()
    private val locks = ConcurrentHashMap<String, Any>()
    private val failedUntil = ConcurrentHashMap<String, Long>()
    private val setupRetryAfter = ConcurrentHashMap<Int, Long>()
    private val nextLane = AtomicInteger()
    private val nextChannel = AtomicInteger()
    @Volatile var closed = false
        private set

    fun submit(task: () -> Unit) = exec.execute(task)

    /** Канал для медиа-соединения или null — тогда обычный WS через CF-прокси. */
    fun open(dc: Int, label: String): H2Channel? {
        if (closed) return null
        val now = CfH2.nowMs()
        if (now < (setupRetryAfter[dc] ?: Long.MIN_VALUE)) return null
        if (dc !in Proto.DC_DEFAULT_IPS) return null
        val deadline = now + CfH2.SETUP_TIMEOUT_MS
        for (base in Balancer.domainsForDc(dc)) {
            if (CfH2.nowMs() >= deadline) {
                setupRetryAfter[dc] = CfH2.nowMs() + CfH2.DOMAIN_COOLDOWN_MS
                Log.w("[$label] H2 DC$dc: подключение не успело за ${CfH2.SETUP_TIMEOUT_MS / 1000} с — медиа пока через WS")
                return null
            }
            val host = "kws$dc.$base"
            synchronized(locks.getOrPut(host) { Any() }) {
                if (closed) return null
                var lane = lanes[host]
                val t = CfH2.nowMs()
                if (t < maxOf(failedUntil[host] ?: Long.MIN_VALUE, lane?.failedUntil ?: Long.MIN_VALUE)) return@synchronized
                if (lane == null) {
                    val fresh = H2Lane(this, host, nextLane.incrementAndGet())
                    try {
                        fresh.preflight(deadline - t)
                    } catch (e: Exception) {
                        Stats.h2Errors.incrementAndGet()
                        failedUntil[host] = CfH2.nowMs() + CfH2.DOMAIN_COOLDOWN_MS
                        Log.d("[$label] H2 ${DomainCensor.apply(host)} не подошёл: ${e.javaClass.simpleName}: ${e.message}")
                        return@synchronized
                    }
                    if (closed) return null
                    lanes[host] = fresh
                    lane = fresh
                }
                val ch = H2Channel(lane, nextChannel.incrementAndGet(), label).also { it.submitter = ::submit }
                if (Balancer.updateDomainForDc(dc, base))
                    Log.i("[$label] DC$dc media CF-прокси: активный домен сменён на ${DomainCensor.apply(base)}")
                Log.d("[$label] DC$dc media -> HTTP/2 lane=${lane.id} channel=${ch.id} host=${DomainCensor.apply(host)} channels=${lane.channelCount}")
                return ch
            }
        }
        Log.d("[$label] H2 DC$dc: ни один домен не подошёл — медиа через WS")
        return null
    }

    fun close() {
        closed = true
        lanes.values.forEach { it.close() }
        lanes.clear()
        exec.shutdownNow()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

/** Все каналы к одному хосту: общий лимит запросов/байт, общий отказ домена на 30 с. */
internal class H2Lane(private val pool: CfH2Pool, val host: String, val id: Int) {
    @Volatile var failedUntil = Long.MIN_VALUE
    @Volatile var closed = false
    private val lock = ReentrantLock()
    private val capacity = lock.newCondition()
    private var inflight = 0
    private var queuedBytes = 0L
    private val replyBufferBytes = java.util.concurrent.atomic.AtomicLong()
    val channels: MutableSet<H2Channel> = ConcurrentHashMap.newKeySet()
    val channelCount: Int get() = channels.size
    private val url = "https://$host/api"

    fun preflight(timeoutMs: Long) {
        val call = pool.client.newCall(Request.Builder().url(url).head().build())
        call.timeout().timeout(maxOf(timeoutMs, 1000), TimeUnit.MILLISECONDS)
        call.execute().use { r ->
            if (r.protocol != Protocol.HTTP_2) throw IOException("CF /api: ${r.protocol} вместо HTTP/2")
            if (!(r.code in 200..299 || r.code == 405 || r.code == 501))
                throw IOException("CF /api HTTP ${r.code} cf_ray=${r.header("cf-ray") ?: "-"}")
        }
    }

    fun hasCapacity(length: Int): Boolean = inflight < CfH2.MAX_LANE_REQUESTS && queuedBytes + length <= CfH2.MAX_LANE_BYTES

    /** Занять место под запрос. wait=false — сразу false, если места нет (для повторов). */
    fun reserve(length: Int, wait: Boolean): Boolean = lock.withLock {
        if (!wait && !hasCapacity(length)) return false
        var left = TimeUnit.MILLISECONDS.toNanos(CfH2.REQUEST_TIMEOUT_MS)
        while (!closed && !hasCapacity(length)) {
            if (left <= 0) throw IOException("H2 lane: нет места под запрос ${CfH2.REQUEST_TIMEOUT_MS / 1000} с")
            left = capacity.awaitNanos(left)
        }
        if (closed) throw H2Closed("H2 lane closed")
        inflight++; queuedBytes += length
        true
    }

    fun release(length: Int) = lock.withLock {
        inflight--; queuedBytes -= length
        capacity.signalAll()
    }

    fun hasCapacityLocked(length: Int): Boolean = lock.withLock { hasCapacity(length) }

    /** Отправить пакет и дождаться ответа целиком. */
    fun post(req: H2Request, channel: H2Channel): ByteArray {
        val request = Request.Builder().url(url)
            .post(req.body.toRequestBody(OCTET))
            .header("Accept-Encoding", "identity")
            .tag(H2Request::class.java, req)
            .build()
        val call = pool.client.newCall(request)
        req.call = call
        if (req.cancelled) call.cancel()
        Stats.h2Requests.incrementAndGet()
        var buffered = 0L
        try {
            call.execute().use { r ->
                req.receiving = true
                channel.lastProgress = CfH2.nowMs()
                val ray = r.header("cf-ray") ?: "-"
                if (r.protocol != Protocol.HTTP_2) throw IOException("CF /api: ${r.protocol} вместо HTTP/2")
                if (r.code in TRANSPORT_CODES) throw CfH2.TransportError(-r.code, "HTTP ${r.code} cf_ray=$ray")
                if (r.code != 200) throw IOException("CF /api HTTP ${r.code} cf_ray=$ray")
                if ((r.header("content-type") ?: "").contains("text/html", ignoreCase = true))
                    throw IOException("CF /api ответил HTML вместо MTProto")
                val declared = r.header("content-length")?.toLongOrNull()
                if (declared != null && declared !in 0..CfH2.MAX_PACKET.toLong())
                    throw IOException("ответ больше допустимого пакета")
                val out = ByteArrayOutputStream()
                val src = r.body!!.byteStream()
                val buf = ByteArray(65536)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    if (out.size() + n > CfH2.MAX_PACKET) throw IOException("ответ больше допустимого пакета")
                    if (replyBufferBytes.addAndGet(n.toLong()) > CfH2.MAX_LANE_REPLY_BYTES) {
                        buffered += n
                        throw ReplyBufferFull()
                    }
                    buffered += n
                    out.write(buf, 0, n)
                    channel.lastProgress = CfH2.nowMs()
                }
                val content = out.toByteArray()
                if (declared != null && (r.header("content-encoding") ?: "identity") == "identity" && content.size.toLong() != declared)
                    throw IOException("ответ пришёл не целиком")
                if (content.isNotEmpty() && content.size % 4 != 0) throw IOException("ответ CF /api не выровнен по 4 байта")
                if (content.size == 4) {
                    val code = Obfuscation.leInt(content, 0)
                    if (code < 0) throw CfH2.TransportError(code, "HTTP 200 payload")
                }
                return content
            }
        } catch (e: CfH2.TransportError) {
            throw e
        } catch (e: ReplyBufferFull) {
            throw e
        } catch (e: IOException) {
            if (!req.cancelled) failedUntil = CfH2.nowMs() + CfH2.DOMAIN_COOLDOWN_MS
            throw e
        } finally {
            replyBufferBytes.addAndGet(-buffered)
            req.receiving = false
        }
    }

    fun close() {
        closed = true
        lock.withLock { capacity.signalAll() }
        channels.toList().forEach { it.close() }
    }

    class ReplyBufferFull : IOException("H2 lane: буфер ответов переполнен")

    private companion object {
        val OCTET = "application/octet-stream".toMediaType()
        val TRANSPORT_CODES = setOf(403, 404, 429, 444)
    }
}

/**
 * Одно медиа-соединение Telegram внутри полосы: до 8 запросов в полёте, ответы — в порядке прихода,
 * повторы зависших и «опрос» сервера повтором последнего пакета (как `_HttpChannel` у Flowseal).
 */
internal class H2Channel(val lane: H2Lane, val id: Int, val label: String) {
    private val lock = Object()
    private val sendLock = ReentrantLock()
    private val pending = LinkedHashMap<H2Request, Int>()
    private var pendingBytes = 0L
    private val replayPending = HashSet<H2Request>()
    private val history = ArrayList<ReplayPacket>()
    private var replayKey: ByteArray? = null
    private var requests = 0
    private val queue = LinkedBlockingQueue<Any>()
    @Volatile private var replyBytes = 0L
    @Volatile var delivering = false
    @Volatile var closed = false
        private set
    @Volatile var transportError: Int? = null
        private set
    @Volatile var lastProgress = CfH2.nowMs()
    private val wake = Object()
    @Volatile private var wakeFlag = false
    /** Отправка ждёт места: «channel» или «lane» — кто мешает; повтор тогда может освободить слот. */
    @Volatile private var uploadWaitPhase: String? = null
    @Volatile private var uploadWaitBytes = 0

    init { lane.channels += this }

    private val nativeBackpressure: Boolean get() = replyBytes > 0 || delivering

    private fun hasCapacity(length: Int) = pending.size < CfH2.MAX_CHANNEL_REQUESTS && pendingBytes + length <= CfH2.MAX_CHANNEL_BYTES

    private fun wakeRecover() = synchronized(wake) { wakeFlag = true; wake.notifyAll() }

    private fun wakeReceiver() {
        if (!closed && synchronized(lock) { pending.isEmpty() } && !nativeBackpressure) wakeRecover()
    }

    fun onDelivered() { lastProgress = CfH2.nowMs(); wakeReceiver() }

    private fun fail(error: Exception) {
        synchronized(lock) {
            if (closed) return
            if (error is CfH2.TransportError) transportError = error.code
            closed = true
            queue.clear()
            replyBytes = 0
            queue.put(error)
            lock.notifyAll()
        }
        wakeRecover()
    }

    /** Ответ сервера для клиента; бросает ошибку канала. */
    fun receive(): ByteArray {
        val v = queue.take()
        if (v is Exception) throw v
        v as ByteArray
        synchronized(lock) { replyBytes -= v.size }
        wakeReceiver()
        return v
    }

    /** false — канал закрыт (для повтора — «сейчас не время»). */
    fun send(body: ByteArray, replay: Boolean): Boolean {
        if (replay) {
            if (!sendLock.tryLock()) return false
        } else sendLock.lock()
        try {
            if (!replay) { uploadWaitPhase = "channel"; uploadWaitBytes = body.size }
            synchronized(lock) {
                if (replay && !hasCapacity(body.size)) return false
                if (!replay) {
                    val deadline = CfH2.nowMs() + CfH2.REQUEST_TIMEOUT_MS
                    while (!closed && !hasCapacity(body.size)) {
                        val left = deadline - CfH2.nowMs()
                        if (left <= 0) throw IOException("H2 channel: нет места под запрос")
                        lock.wait(left)
                    }
                }
                if (closed) return false
            }
            if (!replay) uploadWaitPhase = "lane"
            if (!lane.reserve(body.size, wait = !replay)) return false
            val req: H2Request
            synchronized(lock) {
                if (closed) { lane.release(body.size); return false }
                val now = CfH2.nowMs()
                val replayPacket = if (replay) findPacket(body) else null
                if (replay && (replayPacket == null || replayPacket.pending != null || !replayDue(replayPacket, now))) {
                    lane.release(body.size); return false
                }
                val nativePacket = if (!replay) rememberPacket(body, now) else null
                requests++
                req = H2Request(body, replay, replayPacket ?: nativePacket)
                pending[req] = body.size
                pendingBytes += body.size
                if (!replay) Stats.bytesUp.addAndGet(body.size.toLong())
                if (nativePacket != null) { nativePacket.originals += req; trimHistory(now) }
                if (replayPacket != null) {
                    replayPending += req
                    replayPacket.attempts++
                    replayPacket.lastAttemptAt = now
                    replayPacket.pending = req
                    Stats.h2Replays.incrementAndGet()
                }
            }
            poolSubmit { runRequest(req) }
            return true
        } finally {
            if (!replay) { uploadWaitPhase = null; uploadWaitBytes = 0 }
            sendLock.unlock()
        }
    }

    private fun poolSubmit(task: () -> Unit) = try { submitter(task) } catch (e: Exception) { fail(H2Closed()) }

    /** Через пул полосы, чтобы потоки запросов закрывались вместе с ним. */
    var submitter: (() -> Unit) -> Unit = { t -> Thread(t, "h2-req-$id").apply { isDaemon = true }.start() }


    private fun findPacket(body: ByteArray) = history.firstOrNull { it.body.contentEquals(body) }

    private fun rememberPacket(body: ByteArray, now: Long): ReplayPacket? {
        val key = body.copyOf(8)
        if (key.all { it == 0.toByte() }) return null
        if (!key.contentEquals(replayKey)) { history.clear(); replayKey = key }
        if (body.size > CfH2.REPLAY_HISTORY_BYTES) return null
        return findPacket(body) ?: ReplayPacket(now, body, requests).also { history += it }
    }

    private fun trimHistory(now: Long) {
        var retained = history.sumOf { it.body.size }
        while (history.size > CfH2.REPLAY_HISTORY_PACKETS || retained > CfH2.REPLAY_HISTORY_BYTES) {
            val victim = history.firstOrNull { e ->
                e.originals.isEmpty() && !(e.retired && e.attempts < CfH2.REPLAY_MAX_ATTEMPTS && now - e.sentAt <= CfH2.REPLAY_MAX_AGE_MS)
            } ?: history[0]
            history.remove(victim)
            retained -= victim.body.size
        }
    }

    private fun runRequest(req: H2Request) {
        try {
            val reply = lane.post(req, this)
            lastProgress = CfH2.nowMs()
            if (reply.isNotEmpty() && !closed) synchronized(lock) {
                val p = req.packet
                val rid = reply.copyOf(minOf(24, reply.size))
                if (p != null && !rid.contentEquals(p.replyId)) { p.replyId = rid; p.attempts = 0 }
                if (queue.size >= CfH2.MAX_QUEUED_REPLIES || replyBytes + reply.size > CfH2.MAX_CHANNEL_BYTES)
                    throw IOException("H2: очередь ответов клиенту переполнена")
                replyBytes += reply.size
                queue.put(reply)
            }
        } catch (e: Exception) {
            if (req.cancelled && e !is CfH2.TransportError) {
                // Отменили сами (повтор вытеснил запрос или канал закрыт) — не ошибка канала.
            } else {
                Stats.h2Errors.incrementAndGet()
                if (!closed) {
                    if (e is CfH2.TransportError) Log.w("[$label] H2 lane=${lane.id} channel=$id: ошибка транспорта MTProto ${e.message} — передаю клиенту")
                    else Log.w("[$label] H2 lane=${lane.id} channel=$id запрос не прошёл: ${e.javaClass.simpleName}: ${e.message}; " +
                        "закрываю соединение, Telegram переподключится")
                }
                fail(e)
            }
        } finally {
            finishRequest(req)
        }
    }

    private fun finishRequest(req: H2Request) {
        synchronized(lock) {
            if (req.done) return
            req.done = true
            val length = pending.remove(req) ?: return
            pendingBytes -= length
            replayPending -= req
            val p = req.packet ?: history.firstOrNull { req in it.originals || it.pending === req }
            if (p != null) {
                p.originals -= req
                if (p.pending === req) p.pending = null
            }
            lane.release(length)
            lock.notifyAll()
        }
        wakeReceiver()
    }

    private fun overdueOriginal(e: ReplayPacket, now: Long): Boolean = e.retired || e.originals.any { r ->
        val sent = r.sentAt
        sent != null && !r.receiving && now - sent >= CfH2.REPLAY_REQUEST_MS
    }

    private fun replayLimit(e: ReplayPacket): Int =
        if (e.originals.isNotEmpty() || e.retired || e === history.lastOrNull()) CfH2.REPLAY_MAX_ATTEMPTS else 0

    /** null — пакет пора повторить; иначе причина подождать. */
    private fun replayBlocker(e: ReplayPacket, now: Long): String? {
        if (now - e.sentAt !in 0..CfH2.REPLAY_MAX_AGE_MS) return "expired"
        if (e.attempts >= replayLimit(e)) return "budget-used"
        if (nativeBackpressure) return "native-backpressure"
        if (e.originals.any { it.receiving }) return "original-body"
        if (e.pending?.receiving == true) return "replay-body"
        if (e.replayed && now - e.lastAttemptAt < CfH2.REPLAY_RETRY_MS) return "backoff"
        if (overdueOriginal(e, now)) return null
        if (e !== history.lastOrNull() || pending.keys.any { it !== e.pending }) return "receiver-active"
        if (e.replayed && now - lastProgress < CfH2.REPLAY_IDLE_MS) return "backoff"
        return null
    }

    private fun replayDue(e: ReplayPacket, now: Long) = replayBlocker(e, now) == null

    /** Кандидат на вытеснение, чтобы отправке нашлось место: давно отправленный и без ответа. */
    private fun capacityCandidate(now: Long): Pair<H2Request, ReplayPacket?>? {
        val ordered = pending.keys.filter { it.sentAt != null }
            .sortedWith(compareBy({ it !in replayPending }, { it.sentAt }))
        for (r in ordered) {
            if (r.done || r.receiving || now - r.sentAt!! < CfH2.REPLAY_REQUEST_MS) continue
            if (r in replayPending) return r to null
            val e = history.firstOrNull { r in it.originals }
            if (e != null && now - e.sentAt <= CfH2.REPLAY_MAX_AGE_MS) return r to e
        }
        return null
    }

    private fun makeUploadRoom(now: Long) {
        val phase = uploadWaitPhase ?: return
        val bytes = uploadWaitBytes
        val channels = when (phase) {
            "channel" -> if (synchronized(lock) { hasCapacity(bytes) }) return else listOf(this)
            "lane" -> if (lane.hasCapacityLocked(bytes)) return else lane.channels.toList()
            else -> return
        }
        for (ch in channels) {
            val victim = synchronized(ch.lock) {
                ch.capacityCandidate(now)?.also { (r, e) -> r.sentAt = null; e?.retired = true }
            } ?: continue
            victim.first.cancel()
            return
        }
    }

    /** Фоновый цикл повторов: раз в 250 мс или по сигналу. */
    fun recoverLoop() {
        while (!closed) {
            synchronized(wake) { if (!wakeFlag) wake.wait(CfH2.REPLAY_CHECK_MS); wakeFlag = false }
            if (closed) break
            val now = CfH2.nowMs()
            makeUploadRoom(now)
            if (closed || nativeBackpressure) continue
            var toSend: ReplayPacket? = null
            var toCancel: H2Request? = null
            synchronized(lock) {
                val candidates = history.sortedWith(compareBy<ReplayPacket>({ !overdueOriginal(it, now) },
                    { if (overdueOriginal(it, now)) it.sequence else -it.sequence }))
                for (e in candidates) {
                    if (!replayDue(e, now)) continue
                    var replace = e.pending
                    if (replace == null && replayPending.size >= CfH2.MAX_CHANNEL_REPLAYS) {
                        replace = pending.keys.firstOrNull { it in replayPending && !it.receiving } ?: continue
                    }
                    if (replace != null) {
                        val sent = replace.sentAt
                        if (replace.receiving || sent == null || now - sent < CfH2.REPLAY_SLOT_MS) continue
                        toCancel = replace
                    }
                    toSend = e
                    break
                }
            }
            toCancel?.let { r ->
                r.cancel()
                // Отменённый запрос освобождает слот в своём потоке — ждём, но недолго.
                val until = CfH2.nowMs() + 2000
                while (!r.done && CfH2.nowMs() < until) Thread.sleep(10)
            }
            toSend?.let { send(it.body, replay = true) }
        }
    }

    fun close() {
        fail(H2Closed())
        val reqs = synchronized(lock) { pending.keys.toList() }
        reqs.forEach { it.cancel() }
        synchronized(lock) { history.clear() }
        lane.channels -= this
    }
}
