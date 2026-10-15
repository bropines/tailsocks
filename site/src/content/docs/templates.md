---
title: Policy templates
description: Ready-made pieces of a Tailscale or Headscale policy that TailSocks's visual policy editor fills in and adds, and how to write one.
---

A template is a ready-made piece of a tailnet policy: a short form, a preview of exactly what it adds, and **Apply**. The visual policy editor of TailSocks's admin console offers them, for Tailscale and for Headscale.

A template only adds — rules, tags, approvers, attributes, tests — and never removes or changes a rule the policy has. What the policy has already is not written twice. The server checks the policy on save as it always does: that check stays the last word.

## Use one

1. Settings → **Automation & API** → **Admin Console**, with an admin profile of your tailnet or Headscale server.
2. **Policy** → **Edit visually**.
3. On **Access**, **Device attributes** or **Peer relays**, tap **All templates**. Some pages also offer a template of their own as a card.
4. Pick a template, fill in its fields, read **What Apply adds**, and tap **Apply**. One step of undo takes it back.

On Headscale the catalog leaves out what Headscale does not take, and marks *Partly on Headscale* what it takes only in part.

## Where they come from

The app has the templates below built in. When the catalog opens, it also asks this site for newer versions and new templates — at most once in six hours, or when you tap **Check for new templates** — through the admin console's connection: [`templates/index.json`](/tailsocks/templates/index.json) and the files it lists. A file whose SHA-256 is not the one the index lists, or that breaks the app's rules, is ignored; the built-in templates keep working either way.

## The templates

<!-- templates:start -->

### Everyone reaches their own devices

`own-devices` · Access · Headscale: yes · version 1

Every member, on every port, to the devices they own.

No fields: it writes the same into every policy.

