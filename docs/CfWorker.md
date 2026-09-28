# Свой Cloudflare Worker

Бесплатный запасной путь до Telegram: ваш личный Worker на Cloudflare передаёт подключения к серверам Telegram. Домен покупать не нужно.

**Зачем, если в приложении уже есть CF-прокси?** Общие домены CF-прокси делят все пользователи, и Cloudflare ограничивает число одновременных подключений. Когда домены перегружены, они отвечают ошибкой 503, и Telegram подвисает. Свой Worker — только ваш.

Если на аккаунте без Premium через Worker всё равно не грузятся фото и видео, оставьте в «Настройки → Датацентры» только строку `4:149.154.167.220`.

> [!WARNING]
> Проверено 28.09.2026: через Worker проходят маленькие ответы Telegram, а крупные (например, при открытии канала) могут застревать — у оригинального прокси на компьютере то же самое. Проверка связи при этом зелёная, потому что она только открывает соединение. Worker пробуется **первым**, поэтому если после его добавления Telegram перестал грузить каналы — очистите поле «Домены Cloudflare Worker» и сохраните.

## Как получить

> Если `dash.cloudflare.com` или `workers.dev` у вас не открываются, выполните настройку с другого устройства или из другой сети — это делается один раз.

1. Зарегистрируйтесь на [dash.cloudflare.com](https://dash.cloudflare.com/) (или войдите) и **подтвердите почту** по ссылке из письма.
2. Слева в меню: **Compute → Workers & Pages**.
3. Справа вверху **Create application** → **Start with Hello World!** → **Deploy**.
4. Справа вверху **Edit code**, удалите весь код слева и вставьте [код ниже](#код-worker). В приложении его можно скопировать кнопкой: «О программе» → «Справка» → «Свой Cloudflare Worker» → «Скопировать код Worker».
5. Справа вверху **Deploy**.
6. Скопируйте адрес Worker справа — вида `название-1234.ваш-аккаунт.workers.dev`.
7. В приложении: **Настройки → Cloudflare → Домены Cloudflare Worker** — вставьте адрес и нажмите **Сохранить**. Несколько Worker можно указать через запятую.

Проверить: кнопка **Проверить Worker** прямо под полем покажет «✓ … отвечает» — или объяснит, что не так. После сохранения то же видно в **Проверить связь** на главном экране: строка «CF Worker» зелёная.

## Код Worker

```javascript
import { connect } from "cloudflare:sockets";

function toBytes(data) {
	if (data instanceof ArrayBuffer) {
		return new Uint8Array(data);
	}
	if (typeof data === "string") {
		return new TextEncoder().encode(data);
	}
	if (data && typeof data.arrayBuffer === "function") {
		return data.arrayBuffer().then((ab) => new Uint8Array(ab));
	}
	return new Uint8Array();
}

export default {
	async fetch(request) {
		if ((request.headers.get("Upgrade") || "").toLowerCase() !== "websocket") {
			return new Response("Expected websocket", { status: 426 });
		}

		const url = new URL(request.url);
		if (url.pathname !== "/apiws") {
			return new Response("Not found", { status: 404 });
		}

		const dst = url.searchParams.get("dst");
		const pair = new WebSocketPair();
		const client = pair[0];
		const server = pair[1];
		server.accept();

		const socket = connect({ hostname: dst, port: 443 });
		const tcpReader = socket.readable.getReader();
		const tcpWriter = socket.writable.getWriter();

		server.addEventListener("message", async (event) => {
			try {
				await tcpWriter.write(await toBytes(event.data));
			} catch {
				try {
					server.close(1011, "tcp write failed");
				} catch {}
			}
		});

		server.addEventListener("close", async () => {
			try {
				await tcpWriter.close();
			} catch {}
			try {
				socket.close();
			} catch {}
		});

		(async () => {
			try {
				while (true) {
					const { value, done } = await tcpReader.read();
					if (done) {
						break;
					}
					if (value) {
						server.send(value);
					}
				}
			} catch {
			} finally {
				try {
					server.close();
				} catch {}
				try {
					tcpReader.releaseLock();
				} catch {}
				try {
					socket.close();
				} catch {}
			}
		})();

		return new Response(null, { status: 101, webSocket: client });
	},
};
```
