# Интеграция с Root и Системная Служба в TailSocks

TailSocks поддерживает расширенный **Root-режим** для Android-устройств с правами суперпользователя (**Magisk**, **KernelSU** или **APatch**). Root-режим позволяет демону `tailscaled` работать в качестве полноценного системного демона независимо от жизненного цикла Android UI, автоматически запускаться при загрузке устройства и предоставлять официальный консольный интерфейс `tailscale` CLI в терминальной среде.

---

## ⚡ Ключевая Архитектура и Возможности

### 1. Автозапуск Системной Службы через `service.d` (`tailscaled.sh`)
* **Путь:** `/data/adb/service.d/tailscaled.sh`
* **Запуск:** Выполняется автоматически Magisk / KernelSU / APatch на ранней стадии загрузки (`late_start` фаза) от имени `root` (UID 0).
* **Авто-определение Директории Состояния:** Динамически ищет существующие профили аккаунтов в `/data/data/io.github.bropines.tailscaled/files/states/` (по умолчанию `default` или `root`).
* **Управление Сокетом и Логами:** Использует сокет `/data/data/io.github.bropines.tailscaled/files/tailscaled.sock` с правами `0666` (благодаря встроенному патчу safesocket в ядре TailSocks). Логи демона (`tailscaled.log`) автоматически ротируются при превышении 2 МБ.
* **Передача Прокси и Control Plane:** Считывает переменные окружения из `/data/data/io.github.bropines.tailscaled/files/control_proxy.env` (включая `ALL_PROXY`, `HTTP_PROXY`, `HTTPS_PROXY` и разрешённые IP через `TS_STATIC_HOSTS`).

### 2. Интеграция с Tailscale CLI (`tailscale_cli.sh`)
* **Пути установки CLI-обёртки:**
  * `/data/adb/service.d/tailscale` (Исполняемый фаллбэк)
  * `/data/adb/modules/tailscaled/system/bin/tailscale` (Оверлей-модуль Magisk)
  * `/product/bin/tailscale` (Оверлей для раздела `/product/bin`)
  * `/system/bin/tailscale` (Прямая ссылка в `/system/bin`, если раздел доступен для записи)
* **Принцип работы:** Оборачивает нативный бинарник `libtailscale_cli.so` из установленного пакета приложения и автоматически подставляет аргумент `--socket=/data/data/io.github.bropines.tailscaled/files/tailscaled.sock` к каждой команде.
* **Применение:** Позволяет выполнять стандартные команды `tailscale` в Termux, `su`-шелле, ADB-шелле и скриптах.

### 3. Автоматическое Обновление при Обновлении Приложения (`BootReceiver`)
* **Отслеживаемые События:** `ACTION_BOOT_COMPLETED`, `QUICKBOOT_POWERON` и `ACTION_MY_PACKAGE_REPLACED`.
* **Логика Обновления:** При обновлении TailSocks (замене APK) `BootReceiver` автоматически обновляет скрипты службы `service.d` и CLI-обёртку из ассетов. Это гарантирует, что пути к нативным библиотекам `libtailscale.so` и `libtailscale_cli.so` в `/data/app/...` всегда актуальны и соответствуют новой версии без необходимости ручной переустановки.

### 4. Нативная маршрутизация TUN (`tailscale0`)

При включённом **Native Linux TUN** демон создаёт настоящий интерфейс ядра
`tailscale0`, оставляя слот Android VPN свободным. TailSocks настраивает
policy-routing вокруг него после перехода демона в состояние `Running`:

* **Таблица `1099`** содержит маршрут `100.64.0.0/10` (и `fd7a:115c:a1e0::/48`)
  через `tailscale0`. Трафик направляется в неё меткой файрвола `1099`.
* **`TAILSOCKS_MARK`** (mangle) ставит эту метку на пакеты в сторону tailnet.
* **`TAILSOCKS_DNS`** (nat) перенаправляет системный 53-й порт на MagicDNS
  (`100.100.100.100`). Два класса трафика исключаются до редиректа:
  * диапазон tailnet `100.64.0.0/10` — чтобы Split DNS-резолверы на пирах
    оставались доступны напрямую;
  * собственные upstream-резолверы демона (`TS_DNS_FALLBACK`, берутся из
    настройки DNS fallback). Без этого запросы самого резолвера
    перенаправляются обратно в него же, и ни одно внешнее имя не резолвится.

  Редирект ставится **только** при включённом `accept-dns`: перенаправлять всё
  устройство на резолвер, который не отвечает, значит сломать DNS целиком.

Все правила живут в именованных цепочках — они идемпотентны, их видно через
`iptables -t nat -S TAILSOCKS_DNS`, и они снимаются одним махом при остановке
службы. **Настройки → Root-режим → Проверить маршрутизацию** показывает текущее
состояние, а ошибки попадают во вкладку **ROOT** на экране логов, а не
теряются молча.

---

## 📱 Включение Root-интеграции в Приложении

1. Откройте **TailSocks**.
2. Перейдите в **Настройки** → **Root Интеграция**.
3. **Предоставьте Root-права:** Нажмите для проверки наличия бинарника `su`.
4. **Установите Автозапуск Службы:** Включите переключатель установки `/data/adb/service.d/tailscaled.sh`.
5. **Установите Tailscale CLI:** Включите переключатель установки `/system/bin/tailscale` и оверлея Magisk.
6. Приложение отобразит текущий статус (установлено/не установлено), целевые пути и кнопки копирования путей.

---

## 💻 Примеры Использования CLI

После установки CLI-интеграции откройте любой root-терминал (`su` в Termux, ADB shell или серийную консоль) и выполняйте:

```bash
# Проверить статус узлов и подключенных пиров
su -c tailscale status

# Узнать IP-адреса Tailscale
su -c tailscale ip

# Выполнить ping до узла сети Tailnet
su -c tailscale ping <node-ip-or-name>

# Выполнить проверку сети и NAT traversal
su -c tailscale netcheck

# Узнать версию демона tailscaled
su -c tailscale version
```

---

## 🪵 Логи и Устранение Неполадок

* **Файл логов демона:** `/data/data/io.github.bropines.tailscaled/logs/tailscaled.log`
* **Файл сокета:** `/data/data/io.github.bropines.tailscaled/files/tailscaled.sock`
* **Конфиг модуля Magisk:** `/data/adb/modules/tailscaled/module.prop`

### Полезные команды для диагностики:

```bash
# Просмотр логов tailscaled в реальном времени
su -c tail -f /data/data/io.github.bropines.tailscaled/logs/tailscaled.log

# Проверка работы процесса tailscaled от имени root
su -c ps -ef | grep tailscaled

# Проверка прав доступа к сокету
su -c ls -la /data/data/io.github.bropines.tailscaled/files/tailscaled.sock
```