Read more: [Autogroups](https://tailscale.com/kb/1337/policy-syntax#autogroups).

<details>
<summary>The template file, <code>own-devices.json</code></summary>

```json
{
  "schema": 1,
  "id": "own-devices",
  "version": 1,
  "category": "access",
  "title": {
    "en": "Everyone reaches their own devices",
    "ru": "Каждый — к своим устройствам"
  },
  "description": {
    "en": "Every member, on every port, to the devices they own.",
    "ru": "Каждый участник ко всем портам своих устройств."
  },
  "headscale": { "supported": true },
  "docs": [
    { "title": { "en": "Autogroups", "ru": "Автогруппы" }, "url": "https://tailscale.com/kb/1337/policy-syntax#autogroups" }
  ],
  "suggest": [ { "page": "access", "slot": "empty" } ],
  "fragments": {
    "access": [
      { "src": ["autogroup:member"], "dst": ["autogroup:self"], "ports": ["*"] }
    ]
  }
}
```

</details>

### Admins reach everything

`admins-everything` · Access · Not on Headscale · version 1

Users with the Admin role, to every device on every port.

Not on Headscale. Headscale has no admin role to name.

No fields: it writes the same into every policy.

Read more: [Autogroups](https://tailscale.com/kb/1337/policy-syntax#autogroups).

<details>
<summary>The template file, <code>admins-everything.json</code></summary>

```json
{
  "schema": 1,
  "id": "admins-everything",
  "version": 1,
  "category": "access",
  "title": {
    "en": "Admins reach everything",
    "ru": "Администраторам — всё"
  },
  "description": {
    "en": "Users with the Admin role, to every device on every port.",
    "ru": "Пользователи с ролью администратора ко всем устройствам на всех портах."
  },
  "headscale": {
    "supported": false,
    "note": {
      "en": "Headscale has no admin role to name.",
      "ru": "В Headscale нет роли администратора, на которую можно сослаться."
    }
  },
  "docs": [
    { "title": { "en": "Autogroups", "ru": "Автогруппы" }, "url": "https://tailscale.com/kb/1337/policy-syntax#autogroups" }
  ],
  "suggest": [ { "page": "access", "slot": "empty" } ],
  "fragments": {
    "access": [
      { "src": ["autogroup:admin"], "dst": ["*"], "ports": ["*"] }
    ]
  }
}
```

</details>

### Lock it down

`lock-down` · Basics · Headscale: yes · version 1

A narrow start instead of allow-all: members reach their own devices, admins reach everything, tagged devices reach nothing. Read the preview carefully.

It only adds rules and never removes one: while the policy keeps a rule that lets everyone reach everything (such as \* to \*:\*), nothing gets narrower. The page shows that rule; delete it by hand after Apply. From then on whatever is not allowed here closes: tagged servers talking to each other, devices shared with you, subnet routes — add rules for what has to keep working before you delete it.

| Field | Takes | About it |
|---|---|---|
| **Admins** (several) | from the picker: groups, users | Who reaches every device. On Tailscale people with the Admin role do anyway; Headscale has no roles, so here they are the only ones. |
| **Members keep exit nodes** | a switch; default on | Every member may still route their internet traffic through an exit node. |

Read more: [Autogroups](https://tailscale.com/docs/reference/targets-and-selectors), [Tags](https://tailscale.com/docs/features/tags).

<details>
<summary>The template file, <code>lock-down.json</code></summary>

```json
{
  "schema": 1,
  "id": "lock-down",
  "version": 1,
  "category": "baseline",
  "title": {
    "en": "Lock it down",
    "ru": "Закрыть лишнее"
  },
  "description": {
    "en": "A narrow start instead of allow-all: members reach their own devices, admins reach everything, tagged devices reach nothing. Read the preview carefully.\n\nIt only adds rules and never removes one: while the policy keeps a rule that lets everyone reach everything (such as * to *:*), nothing gets narrower. The page shows that rule; delete it by hand after Apply. From then on whatever is not allowed here closes: tagged servers talking to each other, devices shared with you, subnet routes — add rules for what has to keep working before you delete it.",
    "ru": "Узкий старт вместо «можно всё»: участники ходят к своим устройствам, администраторы — ко всем, устройства с тегами — никуда. Внимательно прочитайте предпросмотр.\n\nШаблон только добавляет правила и ничего не удаляет: пока в политике есть правило, которое пускает всех ко всем (вроде * → *:*), ничего не сузится. Страница покажет это правило — удалите его вручную после «Применить». С этого момента закроется всё, что здесь не разрешено: серверы с тегами друг к другу, устройства, которыми с вами поделились, маршруты подсетей. Добавьте правила для того, что должно продолжать работать, до того как удалять."
  },
  "headscale": {
    "supported": true
  },
  "docs": [
    { "title": { "en": "Autogroups", "ru": "Автогруппы" }, "url": "https://tailscale.com/docs/reference/targets-and-selectors" },
    { "title": { "en": "Tags", "ru": "Теги" }, "url": "https://tailscale.com/docs/features/tags" }
  ],
  "params": [
    {
      "id": "admins",
      "kind": "selector",
      "slot": "acl-src",
      "allow": ["group", "user"],
      "label": { "en": "Admins", "ru": "Администраторы" },
      "help": {
        "en": "Who reaches every device. On Tailscale people with the Admin role do anyway; Headscale has no roles, so here they are the only ones.",
        "ru": "Кому открыты все устройства. На Tailscale людям с ролью администратора — в любом случае; в Headscale ролей нет, и тогда это единственные администраторы."
      },
      "multi": true
    },
    {
      "id": "internet",
      "kind": "bool",
      "label": { "en": "Members keep exit nodes", "ru": "Участникам остаются exit-ноды" },
      "help": {
        "en": "Every member may still route their internet traffic through an exit node.",
        "ru": "Любой участник по-прежнему может пускать интернет через exit-ноду."
      },
      "default": true
    }
  ],
  "checks": [
    {
      "id": "open",
      "kind": "access-open",
      "src": ["autogroup:member"],
      "dst": ["*"],
      "ports": ["*"],
      "note": {
        "en": "The policy lets every member reach every device already, by the rule shown here. Nothing gets narrower until you delete it by hand.",
        "ru": "Политика уже пускает любого участника ко всем устройствам — правилом, показанным здесь. Пока его не удалить вручную, ничего не сузится."
      }
    }
  ],
  "fragments": {
    "access": [
      { "src": ["autogroup:member"], "dst": ["autogroup:self"], "ports": ["*"] },
      { "when": "tailscale", "src": ["autogroup:admin"], "dst": ["*"], "ports": ["*"] },
      { "src": ["{{admins}}"], "dst": ["*"], "ports": ["*"] },
      { "when": "param:internet", "src": ["autogroup:member"], "dst": ["autogroup:internet"], "ports": ["*"] }
    ]
  }
}
```

</details>

### Guests use exit nodes only

`guests-exit-nodes` · Guests · Headscale: yes · version 2

The internet through exit nodes and nothing in the tailnet. Pick who the guests are.

Rules only allow: while another rule lets every member in, guests get that too. Fill in both test fields and a test makes the server refuse any policy that opens those devices to a guest (ports 22 and 443).

| Field | Takes | About it |
|---|---|---|
| **Who the guests are** (required, several) | from the picker: users, groups, tags, autogroups | Users, groups or tags whose devices may use exit nodes and nothing else. |
| **Test: as which guest** | from the picker: users, groups | One of the guests, with devices of their own. Empty: no test. |
| **Test: stays closed to guests** (several) | from the picker: users, groups, tags, hosts | Your own devices, admins' groups, servers' tags. Empty: no test. |

Read more: [Exit nodes](https://tailscale.com/kb/1103/exit-nodes).

<details>
<summary>The template file, <code>guests-exit-nodes.json</code></summary>

```json
{
  "schema": 1,
  "id": "guests-exit-nodes",
  "version": 2,
  "category": "guests",
  "title": {
    "en": "Guests use exit nodes only",
    "ru": "Гостям — только exit-ноды"
  },
  "description": {
    "en": "The internet through exit nodes and nothing in the tailnet. Pick who the guests are.\n\nRules only allow: while another rule lets every member in, guests get that too. Fill in both test fields and a test makes the server refuse any policy that opens those devices to a guest (ports 22 and 443).",
    "ru": "Интернет через exit-ноды и ничего внутри tailnet. Останется выбрать, кто гости.\n\nПравила только разрешают: пока другое правило пускает всех участников, гостям достаётся и это. Заполните оба поля теста — и сервер не примет политику, которая откроет гостю эти устройства (порты 22 и 443)."
  },
  "headscale": { "supported": true },
  "docs": [
    { "title": { "en": "Exit nodes", "ru": "Exit-ноды" }, "url": "https://tailscale.com/kb/1103/exit-nodes" }
  ],
  "suggest": [ { "page": "access", "slot": "empty" } ],
  "params": [
    {
      "id": "guests",
      "kind": "selector",
      "slot": "acl-src",
      "allow": ["user", "group", "tag", "autogroup"],
      "label": { "en": "Who the guests are", "ru": "Кто гости" },
      "help": {
        "en": "Users, groups or tags whose devices may use exit nodes and nothing else.",
        "ru": "Пользователи, группы или теги, чьим устройствам можно пользоваться exit-нодами и ничем больше."
      },
      "required": true,
      "multi": true
    },
    {
      "id": "tester",
      "kind": "selector",
      "slot": "test-src",
      "allow": ["user", "group"],
      "label": { "en": "Test: as which guest", "ru": "Тест: от имени какого гостя" },
      "help": {
        "en": "One of the guests, with devices of their own. Empty: no test.",
        "ru": "Один из гостей, и у него есть устройства. Пусто — без теста."
      }
    },
    {
      "id": "closed",
      "kind": "selector",
      "slot": "test-dst",
      "allow": ["user", "group", "tag", "host"],
      "label": { "en": "Test: stays closed to guests", "ru": "Тест: закрыто для гостей" },
      "help": {
        "en": "Your own devices, admins' groups, servers' tags. Empty: no test.",
        "ru": "Ваши устройства, группы администраторов, теги серверов. Пусто — без теста."
      },
      "multi": true
    }
  ],
  "fragments": {
    "access": [
      { "src": ["{{guests}}"], "dst": ["autogroup:internet"], "ports": ["*"] }
    ],
    "tests": [
      { "when": "param:tester && param:closed", "src": "{{tester}}", "deny": ["{{closed}}:22", "{{closed}}:443"] }
    ]
  }
}
```

</details>

### A group reaches a tag on chosen ports

`group-to-tag` · Access · Headscale: yes · version 1

Pick the group, the tag and the ports.

| Field | Takes | About it |
|---|---|---|
| **Group** (required) | a group |  |
| **Tag** (required) | a tag |  |
| **Ports** (required, several) | ports | A port, a range such as 8000-8100, or \* for every port. |

Read more: [Access rules](https://tailscale.com/kb/1337/policy-syntax).

<details>
<summary>The template file, <code>group-to-tag.json</code></summary>

```json
{
  "schema": 1,
  "id": "group-to-tag",
  "version": 1,
  "category": "access",
  "title": {
    "en": "A group reaches a tag on chosen ports",
    "ru": "Группа к тегу на нужных портах"
  },
  "description": {
    "en": "Pick the group, the tag and the ports.",
    "ru": "Выберите группу, тег и порты."
  },
  "headscale": { "supported": true },
  "docs": [
    { "title": { "en": "Access rules", "ru": "Правила доступа" }, "url": "https://tailscale.com/kb/1337/policy-syntax" }
  ],
  "suggest": [ { "page": "access", "slot": "empty" } ],
  "params": [
    {
      "id": "group",
      "kind": "group",
      "label": { "en": "Group", "ru": "Группа" },
      "required": true
    },
    {
      "id": "tag",
      "kind": "tag",
      "label": { "en": "Tag", "ru": "Тег" },
      "required": true
    },
    {
      "id": "ports",
      "kind": "port",
      "label": { "en": "Ports", "ru": "Порты" },
      "help": {
        "en": "A port, a range such as 8000-8100, or * for every port.",
        "ru": "Порт, диапазон вроде 8000-8100 или * — все порты."
      },
      "required": true,
      "multi": true
    }
  ],
  "fragments": {
    "access": [
      { "src": ["{{group}}"], "dst": ["{{tag}}"], "ports": ["{{ports}}"] }
    ]
  }
}
```

</details>

### Tailscale SSH for admins

`ssh-admins` · SSH · Headscale: yes · version 1

A group logs in to tagged servers with Tailscale SSH as the users you choose, with or without signing in again.

Writes the SSH rule and, unless the policy opens it already, the access rule for TCP 22 that SSH needs too, plus tests. The servers run Tailscale SSH: tailscale set `--ssh`. Check mode asks for a fresh sign-in every 12 hours. The SSH test pins root: allowed when root is among the users, refused otherwise. A server may refuse the test while no device has these tags yet (Headscale does): switch it off until one does.

| Field | Takes | About it |
|---|---|---|
| **Who logs in** (required) | from the picker: groups, users | A group of admins, or one person. |
| **Servers** (required, several) | from the picker: tags | Tags of the servers. A person's own devices are not for this rule: Tailscale lets only their owner into them. |
| **As users** (required, several) | from the picker; default `root`, `autogroup:nonroot` | Accounts on the servers. "Any user but root" is autogroup:nonroot. |
| **Sign-in** (required) | one of: Check: sign in again, Accept: no extra step; default Check: sign in again |  |
| **Pin it with an SSH test** | a switch; default on | Turn it off until a device has one of these tags: a server may refuse a test of servers that do not exist yet. |

Read more: [Tailscale SSH](https://tailscale.com/docs/features/tailscale-ssh), [SSH rules and tests](https://tailscale.com/docs/reference/syntax/policy-file).

<details>
<summary>The template file, <code>ssh-admins.json</code></summary>

```json
{
  "schema": 1,
  "id": "ssh-admins",
  "version": 1,
  "category": "ssh",
  "title": {
    "en": "Tailscale SSH for admins",
    "ru": "Tailscale SSH для администраторов"
  },
  "description": {
    "en": "A group logs in to tagged servers with Tailscale SSH as the users you choose, with or without signing in again.\n\nWrites the SSH rule and, unless the policy opens it already, the access rule for TCP 22 that SSH needs too, plus tests. The servers run Tailscale SSH: tailscale set --ssh. Check mode asks for a fresh sign-in every 12 hours. The SSH test pins root: allowed when root is among the users, refused otherwise. A server may refuse the test while no device has these tags yet (Headscale does): switch it off until one does.",
    "ru": "Группа входит на серверы с тегом через Tailscale SSH под выбранными пользователями — с повторным входом или без.\n\nЗапишется правило SSH и, если политика ещё не открывает его, правило доступа к TCP 22, без которого SSH не работает, плюс тесты. На серверах должен работать Tailscale SSH: tailscale set --ssh. Режим проверки просит войти заново раз в 12 часов. SSH-тест закрепляет root: разрешён, если root среди пользователей, иначе запрещён. Сервер может не принять тест, пока ни у одного устройства нет этих тегов (Headscale не принимает), — до тех пор выключите его."
  },
  "headscale": { "supported": true },
  "docs": [
    { "title": { "en": "Tailscale SSH", "ru": "Tailscale SSH" }, "url": "https://tailscale.com/docs/features/tailscale-ssh" },
    { "title": { "en": "SSH rules and tests", "ru": "Правила и тесты SSH" }, "url": "https://tailscale.com/docs/reference/syntax/policy-file" }
  ],
  "params": [
    {
      "id": "admins",
      "kind": "selector",
      "slot": "ssh-src",
      "allow": ["group", "user"],
      "label": { "en": "Who logs in", "ru": "Кто входит" },
      "help": {
        "en": "A group of admins, or one person.",
        "ru": "Группа администраторов или один человек."
      },
      "required": true
    },
    {
      "id": "servers",
      "kind": "selector",
      "slot": "ssh-dst",
      "allow": ["tag"],
      "label": { "en": "Servers", "ru": "Серверы" },
      "help": {
        "en": "Tags of the servers. A person's own devices are not for this rule: Tailscale lets only their owner into them.",
        "ru": "Теги серверов. Личные устройства сюда не годятся: Tailscale пускает на них только владельца."
      },
      "required": true,
      "multi": true
    },
    {
      "id": "users",
      "kind": "selector",
      "slot": "ssh-user",
      "label": { "en": "As users", "ru": "Под пользователями" },
      "help": {
        "en": "Accounts on the servers. \"Any user but root\" is autogroup:nonroot.",
        "ru": "Учётные записи на серверах. «Любой, кроме root» — это autogroup:nonroot."
      },
      "required": true,
      "multi": true,
      "default": ["root", "autogroup:nonroot"]
    },
    {
      "id": "mode",
      "kind": "choice",
      "label": { "en": "Sign-in", "ru": "Вход" },
      "options": [
        { "value": "check", "label": { "en": "Check: sign in again", "ru": "Проверка: войти заново" } },
        { "value": "accept", "label": { "en": "Accept: no extra step", "ru": "Сразу: без лишнего шага" } }
      ],
      "required": true,
      "default": "check"
    },
    {
      "id": "test",
      "kind": "bool",
      "label": { "en": "Pin it with an SSH test", "ru": "Закрепить SSH-тестом" },
      "help": {
        "en": "Turn it off until a device has one of these tags: a server may refuse a test of servers that do not exist yet.",
        "ru": "Выключите, пока ни у одного устройства нет этих тегов: сервер может не принять тест серверов, которых ещё нет."
      },
      "default": true
    }
  ],
  "checks": [
    {
      "id": "open",
      "kind": "access-open",
      "src": ["{{admins}}"],
      "dst": ["{{servers}}"],
      "ports": ["22"],
      "proto": "tcp",
      "note": {
        "en": "The policy lets them reach port 22 of these servers already, so only the SSH rule and the tests are added.",
        "ru": "Политика уже пускает их на порт 22 этих серверов, поэтому добавятся только правило SSH и тесты."
      }
    }
  ],
  "fragments": {
    "access": [
      { "when": "!check:open", "src": ["{{admins}}"], "dst": ["{{servers}}"], "ports": ["22"], "proto": "tcp" }
    ],
    "ssh": [
      { "action": "{{mode}}", "src": ["{{admins}}"], "dst": ["{{servers}}"], "users": ["{{users}}"] }
    ],
    "tests": [
      { "generate": "pair-test", "src": ["{{admins}}"], "dst": ["{{servers}}"], "port": "22", "proto": "tcp" }
    ],
    "sshTests": [
      { "when": "param:test && param:users=root && param:mode=accept", "src": "{{admins}}", "dst": ["{{servers}}"], "accept": ["root"] },
      { "when": "param:test && param:users=root && param:mode=check", "src": "{{admins}}", "dst": ["{{servers}}"], "check": ["root"] },
      { "when": "param:test && !param:users=root", "src": "{{admins}}", "dst": ["{{servers}}"], "deny": ["root"] }
    ]
  }
}
```

</details>

### Chosen exit nodes for a group

`exit-nodes-group` · Exit nodes · Headscale: yes · version 1

Who may send their internet traffic through the exit nodes of one tag, and exit nodes with that tag approved without the admin console.

Writes a grant to the internet through devices with the tag (via). A tag the policy does not define yet is added with no owners: only admins can apply it. Leave the tag empty to allow every exit node. Grants add up: while another rule gives these people the internet without via, every exit node stays open to them. No test is written: a policy test cannot name the internet.

| Field | Takes | About it |
|---|---|---|
| **Who uses them** (required, several) | from the picker: users, groups, tags, autogroups | Users, groups or tags whose devices may route their internet traffic through these exit nodes. |
| **Exit nodes with the tag** | a tag; default `tag:exit-node` | Only exit nodes with this tag carry their traffic. Empty: any exit node. |
| **Approve these exit nodes automatically** | a switch; default on | A device with the tag that offers itself as an exit node needs no approval in the admin console. Counts from its next advertisement on. |

Read more: [Exit nodes](https://tailscale.com/docs/features/exit-nodes), [Route filtering with via](https://tailscale.com/docs/features/access-control/grants/grants-via).

<details>
<summary>The template file, <code>exit-nodes-group.json</code></summary>

```json
{
  "schema": 1,
  "id": "exit-nodes-group",
  "version": 1,
  "category": "exit-nodes",
  "title": {
    "en": "Chosen exit nodes for a group",
    "ru": "Группе — свои exit-ноды"
  },
  "description": {
    "en": "Who may send their internet traffic through the exit nodes of one tag, and exit nodes with that tag approved without the admin console.\n\nWrites a grant to the internet through devices with the tag (via). A tag the policy does not define yet is added with no owners: only admins can apply it. Leave the tag empty to allow every exit node. Grants add up: while another rule gives these people the internet without via, every exit node stays open to them. No test is written: a policy test cannot name the internet.",
    "ru": "Кто может пускать свой интернет через exit-ноды с одним тегом; exit-ноды с этим тегом одобряются без консоли администратора.\n\nЗапишется грант в интернет через устройства с тегом (via). Тег, которого в политике ещё нет, добавится без владельцев — назначать его смогут только администраторы. Пустой тег — разрешены любые exit-ноды. Гранты складываются: пока другое правило даёт этим людям интернет без via, им открыты все exit-ноды. Теста не будет: тест политики не умеет называть интернет."
  },
  "headscale": { "supported": true },
  "docs": [
    { "title": { "en": "Exit nodes", "ru": "Exit-ноды" }, "url": "https://tailscale.com/docs/features/exit-nodes" },
    { "title": { "en": "Route filtering with via", "ru": "Маршрут через via" }, "url": "https://tailscale.com/docs/features/access-control/grants/grants-via" }
  ],
  "params": [
    {
      "id": "who",
      "kind": "selector",
      "slot": "grant-src",
      "allow": ["user", "group", "tag", "autogroup"],
      "label": { "en": "Who uses them", "ru": "Кто пользуется" },
      "help": {
        "en": "Users, groups or tags whose devices may route their internet traffic through these exit nodes.",
        "ru": "Пользователи, группы или теги, чьи устройства могут пускать интернет через эти exit-ноды."
      },
      "required": true,
      "multi": true
    },
    {
      "id": "exit",
      "kind": "tag",
      "slot": "grant-via",
      "label": { "en": "Exit nodes with the tag", "ru": "Exit-ноды с тегом" },
      "help": {
        "en": "Only exit nodes with this tag carry their traffic. Empty: any exit node.",
        "ru": "Их трафик пойдёт только через exit-ноды с этим тегом. Пусто — через любую exit-ноду."
      },
      "default": "tag:exit-node"
    },
    {
      "id": "approve",
      "kind": "bool",
      "label": { "en": "Approve these exit nodes automatically", "ru": "Одобрять эти exit-ноды автоматически" },
      "help": {
        "en": "A device with the tag that offers itself as an exit node needs no approval in the admin console. Counts from its next advertisement on.",
        "ru": "Устройство с тегом, которое объявит себя exit-нодой, не придётся одобрять в консоли администратора. Действует со следующего объявления."
      },
      "default": true
    }
  ],
  "checks": [
    {
      "id": "open",
      "kind": "access-open",
      "src": ["{{who}}"],
      "dst": ["autogroup:internet"],
      "ports": ["*"],
      "note": {
        "en": "A rule of the policy gives these people the internet without via already: every exit node stays open to them until that rule is narrowed.",
        "ru": "Правило политики уже даёт этим людям интернет без via: все exit-ноды останутся им открыты, пока это правило не сузить."
      }
    }
  ],
  "fragments": [
    {
      "access": [
        {
          "when": "!check:open || param:exit",
          "src": ["{{who}}"],
          "dst": ["autogroup:internet"],
          "ports": ["*"],
          "via": ["{{exit}}"]
        }
      ],
      "tagOwners": { "{{exit}}": [] }
    },
    { "when": "param:approve", "autoApprovers": { "exitNode": ["{{exit}}"] } }
  ]
}
```

</details>

### Mullvad exit nodes for chosen people

`mullvad-exit-nodes` · Exit nodes · Not on Headscale · version 1

Devices of these people or tags see Mullvad's servers as exit nodes. Needs the Mullvad VPN add-on.

Writes the mullvad attribute. Once the policy hands Mullvad out, all Mullvad access is managed in the policy, not on the admin console's Mullvad page. A license goes to a device as it connects, first come, first served: with more devices than licenses some see no Mullvad exit nodes.

Not on Headscale. Mullvad exit nodes are a Tailscale add-on.

| Field | Takes | About it |
|---|---|---|
| **Who gets them** (required, several) | from the picker: users, groups, tags, autogroups | Users, groups or tags; each of their devices takes a license as it connects, while licenses are left. |

Read more: [Mullvad exit nodes](https://tailscale.com/docs/features/exit-nodes/mullvad-exit-nodes).

<details>
<summary>The template file, <code>mullvad-exit-nodes.json</code></summary>

```json
{
  "schema": 1,
  "id": "mullvad-exit-nodes",
  "version": 1,
  "category": "exit-nodes",
  "title": {
    "en": "Mullvad exit nodes for chosen people",
    "ru": "Exit-ноды Mullvad для выбранных людей"
  },
  "description": {
    "en": "Devices of these people or tags see Mullvad's servers as exit nodes. Needs the Mullvad VPN add-on.\n\nWrites the mullvad attribute. Once the policy hands Mullvad out, all Mullvad access is managed in the policy, not on the admin console's Mullvad page. A license goes to a device as it connects, first come, first served: with more devices than licenses some see no Mullvad exit nodes.",
    "ru": "Устройства этих людей или тегов увидят серверы Mullvad как exit-ноды. Нужно дополнение Mullvad VPN.\n\nЗапишется атрибут mullvad. Когда Mullvad раздаёт политика, весь доступ к нему управляется в политике, а не на странице Mullvad в консоли администратора. Лицензия достаётся устройству при подключении, кто первым успел: если устройств больше, чем лицензий, часть из них exit-нод Mullvad не увидит."
  },
  "headscale": {
    "supported": false,
    "note": {
      "en": "Mullvad exit nodes are a Tailscale add-on.",
      "ru": "Exit-ноды Mullvad — дополнение Tailscale."
    }
  },
  "docs": [
    { "title": { "en": "Mullvad exit nodes", "ru": "Exit-ноды Mullvad" }, "url": "https://tailscale.com/docs/features/exit-nodes/mullvad-exit-nodes" }
  ],
  "params": [
    {
      "id": "devices",
      "kind": "selector",
      "slot": "node-target",
      "allow": ["user", "group", "tag", "autogroup"],
      "label": { "en": "Who gets them", "ru": "Кому" },
      "help": {
        "en": "Users, groups or tags; each of their devices takes a license as it connects, while licenses are left.",
        "ru": "Пользователи, группы или теги; каждое их устройство берёт лицензию при подключении, пока лицензии есть."
      },
      "required": true,
      "multi": true
    }
  ],
  "checks": [
    {
      "id": "mullvad",
      "kind": "node-attr",
      "attrs": ["mullvad"],
      "note": {
        "en": "A rule of the policy hands Mullvad out already; this one adds these devices.",
        "ru": "Mullvad уже раздаёт одно из правил политики; это добавит ещё устройства."
      }
    }
  ],
  "fragments": {
    "nodeAttrs": [
      { "target": ["{{devices}}"], "attr": ["mullvad"] }
    ]
  }
}
```

</details>

### A subnet through a tagged router

`subnet-router` · Subnets · Headscale: yes · version 1

Who reaches a local network through the subnet routers of one tag, and the route approved without the admin console.

Writes a grant to the subnet through devices with the tag (via). A tag the policy does not define yet is added with no owners: only admins can apply it. Leave the tag empty to allow any router that advertises the subnet. Linux clients also need tailscale set `--accept-routes`. Fill in both test fields and a test pins the path: the server then refuses a policy that loses it.

| Field | Takes | About it |
|---|---|---|
| **Who reaches it** (required, several) | from the picker: users, groups, tags, autogroups | Users, groups or tags whose devices may reach every address of the subnet. |
| **Subnet** (required) | a subnet | As the router advertises it: tailscale set `--advertise-routes=192.168.1.0/24`. |
| **Routers with the tag** | a tag; default `tag:subnet-router` | Only routers with this tag carry the traffic. Empty: any router that advertises the subnet. |
| **Approve the route automatically** | a switch; default on | A router with the tag that advertises this subnet needs no approval in the admin console. Counts from its next advertisement on. |
| **Test: as whom** | from the picker: users, groups, tags | One of those who reach the subnet, with devices of their own. Empty: no test. |
| **Test: an address in the subnet** | an address | The test checks that it is reachable on port 443. Through a tagged router the server may refuse the test until a router with the tag advertises the subnet; Headscale does. Empty: no test. |

Read more: [Subnet routers](https://tailscale.com/docs/features/subnet-routers), [Route filtering with via](https://tailscale.com/docs/features/access-control/grants/grants-via).

<details>
<summary>The template file, <code>subnet-router.json</code></summary>

```json
{
  "schema": 1,
  "id": "subnet-router",
  "version": 1,
  "category": "subnets",
  "title": {
    "en": "A subnet through a tagged router",
    "ru": "Подсеть через роутер с тегом"
  },
  "description": {
    "en": "Who reaches a local network through the subnet routers of one tag, and the route approved without the admin console.\n\nWrites a grant to the subnet through devices with the tag (via). A tag the policy does not define yet is added with no owners: only admins can apply it. Leave the tag empty to allow any router that advertises the subnet. Linux clients also need tailscale set --accept-routes. Fill in both test fields and a test pins the path: the server then refuses a policy that loses it.",
    "ru": "Кто попадает в локальную сеть через subnet-роутеры с одним тегом; маршрут одобряется без консоли администратора.\n\nЗапишется грант в подсеть через устройства с тегом (via). Тег, которого в политике ещё нет, добавится без владельцев — назначать его смогут только администраторы. Пустой тег — через любой роутер, объявивший подсеть. Клиентам на Linux нужно ещё tailscale set --accept-routes. Заполните оба поля теста — и тест закрепит этот путь: сервер не примет политику, которая его потеряет."
  },
  "headscale": { "supported": true },
  "docs": [
    { "title": { "en": "Subnet routers", "ru": "Subnet-роутеры" }, "url": "https://tailscale.com/docs/features/subnet-routers" },
    { "title": { "en": "Route filtering with via", "ru": "Маршрут через via" }, "url": "https://tailscale.com/docs/features/access-control/grants/grants-via" }
  ],
  "params": [
    {
      "id": "who",
      "kind": "selector",
      "slot": "grant-src",
      "allow": ["user", "group", "tag", "autogroup"],
      "label": { "en": "Who reaches it", "ru": "Кому доступна" },
      "help": {
        "en": "Users, groups or tags whose devices may reach every address of the subnet.",
        "ru": "Пользователи, группы или теги, чьим устройствам открыты все адреса подсети."
      },
      "required": true,
      "multi": true
    },
    {
      "id": "subnet",
      "kind": "cidr",
      "label": { "en": "Subnet", "ru": "Подсеть" },
      "help": {
        "en": "As the router advertises it: tailscale set --advertise-routes=192.168.1.0/24.",
        "ru": "Как её объявляет роутер: tailscale set --advertise-routes=192.168.1.0/24."
      },
      "required": true
    },
    {
      "id": "router",
      "kind": "tag",
      "slot": "grant-via",
      "label": { "en": "Routers with the tag", "ru": "Роутеры с тегом" },
      "help": {
        "en": "Only routers with this tag carry the traffic. Empty: any router that advertises the subnet.",
        "ru": "Трафик пойдёт только через роутеры с этим тегом. Пусто — через любой роутер, объявивший подсеть."
      },
      "default": "tag:subnet-router"
    },
    {
      "id": "approve",
      "kind": "bool",
      "label": { "en": "Approve the route automatically", "ru": "Одобрять маршрут автоматически" },
      "help": {
        "en": "A router with the tag that advertises this subnet needs no approval in the admin console. Counts from its next advertisement on.",
        "ru": "Роутер с тегом, объявивший эту подсеть, не придётся одобрять в консоли администратора. Действует со следующего объявления."
      },
      "default": true
    },
    {
      "id": "tester",
      "kind": "selector",
      "slot": "test-src",
      "allow": ["user", "group", "tag"],
      "label": { "en": "Test: as whom", "ru": "Тест: от чьего имени" },
      "help": {
        "en": "One of those who reach the subnet, with devices of their own. Empty: no test.",
        "ru": "Один из тех, кому открыта подсеть, — и у него есть устройства. Пусто — без теста."
      }
    },
    {
      "id": "probe",
      "kind": "host",
      "label": { "en": "Test: an address in the subnet", "ru": "Тест: адрес в подсети" },
      "help": {
        "en": "The test checks that it is reachable on port 443. Through a tagged router the server may refuse the test until a router with the tag advertises the subnet; Headscale does. Empty: no test.",
        "ru": "Тест проверит, что он доступен на порту 443. С роутером по тегу сервер может отвергать тест, пока роутер с этим тегом не объявит подсеть; Headscale отвергает. Пусто — без теста."
      }
    }
  ],
  "checks": [
    {
      "id": "open",
      "kind": "access-open",
      "src": ["{{who}}"],
      "dst": ["{{subnet}}"],
      "ports": ["*"],
      "note": {
        "en": "A rule of the policy lets these people into the subnet without via already: every router stays open to them until that rule is narrowed.",
        "ru": "Правило политики уже пускает этих людей в подсеть без via: им открыт любой роутер, пока это правило не сузить."
      }
    }
  ],
  "fragments": [
    {
      "access": [
        {
          "when": "!check:open || param:router",
          "src": ["{{who}}"],
          "dst": ["{{subnet}}"],
          "ports": ["*"],
          "via": ["{{router}}"]
        }
      ],
      "tagOwners": { "{{router}}": [] },
      "tests": [
        { "when": "param:tester && param:probe", "src": "{{tester}}", "accept": ["{{probe}}:443"] }
      ]
    },
    { "when": "param:approve", "autoApprovers": { "routes": { "{{subnet}}": ["{{router}}"] } } }
  ]
}
```

</details>

### An app connector for some domains

`app-connector` · Subnets · Not on Headscale · version 1

Traffic to the domains you name goes through a tagged Linux server, like a subnet router that works by name.

Writes the connector's domains, the grant that sends these people to the internet through it (via), the grant to its port 53 that finding the routes needs, and the approval of the routes it learns. A tag the policy does not define yet is added with no owners: only admins can apply it. The server runs tailscale up `--advertise-connector` `--advertise-tags` with that tag; Linux clients also need tailscale set `--accept-routes`.

Not on Headscale. Headscale has no app connectors.

| Field | Takes | About it |
|---|---|---|
| **App name** (required) | text | How the admin console lists it: GitHub, Jira. |
| **Domains** (required, several) | text | Every domain the app uses; \*.example.com takes its subdomains. |
| **Connector's tag** (required) | a tag; default `tag:app-connector` | The tag of the Linux server that carries the traffic. |
| **Who uses it** (required, several) | from the picker: users, groups, tags, autogroups; default `autogroup:member` | Users, groups or tags whose traffic to these domains goes through the connector. |

Read more: [Set up an app connector](https://tailscale.com/docs/features/app-connectors/how-to/setup).

<details>
<summary>The template file, <code>app-connector.json</code></summary>

```json
{
  "schema": 1,
  "id": "app-connector",
  "version": 1,
  "category": "subnets",
  "title": {
    "en": "An app connector for some domains",
    "ru": "App connector для своих доменов"
  },
  "description": {
    "en": "Traffic to the domains you name goes through a tagged Linux server, like a subnet router that works by name.\n\nWrites the connector's domains, the grant that sends these people to the internet through it (via), the grant to its port 53 that finding the routes needs, and the approval of the routes it learns. A tag the policy does not define yet is added with no owners: only admins can apply it. The server runs tailscale up --advertise-connector --advertise-tags with that tag; Linux clients also need tailscale set --accept-routes.",
    "ru": "Трафик к названным доменам идёт через Linux-сервер с тегом — как subnet-роутер, только по именам.\n\nЗапишутся домены коннектора, грант, который ведёт этих людей в интернет через него (via), грант на его порт 53, без которого маршруты не найдутся, и одобрение маршрутов, которые он узнает. Тег, которого в политике ещё нет, добавится без владельцев — назначать его смогут только администраторы. На сервере — tailscale up --advertise-connector --advertise-tags с этим тегом; клиентам на Linux нужно ещё tailscale set --accept-routes."
  },
  "headscale": {
    "supported": false,
    "note": {
      "en": "Headscale has no app connectors.",
      "ru": "В Headscale нет app connector."
    }
  },
  "docs": [
    { "title": { "en": "Set up an app connector", "ru": "Как настроить app connector" }, "url": "https://tailscale.com/docs/features/app-connectors/how-to/setup" }
  ],
  "params": [
    {
      "id": "name",
      "kind": "text",
      "label": { "en": "App name", "ru": "Название приложения" },
      "help": {
        "en": "How the admin console lists it: GitHub, Jira.",
        "ru": "Как его покажет консоль администратора: GitHub, Jira."
      },
      "required": true
    },
    {
      "id": "domains",
      "kind": "text",
      "label": { "en": "Domains", "ru": "Домены" },
      "help": {
        "en": "Every domain the app uses; *.example.com takes its subdomains.",
        "ru": "Все домены, которыми пользуется приложение; *.example.com — его поддомены."
      },
      "required": true,
      "multi": true
    },
    {
      "id": "connector",
      "kind": "tag",
      "slot": "grant-via",
      "label": { "en": "Connector's tag", "ru": "Тег коннектора" },
      "help": {
        "en": "The tag of the Linux server that carries the traffic.",
        "ru": "Тег Linux-сервера, через который идёт трафик."
      },
      "required": true,
      "default": "tag:app-connector"
    },
    {
      "id": "who",
      "kind": "selector",
      "slot": "grant-src",
      "allow": ["user", "group", "tag", "autogroup"],
      "label": { "en": "Who uses it", "ru": "Кто пользуется" },
      "help": {
        "en": "Users, groups or tags whose traffic to these domains goes through the connector.",
        "ru": "Пользователи, группы или теги, чей трафик к этим доменам пойдёт через коннектор."
      },
      "required": true,
      "multi": true,
      "default": ["autogroup:member"]
    }
  ],
  "fragments": {
    "tagOwners": { "{{connector}}": [] },
    "nodeAttrs": [
      {
        "target": ["*"],
        "app": {
          "tailscale.com/app-connectors": [
            { "name": "{{name}}", "connectors": ["{{connector}}"], "domains": ["{{domains}}"] }
          ]
        }
      }
    ],
    "access": [
      { "src": ["{{who}}"], "dst": ["autogroup:internet"], "ports": ["*"], "via": ["{{connector}}"] }
    ],
    "grants": [
      { "src": ["{{who}}"], "dst": ["{{connector}}"], "ip": ["tcp:53", "udp:53"] }
    ],
    "autoApprovers": {
      "routes": {
        "0.0.0.0/0": ["{{connector}}"],
        "::/0": ["{{connector}}"]
      }
    }
  }
}
```

</details>

### A DNS server for every device

`dns-server` · DNS · Headscale: yes · version 1

Every device of the tailnet may query a tagged DNS server on port 53, over UDP and TCP.

A tag the policy does not define yet is added with no owners: only admins can apply it. Then give the server's Tailscale address as a nameserver in the tailnet's DNS settings. Nothing is written when a rule opens port 53 of the server to everyone already.

| Field | Takes | About it |
|---|---|---|
| **DNS server's tag** (required) | a tag; default `tag:dns` | The tag of the device that answers DNS queries. |

Read more: [Grant examples](https://tailscale.com/docs/reference/examples/grants).

<details>
<summary>The template file, <code>dns-server.json</code></summary>

```json
{
  "schema": 1,
  "id": "dns-server",
  "version": 1,
  "category": "dns",
  "title": {
    "en": "A DNS server for every device",
    "ru": "DNS-сервер для всех устройств"
  },
  "description": {
    "en": "Every device of the tailnet may query a tagged DNS server on port 53, over UDP and TCP.\n\nA tag the policy does not define yet is added with no owners: only admins can apply it. Then give the server's Tailscale address as a nameserver in the tailnet's DNS settings. Nothing is written when a rule opens port 53 of the server to everyone already.",
    "ru": "Любое устройство tailnet может обращаться к DNS-серверу с тегом на порт 53 по UDP и TCP.\n\nТег, которого в политике ещё нет, добавится без владельцев — назначать его смогут только администраторы. Затем укажите адрес сервера в Tailscale как DNS-сервер в настройках DNS tailnet. Если правило уже открывает всем порт 53 этого сервера, ничего не запишется."
  },
  "headscale": { "supported": true },
  "docs": [
    { "title": { "en": "Grant examples", "ru": "Примеры грантов" }, "url": "https://tailscale.com/docs/reference/examples/grants" }
  ],
  "params": [
    {
      "id": "dns",
      "kind": "tag",
      "slot": "grant-dst",
      "label": { "en": "DNS server's tag", "ru": "Тег DNS-сервера" },
      "help": {
        "en": "The tag of the device that answers DNS queries.",
        "ru": "Тег устройства, которое отвечает на DNS-запросы."
      },
      "required": true,
      "default": "tag:dns"
    }
  ],
  "checks": [
    {
      "id": "open",
      "kind": "access-open",
      "src": ["*"],
      "dst": ["{{dns}}"],
      "ports": ["53"],
      "note": {
        "en": "A rule of the policy lets every device reach port 53 of this server already.",
        "ru": "Правило политики уже пускает любое устройство на порт 53 этого сервера."
      }
    }
  ],
  "fragments": [
    { "tagOwners": { "{{dns}}": [] } },
    {
      "when": "!check:open && style:grants",
      "grants": [
        { "src": ["*"], "dst": ["{{dns}}"], "ip": ["udp:53", "tcp:53"] }
      ]
    },
    {
      "when": "!check:open && style:acls",
      "acls": [
        { "action": "accept", "src": ["*"], "dst": ["{{dns}}:53"], "proto": "udp" },
        { "action": "accept", "src": ["*"], "dst": ["{{dns}}:53"], "proto": "tcp" }
      ]
    }
  ]
}
```

</details>

### TailSocks remote management

`tailsocks-remote` · Remote management · Headscale: yes · version 1

Lets chosen people or devices manage TailSocks on other devices: you only choose who manages whom. Writes one rule for TCP port 28115 and a test that keeps it.

Opens only TailSocks's remote-management port, TCP 28115, from the managers' devices to the managed ones. Whether a manager is the owner or a guest is decided by the managed device, by account — not by this rule.

| Field | Takes | About it |
|---|---|---|
| **Who manages** (required, several) | from the picker | Users, groups or tags whose devices manage the others; every member, for a rule where each person manages their own devices. |
| **Whose devices** (required, several) | from the picker | Users, groups or tags whose devices are managed. "Their own devices" lets each person manage only what they own. |

<details>
<summary>The template file, <code>tailsocks-remote.json</code></summary>

```json
{
  "schema": 1,
  "id": "tailsocks-remote",
  "version": 1,
  "category": "remote-management",
  "title": {
    "en": "TailSocks remote management",
    "ru": "Удалённое управление TailSocks"
  },
  "description": {
    "en": "Lets chosen people or devices manage TailSocks on other devices: you only choose who manages whom. Writes one rule for TCP port 28115 and a test that keeps it.\n\nOpens only TailSocks's remote-management port, TCP 28115, from the managers' devices to the managed ones. Whether a manager is the owner or a guest is decided by the managed device, by account — not by this rule.",
    "ru": "Выбранные люди или устройства смогут управлять TailSocks на других устройствах — от вас нужно только «кто кем». Запишется одно правило на TCP-порт 28115 и тест, который его сторожит.\n\nОткрывается только порт удалённого управления TailSocks, TCP 28115, — с устройств тех, кто управляет, на управляемые. Владелец он или гость, решает само управляемое устройство по аккаунту, а не это правило."
  },
  "headscale": { "supported": true },
  "suggest": [
    { "page": "access", "slot": "empty" },
    { "page": "access", "slot": "after" }
  ],
  "params": [
    {
      "id": "managers",
      "kind": "selector",
      "slot": "remote-from",
      "label": { "en": "Who manages", "ru": "Кто управляет" },
      "help": {
        "en": "Users, groups or tags whose devices manage the others; every member, for a rule where each person manages their own devices.",
        "ru": "Пользователи, группы или теги, с чьих устройств управляют остальными; «все участники» — для правила, где каждый управляет своими устройствами."
      },
      "required": true,
      "multi": true
    },
    {
      "id": "managed",
      "kind": "selector",
      "slot": "remote-to",
      "label": { "en": "Whose devices", "ru": "Чьими устройствами" },
      "help": {
        "en": "Users, groups or tags whose devices are managed. \"Their own devices\" lets each person manage only what they own.",
        "ru": "Пользователи, группы или теги, чьи устройства управляются. «Свои устройства» — каждый управляет только тем, что принадлежит ему."
      },
      "required": true,
      "multi": true
    }
  ],
  "checks": [
    {
      "id": "open",
      "kind": "access-open",
      "src": ["{{managers}}"],
      "dst": ["{{managed}}"],
      "ports": ["28115"],
      "proto": "tcp",
      "note": {
        "en": "The policy already lets these devices reach port 28115, so no new rule is needed; only the test is left to add.",
        "ru": "Политика уже пускает эти устройства на порт 28115, новое правило не нужно — осталось добавить только тест."
      }
    }
  ],
  "fragments": {
    "access": [
      {
        "when": "!check:open",
        "comment": {
          "en": "TailSocks remote management (TCP 28115)",
          "ru": "Удалённое управление TailSocks (TCP 28115)"
        },
        "src": ["{{managers}}"],
        "dst": ["{{managed}}"],
        "ports": ["28115"],
        "proto": "tcp"
      }
    ],
    "tests": [
      { "generate": "pair-test", "src": ["{{managers}}"], "dst": ["{{managed}}"], "port": "28115", "proto": "tcp" }
    ]
  }
}
```

</details>

### Taildrive between a person's own devices

`taildrive-own-devices` · Taildrive · Not on Headscale · version 1

Each member's devices share folders and open them on that member's other devices. Writes both attributes and the grant Taildrive needs.

The grant lets members open shares on their own devices, to read and to write. Narrow it afterwards on the Access page.

Not on Headscale. Headscale has no Taildrive.

| Field | Takes | About it |
|---|---|---|
| **Devices that share and open folders** (required, several) | from the picker; default `autogroup:member` | The devices that get both attributes. The grant stays with every member and their own devices. |

Read more: [Taildrive](https://tailscale.com/kb/1369/taildrive).

<details>
<summary>The template file, <code>taildrive-own-devices.json</code></summary>

```json
{
  "schema": 1,
  "id": "taildrive-own-devices",
  "version": 1,
  "category": "taildrive",
  "title": {
    "en": "Taildrive between a person's own devices",
    "ru": "Taildrive между своими устройствами"
  },
  "description": {
    "en": "Each member's devices share folders and open them on that member's other devices. Writes both attributes and the grant Taildrive needs.\n\nThe grant lets members open shares on their own devices, to read and to write. Narrow it afterwards on the Access page.",
    "ru": "Устройства каждого участника делятся папками и открывают их на других его устройствах. Запишутся оба атрибута и грант, без которых Taildrive не работает.\n\nГрант даёт участникам открывать папки на своих устройствах, на чтение и запись. Сузить его потом можно на странице «Доступ»."
  },
  "headscale": {
    "supported": false,
    "note": {
      "en": "Headscale has no Taildrive.",
      "ru": "В Headscale нет Taildrive."
    }
  },
  "docs": [
    { "title": { "en": "Taildrive", "ru": "Taildrive" }, "url": "https://tailscale.com/kb/1369/taildrive" }
  ],
  "suggest": [
    { "page": "attributes", "slot": "before", "when": "!(check:attrs && check:grant)" }
  ],
  "params": [
    {
      "id": "devices",
      "kind": "selector",
      "slot": "node-target",
      "label": { "en": "Devices that share and open folders", "ru": "Чьи устройства делятся и открывают папки" },
      "help": {
        "en": "The devices that get both attributes. The grant stays with every member and their own devices.",
        "ru": "Устройства, которые получат оба атрибута. Грант остаётся на всех участников и их собственные устройства."
      },
      "required": true,
      "multi": true,
      "default": ["autogroup:member"]
    }
  ],
  "checks": [
    { "id": "attrs", "kind": "node-attr", "attrs": ["drive:share", "drive:access"] },
    {
      "id": "grant",
      "kind": "grant-app",
      "cap": "tailscale.com/cap/drive",
      "note": {
        "en": "The policy has a Taildrive grant already, and it stays as it is. Check on the Access page that it lets these devices open each other's shares.",
        "ru": "Грант Taildrive в политике уже есть и останется как был. Проверьте на странице «Доступ», что он даёт этим устройствам открывать папки друг друга."
      }
    }
  ],
  "fragments": {
    "nodeAttrs": [
      { "target": ["{{devices}}"], "attr": ["drive:share", "drive:access"] }
    ],
    "grants": [
      {
        "when": "!check:grant",
        "src": ["autogroup:member"],
        "dst": ["autogroup:self"],
        "app": { "tailscale.com/cap/drive": [ { "shares": ["*"], "access": "rw" } ] }
      }
    ]
  }
}
```

</details>

### Taildrive from one group to another

`taildrive-groups` · Taildrive · Headscale: yes · version 1

Devices of one group or tag share folders; another group opens them, read-only or read and write. Writes both attributes and the grant.

The sharing side gets drive:share, the opening side drive:access, and a grant lets the second open the first's shares: all of them, or only those you name. Shares between a person's own devices are another template.

| Field | Takes | About it |
|---|---|---|
| **Whose devices share** (required, several) | from the picker: groups, users, tags | Groups, users or tags whose devices share folders: they get drive:share. |
| **Who opens them** (required, several) | from the picker: groups, users, tags | Groups, users or tags whose devices open those folders: they get drive:access. |
| **Shares** (required, several) | text; default `*` | Share names as the devices give them; \* for every share. |
| **Access** (required) | one of: Read only, Read and write; default Read only |  |

Read more: [Taildrive](https://tailscale.com/docs/features/taildrive).

<details>
<summary>The template file, <code>taildrive-groups.json</code></summary>

```json
{
  "schema": 1,
  "id": "taildrive-groups",
  "version": 1,
  "category": "taildrive",
  "title": {
    "en": "Taildrive from one group to another",
    "ru": "Taildrive от одной группы другой"
  },
  "description": {
    "en": "Devices of one group or tag share folders; another group opens them, read-only or read and write. Writes both attributes and the grant.\n\nThe sharing side gets drive:share, the opening side drive:access, and a grant lets the second open the first's shares: all of them, or only those you name. Shares between a person's own devices are another template.",
    "ru": "Устройства одной группы или тега делятся папками, другая группа их открывает — только на чтение или на чтение и запись. Запишутся оба атрибута и грант.\n\nДелящаяся сторона получит drive:share, открывающая — drive:access, а грант даст второй открывать папки первой: все или только названные. Папки между своими устройствами — в отдельном шаблоне."
  },
  "headscale": { "supported": true },
  "docs": [
    { "title": { "en": "Taildrive", "ru": "Taildrive" }, "url": "https://tailscale.com/docs/features/taildrive" }
  ],
  "params": [
    {
      "id": "sharers",
      "kind": "selector",
      "slot": "node-target",
      "allow": ["group", "user", "tag"],
      "label": { "en": "Whose devices share", "ru": "Чьи устройства делятся" },
      "help": {
        "en": "Groups, users or tags whose devices share folders: they get drive:share.",
        "ru": "Группы, пользователи или теги, чьи устройства делятся папками: им достанется drive:share."
      },
      "required": true,
      "multi": true
    },
    {
      "id": "readers",
      "kind": "selector",
      "slot": "node-target",
      "allow": ["group", "user", "tag"],
      "label": { "en": "Who opens them", "ru": "Кто открывает" },
      "help": {
        "en": "Groups, users or tags whose devices open those folders: they get drive:access.",
        "ru": "Группы, пользователи или теги, чьи устройства открывают эти папки: им достанется drive:access."
      },
      "required": true,
      "multi": true
    },
    {
      "id": "shares",
      "kind": "text",
      "label": { "en": "Shares", "ru": "Папки" },
      "help": {
        "en": "Share names as the devices give them; * for every share.",
        "ru": "Имена папок, как их дали устройства; * — все папки."
      },
      "required": true,
      "multi": true,
      "default": ["*"]
    },
    {
      "id": "access",
      "kind": "choice",
      "label": { "en": "Access", "ru": "Доступ" },
      "options": [
        { "value": "ro", "label": { "en": "Read only", "ru": "Только чтение" } },
        { "value": "rw", "label": { "en": "Read and write", "ru": "Чтение и запись" } }
      ],
      "required": true,
      "default": "ro"
    }
  ],
  "fragments": {
    "nodeAttrs": [
      { "target": ["{{sharers}}"], "attr": ["drive:share"] },
      { "target": ["{{readers}}"], "attr": ["drive:access"] }
    ],
    "grants": [
      {
        "src": ["{{readers}}"],
        "dst": ["{{sharers}}"],
        "app": { "tailscale.com/cap/drive": [ { "shares": ["{{shares}}"], "access": "{{access}}" } ] }
      }
    ]
  }
}
```

</details>

### Faster start for your devices

`faster-start` · Basics · Headscale: yes · version 1

Members' devices cache the network map and connect from it at start, before the control server answers. Add your servers' tags too.

| Field | Takes | About it |
|---|---|---|
| **Applies to** (required, several) | from the picker; default `autogroup:member` | Members' devices to start with; add the tags of servers that should start faster too. |

<details>
<summary>The template file, <code>faster-start.json</code></summary>

```json
{
  "schema": 1,
  "id": "faster-start",
  "version": 1,
  "category": "baseline",
  "title": {
    "en": "Faster start for your devices",
    "ru": "Быстрый старт для ваших устройств"
  },
  "description": {
    "en": "Members' devices cache the network map and connect from it at start, before the control server answers. Add your servers' tags too.",
    "ru": "Устройства участников кешируют карту сети и при запуске соединяются по ней, не дожидаясь сервера управления. Добавьте и теги своих серверов."
  },
  "headscale": { "supported": true },
  "suggest": [
    { "page": "attributes", "slot": "after", "when": "!check:cache" }
  ],
  "params": [
    {
      "id": "devices",
      "kind": "selector",
      "slot": "node-target",
      "label": { "en": "Applies to", "ru": "Для кого" },
      "help": {
        "en": "Members' devices to start with; add the tags of servers that should start faster too.",
        "ru": "Для начала — устройства участников; добавьте теги серверов, которым тоже нужен быстрый старт."
      },
      "required": true,
      "multi": true,
      "default": ["autogroup:member"]
    }
  ],
  "checks": [
    {
      "id": "cache",
      "kind": "node-attr",
      "attrs": ["cache-network-maps"],
      "note": {
        "en": "A rule of the policy turns the cache on already; this one adds more devices to it.",
        "ru": "Кеш уже включает одно из правил политики; это добавит к нему ещё устройства."
      }
    }
  ],
  "fragments": {
    "nodeAttrs": [
      { "target": ["{{devices}}"], "attr": ["cache-network-maps"] }
    ]
  }
}
```

</details>

### Funnel for tagged devices

`funnel-tag` · Funnel · Not on Headscale · version 1

Devices with these tags may publish a local service to the internet with Funnel.

Writes the funnel attribute for the tags. The tailnet also needs MagicDNS and HTTPS certificates, both turned on in the admin console's DNS settings. Funnel listens only on ports 443, 8443 and 10000, over TLS; each device then switches it on itself (tailscale funnel).

Not on Headscale. Headscale has no Funnel and refuses the attribute.

| Field | Takes | About it |
|---|---|---|
| **Tags that may use Funnel** (required, several) | from the picker: tags | Servers by their tags; people's own devices are left as they are. |

Read more: [Tailscale Funnel](https://tailscale.com/docs/features/tailscale-funnel).

<details>
<summary>The template file, <code>funnel-tag.json</code></summary>

```json
{
  "schema": 1,
  "id": "funnel-tag",
  "version": 1,
  "category": "funnel",
  "title": {
    "en": "Funnel for tagged devices",
    "ru": "Funnel для устройств с тегом"
  },
  "description": {
    "en": "Devices with these tags may publish a local service to the internet with Funnel.\n\nWrites the funnel attribute for the tags. The tailnet also needs MagicDNS and HTTPS certificates, both turned on in the admin console's DNS settings. Funnel listens only on ports 443, 8443 and 10000, over TLS; each device then switches it on itself (tailscale funnel).",
    "ru": "Устройства с этими тегами смогут выставлять локальный сервис в интернет через Funnel.\n\nЗапишется атрибут funnel для этих тегов. Ещё tailnet нужны MagicDNS и HTTPS-сертификаты — оба включаются в настройках DNS в консоли администратора. Funnel слушает только порты 443, 8443 и 10000 и только по TLS; включает его каждое устройство само (tailscale funnel)."
  },
  "headscale": {
    "supported": false,
    "note": {
      "en": "Headscale has no Funnel and refuses the attribute.",
      "ru": "В Headscale нет Funnel, и атрибут он не принимает."
    }
  },
  "docs": [
    { "title": { "en": "Tailscale Funnel", "ru": "Tailscale Funnel" }, "url": "https://tailscale.com/docs/features/tailscale-funnel" }
  ],
  "params": [
    {
      "id": "devices",
      "kind": "selector",
      "slot": "node-target",
      "allow": ["tag"],
      "label": { "en": "Tags that may use Funnel", "ru": "Теги, которым можно Funnel" },
      "help": {
        "en": "Servers by their tags; people's own devices are left as they are.",
        "ru": "Серверы по их тегам; личные устройства людей остаются как были."
      },
      "required": true,
      "multi": true
    }
  ],
  "checks": [
    {
      "id": "funnel",
      "kind": "node-attr",
      "attrs": ["funnel"],
      "note": {
        "en": "A rule of the policy turns Funnel on for some devices already; this one adds these tags.",
        "ru": "Funnel уже включает одно из правил политики; это добавит к нему ещё теги."
      }
    }
  ],
  "fragments": {
    "nodeAttrs": [
      { "target": ["{{devices}}"], "attr": ["funnel"] }
    ]
  }
}
```

</details>

### Relays for devices behind a strict NAT

`relays-site` · Relays · Headscale: yes · version 1

Choose the relays and the devices that use them: servers in an office or a cloud network, not phones and laptops.

| Field | Takes | About it |
|---|---|---|
| **Relay devices** (required, several) | from the picker | The devices that relay, by tag, host, IP set or Tailscale address. Each relays only once it is switched on there: tailscale set `--relay-server-port=40000`. |
| **May use them** (required, several) | from the picker | Devices that stay in one place behind a strict NAT or firewall, in an office or a cloud network. Not phones and laptops that change networks, and not everyone: each of them would try the relays. |

Read more: [Peer relays](https://tailscale.com/kb/1591/peer-relays).

<details>
<summary>The template file, <code>relays-site.json</code></summary>

```json
{
  "schema": 1,
  "id": "relays-site",
  "version": 1,
  "category": "relays",
  "title": {
    "en": "Relays for devices behind a strict NAT",
    "ru": "Реле для устройств за строгим NAT"
  },
  "description": {
    "en": "Choose the relays and the devices that use them: servers in an office or a cloud network, not phones and laptops.",
    "ru": "Выберите реле и устройства, которые ими пользуются: серверы в офисе или облачной сети, а не телефоны и ноутбуки."
  },
  "headscale": { "supported": true },
  "docs": [
    { "title": { "en": "Peer relays", "ru": "Реле-узлы" }, "url": "https://tailscale.com/kb/1591/peer-relays" }
  ],
  "suggest": [ { "page": "peer-relays", "slot": "empty" } ],
  "params": [
    {
      "id": "relays",
      "kind": "selector",
      "slot": "relay-dst",
      "label": { "en": "Relay devices", "ru": "Устройства-реле" },
      "help": {
        "en": "The devices that relay, by tag, host, IP set or Tailscale address. Each relays only once it is switched on there: tailscale set --relay-server-port=40000.",
        "ru": "Устройства, которые ретранслируют: по тегу, хосту, набору IP или адресу Tailscale. Реле заработает, только когда его включат на самом устройстве: tailscale set --relay-server-port=40000."
      },
      "required": true,
      "multi": true
    },
    {
      "id": "clients",
      "kind": "selector",
      "slot": "relay-src",
      "label": { "en": "May use them", "ru": "Кто может пользоваться" },
      "help": {
        "en": "Devices that stay in one place behind a strict NAT or firewall, in an office or a cloud network. Not phones and laptops that change networks, and not everyone: each of them would try the relays.",
        "ru": "Устройства, которые стоят на месте за строгим NAT или файрволом: в офисе, в облачной сети. Не телефоны и ноутбуки, меняющие сети, и не все подряд: каждое из них станет пробовать реле."
      },
      "required": true,
      "multi": true
    }
  ],
  "checks": [
    { "id": "devices", "kind": "relay-devices", "relays": ["{{relays}}"], "clients": ["{{clients}}"] }
  ],
  "fragments": {
    "grants": [
      { "src": ["{{clients}}"], "dst": ["{{relays}}"], "app": { "tailscale.com/cap/relay": [] } }
    ]
  }
}
```

</details>

### A device relays for itself

`relays-self` · Relays · Headscale: yes · version 1

A server behind a firewall that lets in only its relay port is its own relay.

| Field | Takes | About it |
|---|---|---|
| **Devices that relay for themselves** (required, several) | from the picker | The devices that relay, by tag, host, IP set or Tailscale address. Each relays only once it is switched on there: tailscale set `--relay-server-port=40000`. |

Read more: [Peer relays](https://tailscale.com/kb/1591/peer-relays).

<details>
<summary>The template file, <code>relays-self.json</code></summary>

```json
{
  "schema": 1,
  "id": "relays-self",
  "version": 1,
  "category": "relays",
  "title": {
    "en": "A device relays for itself",
    "ru": "Устройство само себе реле"
  },
  "description": {
    "en": "A server behind a firewall that lets in only its relay port is its own relay.",
    "ru": "Сервер за файрволом, открытым только для порта реле, сам служит себе реле."
  },
  "headscale": { "supported": true },
  "docs": [
    { "title": { "en": "Peer relays", "ru": "Реле-узлы" }, "url": "https://tailscale.com/kb/1591/peer-relays" }
  ],
  "suggest": [ { "page": "peer-relays", "slot": "empty" } ],
  "params": [
    {
      "id": "devices",
      "kind": "selector",
      "slot": "relay-dst",
      "label": { "en": "Devices that relay for themselves", "ru": "Устройства, которые сами себе реле" },
      "help": {
        "en": "The devices that relay, by tag, host, IP set or Tailscale address. Each relays only once it is switched on there: tailscale set --relay-server-port=40000.",
        "ru": "Устройства, которые ретранслируют: по тегу, хосту, набору IP или адресу Tailscale. Реле заработает, только когда его включат на самом устройстве: tailscale set --relay-server-port=40000."
      },
      "required": true,
      "multi": true
    }
  ],
  "checks": [
    { "id": "devices", "kind": "relay-devices", "relays": ["{{devices}}"], "clients": ["{{devices}}"] }
  ],
  "fragments": {
    "grants": [
      { "src": ["{{devices}}"], "dst": ["{{devices}}"], "app": { "tailscale.com/cap/relay": [] } }
    ]
  }
}
```

</details>

### A k3s cluster over the tailnet

`k3s-cluster` · Kubernetes · Headscale: yes · version 1

Opens what k3s needs between nodes tagged as servers and agents, and nothing else between them.

TCP 6443 to the servers (the API and the supervisor agents join through), TCP 10250 between all nodes (kubelet), UDP 8472 between all nodes for Flannel VXLAN or UDP 51820–51821 for Flannel WireGuard, and TCP 2379–2380 between servers for embedded etcd. Start k3s on the Tailscale addresses (`--node-ip` and `--flannel-iface` tailscale0) so this traffic stays in the tailnet. Tags the policy does not define yet are added with no owners: only admins can apply them.

| Field | Takes | About it |
|---|---|---|
| **Servers (control plane)** (required) | a tag; default `tag:k3s-server` | The tag of the nodes started with k3s server. |
| **Agents (workers)** (required) | a tag; default `tag:k3s-agent` | The tag of the nodes started with k3s agent. |
| **Flannel backend** (required) | one of: VXLAN, WireGuard; default VXLAN | VXLAN is k3s's default; WireGuard is `--flannel-backend=wireguard-native`. |
| **Several servers with embedded etcd** | a switch; default on | A highly available control plane: the servers reach each other's etcd. |
| **Who runs kubectl** (several) | from the picker: users, groups, tags, autogroups | People or tags that reach the Kubernetes API on the servers. Empty: nobody from outside the cluster. |

Read more: [k3s: inbound rules](https://github.com/k3s-io/docs/blob/main/docs/installation/requirements.md).

<details>
<summary>The template file, <code>k3s-cluster.json</code></summary>

```json
{
  "schema": 1,
  "id": "k3s-cluster",
  "version": 1,
  "category": "k8s",
  "title": {
    "en": "A k3s cluster over the tailnet",
    "ru": "Кластер k3s через tailnet"
  },
  "description": {
    "en": "Opens what k3s needs between nodes tagged as servers and agents, and nothing else between them.\n\nTCP 6443 to the servers (the API and the supervisor agents join through), TCP 10250 between all nodes (kubelet), UDP 8472 between all nodes for Flannel VXLAN or UDP 51820–51821 for Flannel WireGuard, and TCP 2379–2380 between servers for embedded etcd. Start k3s on the Tailscale addresses (--node-ip and --flannel-iface tailscale0) so this traffic stays in the tailnet. Tags the policy does not define yet are added with no owners: only admins can apply them.",
    "ru": "Открывает между узлами с тегами серверов и агентов то, что нужно k3s, и больше ничего.\n\nTCP 6443 к серверам (API и супервизор, через который подключаются агенты), TCP 10250 между всеми узлами (kubelet), UDP 8472 между всеми узлами для Flannel VXLAN или UDP 51820–51821 для Flannel WireGuard и TCP 2379–2380 между серверами для встроенного etcd. Запускайте k3s на адресах Tailscale (--node-ip и --flannel-iface tailscale0), чтобы этот трафик шёл внутри tailnet. Теги, которых в политике ещё нет, добавятся без владельцев — назначать их смогут только администраторы."
  },
  "headscale": { "supported": true },
  "docs": [
    { "title": { "en": "k3s: inbound rules", "ru": "k3s: входящие порты" }, "url": "https://github.com/k3s-io/docs/blob/main/docs/installation/requirements.md" }
  ],
  "params": [
    {
      "id": "servers",
      "kind": "tag",
      "slot": "grant-dst",
      "label": { "en": "Servers (control plane)", "ru": "Серверы (control plane)" },
      "help": {
        "en": "The tag of the nodes started with k3s server.",
        "ru": "Тег узлов, запущенных как k3s server."
      },
      "required": true,
      "default": "tag:k3s-server"
    },
    {
      "id": "agents",
      "kind": "tag",
      "slot": "grant-dst",
      "label": { "en": "Agents (workers)", "ru": "Агенты (воркеры)" },
      "help": {
        "en": "The tag of the nodes started with k3s agent.",
        "ru": "Тег узлов, запущенных как k3s agent."
      },
      "required": true,
      "default": "tag:k3s-agent"
    },
    {
      "id": "flannel",
      "kind": "choice",
      "label": { "en": "Flannel backend", "ru": "Бэкенд Flannel" },
      "help": {
        "en": "VXLAN is k3s's default; WireGuard is --flannel-backend=wireguard-native.",
        "ru": "VXLAN — по умолчанию в k3s; WireGuard — это --flannel-backend=wireguard-native."
      },
      "options": [
        { "value": "vxlan", "label": { "en": "VXLAN", "ru": "VXLAN" } },
        { "value": "wireguard-native", "label": { "en": "WireGuard", "ru": "WireGuard" } }
      ],
      "required": true,
      "default": "vxlan"
    },
    {
      "id": "etcd",
      "kind": "bool",
      "label": { "en": "Several servers with embedded etcd", "ru": "Несколько серверов со встроенным etcd" },
      "help": {
        "en": "A highly available control plane: the servers reach each other's etcd.",
        "ru": "Отказоустойчивый control plane: серверы ходят в etcd друг к другу."
      },
      "default": true
    },
    {
      "id": "kubectl",
      "kind": "selector",
      "slot": "grant-src",
      "allow": ["user", "group", "tag", "autogroup"],
      "label": { "en": "Who runs kubectl", "ru": "Кто работает с kubectl" },
      "help": {
        "en": "People or tags that reach the Kubernetes API on the servers. Empty: nobody from outside the cluster.",
        "ru": "Люди или теги, которым открыт API Kubernetes на серверах. Пусто — никому извне кластера."
      },
      "multi": true
    }
  ],
  "fragments": {
    "tagOwners": { "{{servers}}": [], "{{agents}}": [] },
    "access": [
      {
        "comment": { "en": "k3s: API and supervisor", "ru": "k3s: API и супервизор" },
        "src": ["{{servers}}", "{{agents}}"],
        "dst": ["{{servers}}"],
        "ports": ["6443"],
        "proto": "tcp"
      },
      {
        "comment": { "en": "k3s: kubelet", "ru": "k3s: kubelet" },
        "src": ["{{servers}}", "{{agents}}"],
        "dst": ["{{servers}}", "{{agents}}"],
        "ports": ["10250"],
        "proto": "tcp"
      },
      {
        "when": "param:flannel=vxlan",
        "comment": { "en": "k3s: Flannel VXLAN", "ru": "k3s: Flannel VXLAN" },
        "src": ["{{servers}}", "{{agents}}"],
        "dst": ["{{servers}}", "{{agents}}"],
        "ports": ["8472"],
        "proto": "udp"
      },
      {
        "when": "param:flannel=wireguard-native",
        "comment": { "en": "k3s: Flannel WireGuard", "ru": "k3s: Flannel WireGuard" },
        "src": ["{{servers}}", "{{agents}}"],
        "dst": ["{{servers}}", "{{agents}}"],
        "ports": ["51820", "51821"],
        "proto": "udp"
      },
      {
        "when": "param:etcd",
        "comment": { "en": "k3s: embedded etcd", "ru": "k3s: встроенный etcd" },
        "src": ["{{servers}}"],
        "dst": ["{{servers}}"],
        "ports": ["2379-2380"],
        "proto": "tcp"
      },
      {
        "comment": { "en": "kubectl to the cluster", "ru": "kubectl к кластеру" },
        "src": ["{{kubectl}}"],
        "dst": ["{{servers}}"],
        "ports": ["6443"],
        "proto": "tcp"
      }
    ],
    "tests": [
      { "generate": "pair-test", "src": ["{{agents}}"], "dst": ["{{servers}}"], "port": "6443", "proto": "tcp" },
      { "generate": "pair-test", "src": ["{{kubectl}}"], "dst": ["{{servers}}"], "port": "6443", "proto": "tcp" }
    ]
  }
}
```

</details>

### Only up-to-date devices reach a destination

`posture-tailscale-version` · Access · Not on Headscale · version 1

A device posture: the path opens only from devices whose Tailscale is at least the version you name.

Writes the posture and an access rule that requires it (srcPosture). Rules only allow: while another rule opens the same path without a posture, out-of-date devices still get through — the page shows such rules. Older clients lose only this path, not the tailnet.

Not on Headscale. Headscale has no device postures.

| Field | Takes | About it |
|---|---|---|
| **Who** (required, several) | from the picker | Users, groups or tags whose devices must pass the posture. |
| **Reaches** (required, several) | from the picker | The devices, tags or addresses the path leads to. |
| **Ports** (required, several) | ports; default `*` | A port, a range such as 8000-8100, or \* for every port. |
| **Tailscale at least** (required) | text | A version such as 1.98: devices on an older client lose this path. |
| **Posture name** (required) | text; default `recentTailscale` | A posture of that name the policy has already gets this condition added to its own. |

Read more: [Postures in access rules](https://tailscale.com/docs/features/device-posture/postures-in-access-rules), [Posture attributes](https://tailscale.com/docs/features/device-posture/postures-and-attributes).

<details>
<summary>The template file, <code>posture-tailscale-version.json</code></summary>

```json
{
  "schema": 1,
  "id": "posture-tailscale-version",
  "version": 1,
  "category": "access",
  "title": {
    "en": "Only up-to-date devices reach a destination",
    "ru": "Только обновлённые устройства — к цели"
  },
  "description": {
    "en": "A device posture: the path opens only from devices whose Tailscale is at least the version you name.\n\nWrites the posture and an access rule that requires it (srcPosture). Rules only allow: while another rule opens the same path without a posture, out-of-date devices still get through — the page shows such rules. Older clients lose only this path, not the tailnet.",
    "ru": "Состояние устройства (posture): путь открыт только с устройств, где Tailscale не старее названной версии.\n\nЗапишутся posture и правило доступа, которое её требует (srcPosture). Правила только разрешают: пока другое правило открывает тот же путь без posture, устаревшие устройства всё равно пройдут — страница покажет такие правила. Старые клиенты потеряют только этот путь, а не весь tailnet."
  },
  "headscale": {
    "supported": false,
    "note": {
      "en": "Headscale has no device postures.",
      "ru": "В Headscale нет posture устройств."
    }
  },
  "docs": [
    { "title": { "en": "Postures in access rules", "ru": "Posture в правилах доступа" }, "url": "https://tailscale.com/docs/features/device-posture/postures-in-access-rules" },
    { "title": { "en": "Posture attributes", "ru": "Атрибуты posture" }, "url": "https://tailscale.com/docs/features/device-posture/postures-and-attributes" }
  ],
  "params": [
    {
      "id": "who",
      "kind": "selector",
      "slot": "grant-src",
      "label": { "en": "Who", "ru": "Кто" },
      "help": {
        "en": "Users, groups or tags whose devices must pass the posture.",
        "ru": "Пользователи, группы или теги, чьи устройства должны пройти проверку posture."
      },
      "required": true,
      "multi": true
    },
    {
      "id": "dst",
      "kind": "selector",
      "slot": "grant-dst",
      "label": { "en": "Reaches", "ru": "Куда" },
      "help": {
        "en": "The devices, tags or addresses the path leads to.",
        "ru": "Устройства, теги или адреса, к которым ведёт путь."
      },
      "required": true,
      "multi": true
    },
    {
      "id": "ports",
      "kind": "port",
      "label": { "en": "Ports", "ru": "Порты" },
      "help": {
        "en": "A port, a range such as 8000-8100, or * for every port.",
        "ru": "Порт, диапазон вроде 8000-8100 или * — все порты."
      },
      "required": true,
      "multi": true,
      "default": ["*"]
    },
    {
      "id": "version",
      "kind": "text",
      "format": "name",
      "label": { "en": "Tailscale at least", "ru": "Tailscale не старее" },
      "help": {
        "en": "A version such as 1.98: devices on an older client lose this path.",
        "ru": "Версия вроде 1.98: устройства со старым клиентом потеряют этот путь."
      },
      "required": true
    },
    {
      "id": "posture",
      "kind": "text",
      "format": "tag-name",
      "label": { "en": "Posture name", "ru": "Имя posture" },
      "help": {
        "en": "A posture of that name the policy has already gets this condition added to its own.",
        "ru": "Если posture с таким именем в политике уже есть, условие добавится к её собственным."
      },
      "required": true,
      "default": "recentTailscale"
    }
  ],
  "checks": [
    {
      "id": "open",
      "kind": "access-open",
      "src": ["{{who}}"],
      "dst": ["{{dst}}"],
      "ports": ["{{ports}}"],
      "note": {
        "en": "Rules of the policy open this path without a posture already: until they are narrowed, the posture here changes nothing.",
        "ru": "Правила политики уже открывают этот путь без posture: пока их не сузить, posture здесь ничего не изменит."
      }
    }
  ],
  "fragments": {
    "postures": {
      "posture:{{posture}}": ["node:tsVersion >= '{{version}}'"]
    },
    "access": [
      { "src": ["{{who}}"], "dst": ["{{dst}}"], "ports": ["{{ports}}"], "srcPosture": ["posture:{{posture}}"] }
    ]
  }
}
```

</details>

<!-- templates:end -->

## Write your own

A template is one JSON file: the fields of its form, what it writes into each section of the policy, and checks of what the policy has already. It is data only — nothing in a template runs, and nothing is fetched because a template says so. The format is described in [`policy-templates/README.md`](https://github.com/bropines/tailsocks/blob/main/policy-templates/README.md).

Pull requests are welcome. Add `<id>.json` to `policy-templates/`, run `python3 policy-templates/update_index.py`, and then `./gradlew :app:testDebugUnitTest --tests '*Template*'`: the test renders every template into several policies, on Tailscale and on Headscale. Once a new template is published here, the app picks it up without an update.
