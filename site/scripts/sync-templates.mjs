#!/usr/bin/env node
/*
 * Publishes the policy templates of a checkout of the app (the main branch) and regenerates
 * their catalog pages:
 *
 *     cd site && node scripts/sync-templates.mjs /path/to/tailsocks
 *
 *     policy-templates/<id>.json   →  public/templates/<id>.json   (byte for byte)
 *     policy-templates/index.json  →  public/templates/index.json  (written anew, see below)
 *     the templates themselves     →  src/content/docs/templates.md and ru/templates.md,
 *                                     between <!-- templates:start --> and <!-- templates:end -->
 *
 * The app fetches https://bropines.github.io/tailsocks/templates/index.json and the files it
 * lists (TemplateSource.kt). The index written here is exactly what its loader reads —
 * `{"schema": 1, "templates": [{"id", "file", "version", "sha256", "minApp"?}]}` — in the order
 * of the checkout's index.json, which is the catalog's order, with each sha256 computed from
 * the bytes copied. What the loader would turn away stops the script instead of reaching the
 * site:
 *   - the checkout's index.json must list every template file and agree with each one's
 *     sha256, id and version (run policy-templates/update_index.py there when it does not);
 *   - a file is named `<id>.json`, lowercase, at most 64 characters before `.json`;
 *   - at most 100 templates, the index at most 64 KB, each file at most 64 KB, the whole set
 *     at most 1 MB.
 * Files in public/templates/ that the index no longer lists are removed.
 *
 * Each catalog page gets one section per template, from its file: the title, the category,
 * where Headscale stands, the description, the fields of its form, its documentation links and
 * the file itself in a folded block — in the page's language, English where a template has no
 * Russian. Everything outside the two markers is the pages' own text and is left alone.
 *
 * Run it whenever policy-templates/ changes on main (at the latest at a release), check the
 * diff, and commit public/templates/ and the two pages on the `site` branch.
 */
import crypto from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const checkout = process.argv[2];
if (!checkout) {
	console.error('usage: node scripts/sync-templates.mjs /path/to/tailsocks');
	process.exit(2);
}

const site = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const source = path.join(checkout, 'policy-templates');
const target = path.join(site, 'public/templates');

// The loader's limits: TemplateSource.kt, its companion object.
const SCHEMA = 1;
const MAX_TEMPLATES = 100;
const MAX_INDEX_BYTES = 64 * 1024;
const MAX_FILE_BYTES = 64 * 1024;
const MAX_TOTAL_BYTES = 1024 * 1024;
const FILE = /^[a-z0-9][a-z0-9-]{0,63}\.json$/;

function fail(message) {
	console.error(`sync-templates: ${message}`);
	process.exit(1);
}

const sha256 = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');

// ─── The set ────────────────────────────────────────────────────────────────────────────────
const index = JSON.parse(fs.readFileSync(path.join(source, 'index.json'), 'utf8'));
if (index.schema !== SCHEMA) fail(`index.json is schema ${index.schema}; this script writes ${SCHEMA}`);
const listed = index.templates ?? [];
if (listed.length > MAX_TEMPLATES) fail(`${listed.length} templates; the app reads at most ${MAX_TEMPLATES}`);

const onDisk = fs.readdirSync(source).filter((f) => f.endsWith('.json') && f !== 'index.json');
const unlisted = onDisk.filter((f) => !listed.some((e) => e.file === f)).sort();
if (unlisted.length) fail(`not in index.json: ${unlisted.join(', ')} (run policy-templates/update_index.py)`);

const templates = listed.map((e) => {
	if (!FILE.test(e.file)) fail(`file name "${e.file}"`);
	const bytes = fs.readFileSync(path.join(source, e.file));
	if (bytes.length > MAX_FILE_BYTES) fail(`${e.file} is ${bytes.length} bytes; the app takes at most ${MAX_FILE_BYTES}`);
	const sum = sha256(bytes);
	if (sum !== e.sha256) fail(`${e.file}: its sha256 is not the one index.json lists (run policy-templates/update_index.py)`);
	const t = JSON.parse(bytes.toString('utf8'));
	if (t.id !== e.id || t.version !== e.version || `${t.id}.json` !== e.file) {
		fail(`${e.file} is ${t.id} ${t.version}; index.json says ${e.id} ${e.version}`);
	}
	const entry = { id: t.id, file: e.file, version: t.version, sha256: sum };
	if (t.minApp !== undefined) entry.minApp = t.minApp;
	return { entry, bytes, t };
});

