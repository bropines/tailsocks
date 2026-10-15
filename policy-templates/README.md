# Policy templates

[Русская версия](README_RU.md)

Ready-made pieces of a Tailscale (or Headscale) policy that TailSocks's visual policy editor
offers: a form with a few fields, a preview of exactly what will be written, and Apply. Each
template is one JSON file in this directory; nothing in a template is ever run, and nothing is
fetched because a template says so.

This directory is the only copy. The build copies it into the APK
(`assets/policy-templates/`, task `copyPolicyTemplates`), so the app always has these; the same
files are published at `https://bropines.github.io/tailsocks/templates/`, from where the app
picks up newer versions (see *Where the app takes them from*).

## Adding or changing a template

1. Write or edit `<id>.json` (the format is below). Plain JSON: no comments, no trailing commas.
2. Raise its `version` when you change a published template.
3. Run `python3 policy-templates/update_index.py`: it rewrites `index.json` with every file's
   `sha256` (the order of `index.json` is the catalog's order; new files go last).
4. Run `./gradlew :app:testDebugUnitTest --tests '*Template*'`. `BundledTemplatesTest` fails
   while a file does not parse, breaks a rule below, or disagrees with `index.json`; it also
   renders every template into several policies on both backends.

## Where the app takes them from

1. **Bundled**: the copy in the APK. Always there.
2. **Remote**: `https://bropines.github.io/tailsocks/templates/index.json` and the files it
   lists, over HTTPS only, through the admin console's connection (its proxy setting), at most
   once in six hours when the catalog is opened, or when refreshed by hand. The index is at most
   64 KB, each file 64 KB, the whole set 1 MB; a file whose `sha256` is not the one the index
   lists is rejected, and so is one that does not parse or breaks a rule.
3. **Cache**: the last remote set that checked out, in the app's files.

A remote (cached) template replaces the bundled one with the same `id` only when its `version`
is higher and the app is at least its `minApp`; a new `id` is added after the bundled ones.

## A template file

```json
{
  "schema": 1,
  "id": "tailsocks-remote",
  "version": 1,
  "minApp": "5.1.0",
  "category": "remote-management",
  "title": { "en": "TailSocks remote management", "ru": "Удалённое управление TailSocks" },
  "description": { "en": "…", "ru": "…" },
  "headscale": { "supported": true, "note": { "en": "…" } },
  "docs": [ { "title": { "en": "Access rules" }, "url": "https://tailscale.com/kb/1337/policy-syntax" } ],
  "suggest": [ { "page": "access", "slot": "after", "when": "!check:open" } ],
  "params": [ … ],
  "checks": [ … ],
  "fragments": { … }
}
```

| Field | |
|---|---|
| `schema` | The format's version: 1. A file of a newer schema is skipped. |
| `id` | Stable, kebab-case (`own-devices`), at most 64 characters. |
| `version` | An integer from 1; a remote file replaces the bundled one only with a higher one. |
| `minApp` | Optional: the oldest app that may use it, a version name (`"5.1.0"`) or a version code (`5010000`). |
| `category` | Kebab-case: `access`, `ssh`, `exit-nodes`, `subnets`, `taildrive`, `remote-management`, `relays`, `dns`, `funnel`, `k8s`, `guests`, `baseline`, … The catalog's filter. |
| `title`, `description` | Text by language; `en` is required, `ru` expected. A blank line in a description starts a part that stays folded. |
| `headscale` | `supported`: `true`, `false` or `"partial"`, and an optional `note`. `false` hides the template on Headscale; `"partial"` shows the note. |
| `docs` | Links the template's page offers: `https`, on tailscale.com, headscale.net, github.com or bropines.github.io only. |
| `suggest` | Optional: where a page of the editor shows the template as a card of its own, besides the catalog. `page`: `access`, `attributes`, `peer-relays`; `slot`: `empty` (among the templates of a page with no rules), `before` (over the page's list), `after` (under it); `when`: a condition, evaluated with the form's defaults. |

### Parameters

```json
{ "id": "managers", "kind": "selector", "slot": "remote-from", "allow": ["user", "group", "tag"],
  "label": { "en": "Who manages" }, "help": { "en": "…" }, "required": true, "multi": true,
  "default": ["autogroup:member"] }
```

`id` is lowercase (`[a-z][a-z0-9_-]*`); `label` is required, `help` optional; `required` and
`multi` are false unless said. `default` is a string, a list of strings (`multi`) or a boolean.

| `kind` | The field | A value |
|---|---|---|
| `selector` | The editor's own picker for `slot`, offering only the kinds in `allow` when given (`any`, `autogroup`, `group`, `tag`, `user`, `user-domain`, `host`, `ip`, `cidr`, `ipset`, `service`, `posture`). | What the picker writes: `group:admins`, `tag:web`, `alice@example.com` (Headscale's `alice@`), `autogroup:member` … |
| `user`, `group`, `tag` | The same picker, only that kind. | |
| `port` | Port chips and a typed list. | `22`, `8000-8100`, `*` — one per item. |
| `host` | A text field. | An IP address. |
| `cidr` | A text field. | An address with its prefix: `192.168.1.0/24`. |
| `text` | A text field, checked by `format`: `any`, `name`, `tag-name`, `login`, `duration`. | At most 200 characters. |
| `bool` | A switch. | `true` / `false`; never `multi`. |
| `choice` | Chips of `options` (`[{"value": "check", "label": {…}}]`). | One `value` (several with `multi`). |

`slot` names the editor's selector slots: `acl-src`, `acl-dst`, `grant-src`, `grant-dst`,
`grant-via`, `ssh-src`, `ssh-dst`, `ssh-user`, `node-target`, `tag-owner`, `approver`,
`test-src`, `test-dst`, `ssh-test-src`, `ssh-test-dst`, `group-member`, `posture`, `relay-src`,
`relay-dst`, `remote-from`, `remote-to`. The slot decides which autogroups the picker offers on
each backend; a `selector` needs one, `user`/`group`/`tag` default to `acl-src`.

### Fragments

What Apply merges into the policy, by section. `fragments` is one block — an object of
sections — or a list of blocks, each of which may have a `when`:

```json
"fragments": [
  { "access": [ … ], "tests": [ … ] },
  { "when": "param:approve", "autoApprovers": { "exitNode": ["{{who}}"] } }
]
```

**Lists** — `access`, `acls`, `grants`, `ssh`, `nodeAttrs`, `tests`, `sshTests`: each item is
added at the end of its section (the section is written when the file has none). An item the
policy already has — the same JSON, whatever its layout — is not written twice. An item may
carry two keys that are never written into the policy:

- `when`: a condition; the item is written only while it holds.
- `comment`: text by language, written as the comment above the item (placeholders allowed).

**`access`** is an access rule in a neutral form, written in the syntax the file already uses
(the one it has more rules of; on an empty file grants on Tailscale, ACLs on Headscale):

```json
{ "src": ["{{managers}}"], "dst": ["{{managed}}"], "ports": ["28115"], "proto": "tcp" }
```

`src` and `dst` are required; `ports` defaults to `["*"]`; `proto`, `via` and `srcPosture` are
optional. As an ACL: `{"action": "accept", "src": …, "dst": ["<dst>:<ports joined by ,>"], "proto": …}`.
As a grant: `{"src": …, "dst": …, "ip": ["<proto>:<port>", …]}`; an item with `via` is always a
grant. An item whose `src` or `dst` comes out empty is left out. A rule to `autogroup:self`
from a tag is refused before Apply (a tag owns no devices).

**Objects** — `groups`, `tagOwners`, `ipsets`, `postures`: each key's list is merged into the
list of that name — the values it lacks are added after its own, the list is created when the
file has none. `hosts`: a name and its address; a name the file has with another address is a
conflict that stops Apply. `autoApprovers`: `routes` (prefix → approvers), `exitNode`
(approvers) and `services` (service → approvers), merged the same way.

Every other section is refused: a template cannot touch `derpMap`, `nodeAttrs` rules already in
the file, or anything it did not add.

### Placeholders

A string may name a parameter as `{{id}}` or `{{id|join}}`. Keys and values alike; nothing but a
form's value is ever substituted. What it becomes depends on where the string stands:

| Where | `"{{p}}"` alone | Text around it: `"{{host}}:{{port}}"` |
|---|---|---|
| An item of a list | Every item of `p`, spliced in — none when `p` is empty. A `bool` is one boolean. | One string per combination of the items of the placeholders, the first varying slowest: `["a:22", "a:80", "b:22", "b:80"]`; none when one of them is empty. |
| A field's value | A `multi` parameter: a list of its items. A single one: its value. A `bool`: a boolean. Nothing to write: the field is left out. | One string; every placeholder in it must be single, a `bool`, or `|join`ed. |
| A key | One string, as a value with text around it. | One string, the same way. |

`{{p|join}}` is always one string: the items joined by `,` (`"80,443"`). A `multi` parameter
without `|join` where one string must stand is an error, found when the file is read. A `{{`
or `}}` that is not a placeholder is an error too.

### Conditions

`when` (on a block, an item or a suggestion) is names joined by `!`, `&&`, `||` and
parentheses:

- `param:<id>` — the parameter is set (a switch on, a list not empty); `param:<id>=<value>` —
  one of its items is that value;
- `check:<id>` — the check holds;
- `headscale`, `tailscale` — the backend;
- `style:acls`, `style:grants` — the syntax new access rules are written in.

### Checks

What the policy has already, decided before anything is written. The page shows a check's
`note` while it holds; conditions name it as `check:<id>`. Lists take placeholders.

| `kind` | Fields | Holds when |
|---|---|---|
| `access-open` | `src`, `dst`, `ports`, `proto` | Rules of the file already let every source reach every destination on all of `ports` (no posture, no `via`). The page shows those rules. |
| `grant-app` | `cap` | Some grant carries that application capability. |
| `node-attr` | `attrs` | Each attribute is turned on by some device attribute rule. |
| `relay-devices` | `relays`, `clients` | The device list shows a device that will not take part (a phone cannot relay, a client older than 1.86, a shared device), or the clients are everyone: the page lists them. |

### Engine features

What is logic rather than data stays in the app, and a template asks for it by name:

- `"generate": "pair-test"` in `tests`, with `src`, `dst`, `port` and optional `proto`: the one
  test that pins a new path — the first source a test may name (a user, a group, a tag) to the
  first such destination on `port`, a pair whose sides both have devices when the device list
  is known; none when the file has that test or no pair can be named.
- The `access-open` check (the remote-management duplicate detection), and `relay-devices`
  (what the device list says about relays and their clients).

## Limits

At most 16 parameters, 16 checks and 50 items per template; texts of at most 4000 characters;
values of at most 200. The index lists at most 100 templates.
