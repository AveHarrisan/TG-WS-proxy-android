package com.aveharrisan.tgwsproxy.core

/** Консольный запуск ядра (для отладки на компьютере). Ключи — как у исходного tg-ws-proxy. */
fun main(args: Array<String>) {
    var cfg = ProxyConfig()
    val dcIps = ArrayList<String>()
    var noDcIp = false
    var probe = false
    val cfDomains = ArrayList<String>()
    val workerDomains = ArrayList<String>()
    var i = 0
    fun next(): String = args.getOrNull(++i) ?: error("Нет значения для ${args[i - 1]}")
    while (i < args.size) {
        when (val a = args[i]) {
            "--host" -> cfg = cfg.copy(host = next())
            "--port" -> cfg = cfg.copy(port = next().toInt())
            "--secret" -> cfg = cfg.copy(secret = next().lowercase())
            "--dc-ip" -> { val v = args.getOrNull(i + 1); if (v == null || v.startsWith("--")) noDcIp = true else { dcIps += v; i++ } }
            "--pool-size" -> cfg = cfg.copy(poolSize = next().toInt().coerceAtLeast(0))
            "--buf-kb" -> cfg = cfg.copy(bufferSize = next().toInt().coerceAtLeast(4) * 1024)
            "--cfproxy-domain" -> cfDomains += next()
            "--cfproxy-worker-domain" -> workerDomains += next()
            "--no-cfproxy" -> cfg = cfg.copy(fallbackCfProxy = false)
            "--no-secure" -> cfg = cfg.copy(disableSecure = true)
            "--fake-tls-domain" -> cfg = cfg.copy(fakeTlsDomain = next().trim())
            "--force-test-dc" -> cfg = cfg.copy(forceTestDc = true)
            "--probe" -> probe = true
            "-v", "--verbose" -> Log.minLevel = Level.DEBUG
            else -> error("Неизвестный ключ $a")
        }
        i++
    }
    cfg = cfg.copy(
        dcRedirects = if (noDcIp) emptyMap() else if (dcIps.isEmpty()) cfg.dcRedirects else DcIpParser.parse(dcIps),
        cfProxyUserDomains = Domains.coerceList(cfDomains),
        cfProxyWorkerDomains = Domains.coerceList(workerDomains),
    )
    Log.censorDomains = false
    if (probe) {
        for (r in Diagnostics.run(cfg)) println("%-13s %-22s %-4s %5d мс  %s".format(r.method, r.target, if (r.ok) "OK" else "НЕТ", r.ms, r.detail))
        return
    }
    Log.addSink { _, line -> println(line) }
    val server = ProxyServer(cfg)
    server.start()
    Log.i("Ссылка: ${cfg.link()}")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}