const indexText = `${JSON.stringify({ schema: SCHEMA, templates: templates.map((x) => x.entry) }, null, 2)}\n`;
const indexBytes = Buffer.from(indexText, 'utf8');
if (indexBytes.length > MAX_INDEX_BYTES) fail(`the index is ${indexBytes.length} bytes; the app reads at most ${MAX_INDEX_BYTES}`);
const total = indexBytes.length + templates.reduce((n, x) => n + x.bytes.length, 0);
if (total > MAX_TOTAL_BYTES) fail(`the set is ${total} bytes; the app reads at most ${MAX_TOTAL_BYTES}`);

// ─── public/templates/ ──────────────────────────────────────────────────────────────────────
fs.mkdirSync(target, { recursive: true });
let touched = 0;
function put(name, bytes) {
	const file = path.join(target, name);
	if (fs.existsSync(file) && fs.readFileSync(file).equals(bytes)) return;
	fs.writeFileSync(file, bytes);
	touched++;
}
for (const x of templates) put(x.entry.file, x.bytes);
put('index.json', indexBytes);
const keep = new Set(['index.json', ...templates.map((x) => x.entry.file)]);
for (const f of fs.readdirSync(target).filter((f) => f.endsWith('.json') && !keep.has(f))) {
	fs.rmSync(path.join(target, f));
	touched++;
}
console.log(`public/templates/: ${templates.length} templates, ${total} bytes${touched ? `, ${touched} files written or removed` : ', unchanged'}`);

// ─── The catalog pages ──────────────────────────────────────────────────────────────────────
const WORDS = {
	en: {
		categories: {
			access: 'Access', ssh: 'SSH', 'exit-nodes': 'Exit nodes', subnets: 'Subnets', taildrive: 'Taildrive',
			'remote-management': 'Remote management', relays: 'Relays', dns: 'DNS', funnel: 'Funnel',
			k8s: 'Kubernetes', guests: 'Guests', baseline: 'Basics',
		},
		headscale: { full: 'Headscale: yes', partial: 'Headscale: partly', none: 'Not on Headscale' },
		version: (v) => `version ${v}`,
		minApp: (v) => `needs TailSocks ${v}`,
		field: 'Field',
		takes: 'Takes',
		about: 'About it',
		noFields: 'No fields: it writes the same into every policy.',
		required: 'required',
		several: 'several',
		byDefault: 'default',
		on: 'on',
		off: 'off',
		kinds: {
			selector: 'from the picker', user: 'a user', group: 'a group', tag: 'a tag', port: 'ports',
			host: 'an address', cidr: 'a subnet', text: 'text', bool: 'a switch', choice: 'one of',
		},
		allow: {
			any: 'anything', autogroup: 'autogroups', group: 'groups', tag: 'tags', user: 'users',
			'user-domain': 'users of a domain', host: 'hosts', ip: 'addresses', cidr: 'subnets', ipset: 'IP sets',
			service: 'services', posture: 'postures',
		},
		docs: 'Read more',
		file: (name) => `The template file, <code>${name}</code>`,
	},
	ru: {
		categories: {
			access: 'Доступ', ssh: 'SSH', 'exit-nodes': 'Exit-ноды', subnets: 'Подсети', taildrive: 'Taildrive',
			'remote-management': 'Удалённое управление', relays: 'Реле', dns: 'DNS', funnel: 'Funnel',
			k8s: 'Kubernetes', guests: 'Гости', baseline: 'Основа',
		},
		headscale: { full: 'Headscale: да', partial: 'Headscale: не всё', none: 'Не для Headscale' },
		version: (v) => `версия ${v}`,
		minApp: (v) => `нужен TailSocks ${v}`,
		field: 'Поле',
		takes: 'Что принимает',
		about: 'Пояснение',
		noFields: 'Полей нет: в любую политику пишет одно и то же.',
		required: 'обязательное',
		several: 'можно несколько',
		byDefault: 'по умолчанию',
		on: 'включено',
		off: 'выключено',
		kinds: {
			selector: 'из списка', user: 'пользователь', group: 'группа', tag: 'тег', port: 'порты',
			host: 'адрес', cidr: 'подсеть', text: 'текст', bool: 'переключатель', choice: 'одно из',
		},
		allow: {
			any: 'что угодно', autogroup: 'автогруппы', group: 'группы', tag: 'теги', user: 'пользователи',
			'user-domain': 'пользователи домена', host: 'хосты', ip: 'адреса', cidr: 'подсети', ipset: 'наборы IP',
			service: 'сервисы', posture: 'posture',
		},
		docs: 'Подробнее',
		file: (name) => `Файл шаблона, <code>${name}</code>`,
	},
};

const PAGES = { en: 'src/content/docs/templates.md', ru: 'src/content/docs/ru/templates.md' };
const START = '<!-- templates:start -->';
const END = '<!-- templates:end -->';

/** Text in [lang], else in English. */
const pick = (text, lang) => (text ? (text[lang] ?? text.en ?? '') : '');

