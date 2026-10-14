#!/usr/bin/env node
/*
 * Writes the table of every setting into the "Settings search and links" pages, from a
 * checkout of the app (the main branch):
 *
 *     cd site && node scripts/settings-table.mjs /path/to/tailsocks
 *
 * It reads
 *   app/src/main/java/io/github/bropines/tailscaled/ui/SettingsCatalog.kt — the pages, the rows
 *     and their ids, the condition a row needs (`needs`) and the part of the app it is (`feature`);
 *   app/src/main/java/io/github/bropines/tailscaled/core/DeviceProfile.kt — which parts a TV
 *     hides, and which it keeps under Settings → Advanced;
 *   app/src/main/res/values/strings*.xml and values-ru/strings*.xml — the titles, as the app
 *     shows them in English and in Russian (English where a Russian one is missing);
 * and replaces what lies between the two marker comments
 *
 *     <!-- settings-table:start -->  …  <!-- settings-table:end -->
 *
 * in src/content/docs/settings-links.md and src/content/docs/ru/settings-links.md. Everything
 * outside the markers is the pages' own text and is left alone.
 *
 * Run it whenever SettingsCatalog.kt or a setting's title changes (at the latest at a release),
 * and commit the two pages on the `site` branch. A `needs` it has no words for stops it with an
 * error: add them to NEED_WORDS below.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const checkout = process.argv[2];
if (!checkout) {
	console.error('usage: node scripts/settings-table.mjs /path/to/tailsocks');
	process.exit(2);
}

const site = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const app = (p) => path.join(checkout, 'app/src/main', p);
const catalogKt = fs.readFileSync(app('java/io/github/bropines/tailscaled/ui/SettingsCatalog.kt'), 'utf8');
const profileKt = fs.readFileSync(app('java/io/github/bropines/tailscaled/core/DeviceProfile.kt'), 'utf8');

// ─── Strings ────────────────────────────────────────────────────────────────────────────────
function loadStrings(dir) {
	const out = new Map();
	const full = app(`res/${dir}`);
	for (const file of fs.readdirSync(full).filter((f) => /^strings.*\.xml$/.test(f)).sort()) {
		const xml = fs.readFileSync(path.join(full, file), 'utf8').replace(/<!--[\s\S]*?-->/g, '');
		for (const m of xml.matchAll(/<string\s+name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g)) out.set(m[1], m[2]);
	}
	return out;
}

/** A resource value as the app shows it: unescaped, one line, no markup. */
function plain(value) {
	return value
		.replace(/<!\[CDATA\[([\s\S]*?)\]\]>/g, '$1')
		.replace(/<[^>]+>/g, '')
		.replace(/\\n/g, ' ')
		.replace(/\\(['"@?])/g, '$1')
		.replace(/&lt;/g, '<')
		.replace(/&gt;/g, '>')
		.replace(/&quot;/g, '"')
		.replace(/&apos;/g, "'")
		.replace(/&amp;/g, '&')
		.replace(/^"(.*)"$/s, '$1')
		.replace(/\s+/g, ' ')
		.trim();
}

const strings = { en: loadStrings('values'), ru: loadStrings('values-ru') };

function text(lang, ref) {
	const name = ref.replace(/^R\.string\./, '');
	const value = strings[lang].get(name) ?? strings.en.get(name);
	if (value === undefined) throw new Error(`no string ${name}`);
	return plain(value);
}

// ─── The catalogue ──────────────────────────────────────────────────────────────────────────
/** The top-level arguments of a call: what lies between its parentheses, split at commas. */
function args(call) {
	const inner = call.slice(call.indexOf('(') + 1, call.lastIndexOf(')'));
	const out = [];
	let depth = 0;
	let cur = '';
	for (const ch of inner) {
		if (ch === '(') depth++;
		if (ch === ')') depth--;
		if (ch === ',' && depth === 0) {
			out.push(cur.trim());
			cur = '';
		} else cur += ch;
	}
	if (cur.trim()) out.push(cur.trim());
	return out;
}

function enumBody(name) {
	const start = catalogKt.indexOf(`enum class ${name}`);
	const open = catalogKt.indexOf(') {', start) + 3;
	return catalogKt.slice(open, catalogKt.indexOf('\n}', open));
}

const pages = [];
for (const m of enumBody('SettingsPage').matchAll(/^\s*([A-Z_]+)\((.*)\)[,;]?\s*$/gm)) {
	const a = args(`(${m[2]})`);
	pages.push({
		key: m[1],
		id: JSON.parse(a[0]),
		title: a[1],
		feature: a.find((x) => x.startsWith('Feature.'))?.replace('Feature.', '') ?? null,
		aside: a.includes('aside = true'),
	});
}
const pageByKey = new Map(pages.map((p) => [p.key, p]));

const needs = new Map();
for (const m of enumBody('Need').matchAll(/^\s*([A-Z_0-9]+)(?:\((.*)\))?[,;]?\s*$/gm)) {
	const a = m[2] ? args(`(${m[2]})`) : [];
	needs.set(m[1], { reason: a[0] ?? null });
}

// Local names the row calls use: `val theme = Feature.THEME_AND_ICON`, `val tun = R.string.…`.
const locals = new Map();
for (const m of catalogKt.matchAll(/^\s*val (\w+) = ((?:Feature|R\.string)\.\w+)\s*$/gm)) locals.set(m[1], m[2]);
const resolve = (v) => locals.get(v) ?? v;

const rows = [];
for (const m of catalogKt.matchAll(/^\s*row\((".*)\)\s*$/gm)) {
	const a = args(`(${m[1]})`);
	const named = Object.fromEntries(
		a.filter((x) => /^\w+ = /.test(x)).map((x) => x.split(' = ').map((s) => s.trim())),
	);
	rows.push({
		id: JSON.parse(a[0]),
		page: pageByKey.get(a[1].replace('SettingsPage.', '')),
		title: a[2],
		feature: named.feature ? resolve(named.feature).replace('Feature.', '') : null,
		aside: named.aside ? pageByKey.get(named.aside.replace('SettingsPage.', '')) : null,
		needs: named.needs ? named.needs.replace('Need.', '') : null,
	});
}
if (rows.length < 50) throw new Error(`only ${rows.length} rows found: has SettingsCatalog.kt changed shape?`);

// ─── What a TV does with each feature ───────────────────────────────────────────────────────
const tv = new Map();
{
	const start = profileKt.indexOf('DeviceClass.TV -> when (feature) {');
	if (start < 0) throw new Error('DeviceProfile.kt: the TV table is not where it was');
	let pending = [];
	for (const raw of profileKt.slice(start).split('\n').slice(1)) {
		const line = raw.replace(/\/\/.*$/, '').trim();
		if (line === '}') break;
		pending.push(...[...line.matchAll(/Feature\.([A-Z_]+)/g)].map((x) => x[1]));
		const verdict = line.match(/->\s*(?:Availability\.(\w+)|if\b)/);
		if (!verdict) continue;
		for (const f of pending) tv.set(f, verdict[1] ?? 'DEPENDS');
		pending = [];
	}
}

// ─── Words ──────────────────────────────────────────────────────────────────────────────────
/** The needs the app gives no reason for, because nothing in Settings changes them. */
const NEED_WORDS = {
	ANDROID_12: { en: 'Android 12 and newer', ru: 'Android 12 и новее' },
	SELF_UPDATE: {
		en: 'Not when F-Droid or another store installed the app',
		ru: 'Нет, если приложение поставлено из F-Droid или другого магазина',
	},
};

const WORDS = {
	en: {
		section: 'Setting',
		link: 'Link',
		advanced: 'R.string.tv_settings_advanced',
		tvHidden: 'Not on a TV',
		tvDepends: 'On a TV, only where the system has it',
		tvUnder: (where) => `On a TV: under ${where}`,
		pageOnTv: (advanced) => `On a TV this page is under ${advanced}.`,
		asidePage: (advanced, home) =>
			`A page of its own only on a TV, under ${advanced}. Elsewhere its rows are on ${home}, and this link opens that.`,
	},
	ru: {
		section: 'Настройка',
		link: 'Ссылка',
		advanced: 'R.string.tv_settings_advanced',
		tvHidden: 'Нет на телевизоре',
		tvDepends: 'На телевизоре — только если это есть в системе',
		tvUnder: (where) => `На телевизоре: ${where}`,
		pageOnTv: (advanced) => `На телевизоре эта страница — в разделе «${advanced}».`,
		asidePage: (advanced, home) =>
			`Отдельная страница есть только на телевизоре, в разделе «${advanced}». На других устройствах её строки — на странице «${home}», и ссылка откроет её.`,
	},
};

/** Text for a Markdown table cell. */
const cell = (s) => s.replace(/\|/g, '\\|').replace(/</g, '&lt;').replace(/>/g, '&gt;');

function notes(lang, row) {
	const w = WORDS[lang];
	const out = [];
	if (row.needs) {
		const need = needs.get(row.needs);
		if (!need) throw new Error(`row ${row.id}: unknown need ${row.needs}`);
		if (need.reason) out.push(text(lang, need.reason));
		else if (NEED_WORDS[row.needs]) out.push(NEED_WORDS[row.needs][lang]);
		else throw new Error(`no words for Need.${row.needs}: add them to NEED_WORDS`);
	}
	if (row.feature) {
		const onTv = tv.get(row.feature);
		if (!onTv) throw new Error(`Feature.${row.feature} is not in DeviceProfile's TV table`);
		const advanced = text(lang, w.advanced);
		if (onTv === 'HIDDEN') out.push(w.tvHidden);
		else if (onTv === 'DEPENDS') out.push(w.tvDepends);
		else if (onTv === 'ADVANCED' && row.aside && row.aside.key !== 'ADVANCED')
			out.push(w.tvUnder(`${advanced} → ${text(lang, row.aside.title)}`));
		else if (onTv === 'ADVANCED' && row.aside) out.push(w.tvUnder(advanced));
	}
	return out;
}

function table(lang) {
	const w = WORDS[lang];
	const advanced = text(lang, w.advanced);
	const out = [];
	for (const page of pages) {
		out.push(`### ${text(lang, page.title)}`, '');
		out.push(`\`tailsocks://settings/${page.id}\``, '');
		if (page.aside) {
			const home = rows.find((r) => r.aside === page)?.page;
			out.push(w.asidePage(advanced, home ? text(lang, home.title) : '?'), '');
		} else if (page.feature && tv.get(page.feature) === 'ADVANCED') {
			out.push(w.pageOnTv(advanced), '');
		}
		const own = rows.filter((r) => r.page === page);
		if (!own.length) continue;
		out.push(`| ${w.section} | ${w.link} |`, '|---|---|');
		for (const row of own) {
			const n = notes(lang, row);
			const title = cell(text(lang, row.title)) + (n.length ? `<br><small>${cell(n.join(' · '))}</small>` : '');
			out.push(`| ${title} | \`tailsocks://settings/${page.id}/${row.id}\` |`);
		}
		out.push('');
	}
	return out.join('\n').trimEnd();
}

const START = '<!-- settings-table:start -->';
const END = '<!-- settings-table:end -->';
for (const [lang, rel] of [
	['en', 'src/content/docs/settings-links.md'],
	['ru', 'src/content/docs/ru/settings-links.md'],
]) {
	const file = path.join(site, rel);
	const page = fs.readFileSync(file, 'utf8');
	const a = page.indexOf(START);
	const b = page.indexOf(END);
	if (a < 0 || b < a) throw new Error(`${rel}: the ${START} … ${END} markers are missing`);
	const next = `${page.slice(0, a + START.length)}\n\n${table(lang)}\n\n${page.slice(b)}`;
	if (next !== page) fs.writeFileSync(file, next);
	console.log(`${rel}: ${rows.length} rows on ${pages.length} pages${next === page ? ', unchanged' : ''}`);
}