/** A command-line flag in a template's words: `--accept-routes`, `--advertise-routes=192.168.1.0/24`. */
const FLAG = /--[a-z][a-z0-9-]*(?:=[A-Za-z0-9./:_-]*[A-Za-z0-9/_-])?/g;

/**
 * A template's own words as Markdown: nothing in them becomes markup, and a flag becomes code,
 * which also keeps the site's typography from turning its `--` into a dash.
 */
const prose = (s) => {
	let out = '';
	let at = 0;
	for (const m of s.matchAll(FLAG)) {
		out += s.slice(at, m.index).replace(/([\\`*_[\]<>|])/g, '\\$1') + `\`${m[0]}\``;
		at = m.index + m[0].length;
	}
	return out + s.slice(at).replace(/([\\`*_[\]<>|])/g, '\\$1');
};

/** A value as inline code, safe in a table cell. */
const code = (s) => `\`${String(s).replace(/\|/g, '\\|')}\``;

/** Where Headscale stands, in a few words, and the template's note on it when it is not all there. */
function headscale(t, w, lang) {
	const level = t.headscale?.supported ?? true;
	const key = [true, 'full', 'yes'].includes(level) ? 'full' : level === 'partial' ? 'partial' : 'none';
	const note = key === 'full' ? '' : pick(t.headscale?.note, lang);
	return { short: w.headscale[key], note: note ? `${w.headscale[key]}. ${prose(note)}` : null };
}

const optionLabel = (p, value, lang) => prose(pick(p.options?.find((o) => o.value === value)?.label, lang) || value);

/** What a field takes, and its default. */
function takes(p, w, lang) {
	let what = w.kinds[p.kind] ?? p.kind;
	if (p.kind === 'choice') what = `${what}: ${p.options.map((o) => prose(pick(o.label, lang))).join(', ')}`;
	if (p.kind === 'selector' && p.allow?.length) what = `${what}: ${p.allow.map((a) => w.allow[a] ?? a).join(', ')}`;
	if (p.default === undefined) return what;
	const values = Array.isArray(p.default) ? p.default : [p.default];
	const shown =
		p.kind === 'bool' ? (p.default ? w.on : w.off)
		: p.kind === 'choice' ? values.map((v) => optionLabel(p, v, lang)).join(', ')
		: values.map(code).join(', ');
	return `${what}; ${w.byDefault} ${shown}`;
}

function fields(t, w, lang) {
	if (!t.params?.length) return [w.noFields];
	const rows = t.params.map((p) => {
		const marks = [p.required && p.kind !== 'bool' ? w.required : null, p.multi ? w.several : null].filter(Boolean);
		const name = `**${prose(pick(p.label, lang))}**${marks.length ? ` (${marks.join(', ')})` : ''}`;
		const help = prose(pick(p.help, lang)).replace(/\s*\n+\s*/g, ' ');
		return `| ${name} | ${takes(p, w, lang)} | ${help} |`;
	});
	return [`| ${w.field} | ${w.takes} | ${w.about} |`, '|---|---|---|', ...rows];
}

function section(x, lang) {
	const { t, entry, bytes } = x;
	const w = WORDS[lang];
	const out = [`### ${prose(pick(t.title, lang))}`, ''];
	const hs = headscale(t, w, lang);
	const meta = [code(t.id), w.categories[t.category] ?? t.category, hs.short, w.version(t.version)];
	if (t.minApp !== undefined) meta.push(w.minApp(t.minApp));
	out.push(meta.join(' · '), '');
	for (const part of pick(t.description, lang).split(/\n\s*\n/)) out.push(prose(part.trim()), '');
	if (hs.note) out.push(hs.note, '');
	out.push(...fields(t, w, lang), '');
	if (t.docs?.length) out.push(`${w.docs}: ${t.docs.map((d) => `[${prose(pick(d.title, lang))}](${d.url})`).join(', ')}.`, '');
	out.push('<details>', `<summary>${w.file(entry.file)}</summary>`, '', '```json', bytes.toString('utf8').trimEnd(), '```', '', '</details>', '');
	return out.join('\n');
}

for (const lang of ['en', 'ru']) {
	const file = path.join(site, PAGES[lang]);
	const page = fs.readFileSync(file, 'utf8');
	const a = page.indexOf(START);
	const b = page.indexOf(END);
	if (a < 0 || b < a) fail(`${PAGES[lang]}: the markers ${START} and ${END} are missing`);
	const body = templates.map((x) => section(x, lang)).join('\n').trimEnd();
	const next = `${page.slice(0, a + START.length)}\n\n${body}\n\n${page.slice(b)}`;
	if (next !== page) fs.writeFileSync(file, next);
	console.log(`${PAGES[lang]}: ${templates.length} templates${next === page ? ', unchanged' : ''}`);
}
