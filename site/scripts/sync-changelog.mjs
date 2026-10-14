#!/usr/bin/env node
/*
 * Regenerates the site's two changelog pages from a checkout of the app (the main branch):
 *
 *     cd site && node scripts/sync-changelog.mjs /path/to/tailsocks
 *
 *     CHANGELOG.md     →  src/content/docs/changelog.md
 *     CHANGELOG_RU.md  →  src/content/docs/ru/changelog.md
 *
 * The text is taken as it is, newest first, one `##` heading per version, except that
 *   - the file's own title and preamble give way to the page's front matter and a one-line
 *     note that the app's "What's new" shows the same;
 *   - `## [Unreleased]` is left out: the site lists released versions;
 *   - a version heading `## [5.0.1] - 2026-10-14` reads `## 5.0.1 — 2026-10-14`;
 *   - every entry that mentions an easter egg is left out, with anything nested under it,
 *     and a `###` group that has nothing left goes too;
 *   - a Russian file that stops before the oldest English version ends with a pointer to the
 *     English page, where the older versions are.
 *
 * Part of the release routine: run it once CHANGELOG.md and CHANGELOG_RU.md are final, check
 * the diff, and commit the two pages on the `site` branch.
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const checkout = process.argv[2];
if (!checkout) {
	console.error('usage: node scripts/sync-changelog.mjs /path/to/tailsocks');
	process.exit(2);
}

const site = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const EGG = /easter[\s-]*egg|пасхалк/i;
const VERSION = /^##\s+\[([^\]]+)\](?:\s*-\s*(.*?))?\s*$/;

const PAGES = {
	en: {
		source: 'CHANGELOG.md',
		target: 'src/content/docs/changelog.md',
		front: [
			'title: Changelog',
			'description: What changed in every TailSocks release, newest first.',
			'sidebar:',
			'  label: All versions',
			'editUrl: https://github.com/bropines/tailsocks/edit/main/CHANGELOG.md',
			'tableOfContents:',
			'  maxHeadingLevel: 2',
		],
		note: "The app shows the same list: once after each update, and any time under **What's new** in **About TailSocks** (the ⓘ on the main screen).",
		older: null,
	},
	ru: {
		source: 'CHANGELOG_RU.md',
		target: 'src/content/docs/ru/changelog.md',
		front: [
			'title: Список изменений',
			'description: Что менялось в каждой версии TailSocks, от новых к старым.',
			'sidebar:',
			'  label: Все версии',
			'editUrl: https://github.com/bropines/tailsocks/edit/main/CHANGELOG_RU.md',
			'tableOfContents:',
			'  maxHeadingLevel: 2',
		],
		note: 'То же самое показывает приложение: один раз после каждого обновления и в любое время — **Что нового** в окне **О TailSocks** (ⓘ на главном экране).',
		older: (version) =>
			`Версии до ${version} описаны только по-английски — на [странице Changelog](/tailsocks/changelog/).`,
	},
};

/** The versions of a changelog and their text, in file order, with the filters applied. */
function versions(markdown) {
	const lines = markdown.replace(/\r\n/g, '\n').split('\n');
	const out = [];
	let current = null;
	let dropped = -1; // the indent of an entry being left out; its deeper lines go with it
	for (let i = 0; i < lines.length; i++) {
		const line = lines[i];
		const heading = line.match(VERSION);
		if (heading) {
			current = { version: heading[1].trim(), date: (heading[2] ?? '').trim(), lines: [] };
			out.push(current);
			dropped = -1;
			continue;
		}
		if (!current) continue; // the file's title and preamble
		const indent = indentOf(line);
		if (dropped >= 0) {
			if (line.trim() && indent > dropped) continue;
			dropped = -1;
		}
		if (/^\s*[-*+]\s/.test(line) && EGG.test(entryText(lines, i, indent))) {
			dropped = indent;
			continue;
		}
		current.lines.push(line);
	}
	return out
		.filter((v) => !/^unreleased$/i.test(v.version))
		.map((v) => ({ ...v, lines: dropEmptyGroups(v.lines) }));
}

const indentOf = (line) => line.match(/^\s*/)[0].replace(/\t/g, '  ').length;

/** A bullet's own text: its line and the wrapped lines under it, not the bullets nested in it. */
function entryText(lines, at, indent) {
	let text = lines[at];
	for (let i = at + 1; i < lines.length; i++) {
		const l = lines[i];
		if (!l.trim() || /^\s*[-*+]\s/.test(l) || indentOf(l) <= indent) break;
		text += ` ${l.trim()}`;
	}
	return text;
}

/** Removes a `###` group that has no entry left under it. */
function dropEmptyGroups(lines) {
	const out = [];
	for (let i = 0; i < lines.length; i++) {
		if (/^###\s/.test(lines[i])) {
			let j = i + 1;
			while (j < lines.length && !/^#{2,3}\s/.test(lines[j]) && !lines[j].trim()) j++;
			if (j >= lines.length || /^#{2,3}\s/.test(lines[j])) {
				i = j - 1;
				continue;
			}
		}
		out.push(lines[i]);
	}
	return out;
}

function render(lang, list, oldestEnglish) {
	const page = PAGES[lang];
	const body = [];
	for (const v of list) {
		body.push(`## ${v.version}${v.date ? ` — ${v.date}` : ''}`);
		body.push(...v.lines);
	}
	const text = body.join('\n').replace(/\n{3,}/g, '\n\n').trim();
	const last = list.at(-1)?.version;
	const tail = page.older && last && last !== oldestEnglish ? `\n\n---\n\n${page.older(last)}` : '';
	return `---\n${page.front.join('\n')}\n---\n\n${page.note}\n\n${text}${tail}\n`;
}

const read = (name) => fs.readFileSync(path.join(checkout, name), 'utf8');
const english = versions(read(PAGES.en.source));
const oldestEnglish = english.at(-1)?.version;
for (const lang of ['en', 'ru']) {
	const page = PAGES[lang];
	const list = lang === 'en' ? english : versions(read(page.source));
	if (!list.length) throw new Error(`${page.source}: no versions found`);
	const file = path.join(site, page.target);
	const next = render(lang, list, oldestEnglish);
	const prev = fs.existsSync(file) ? fs.readFileSync(file, 'utf8') : null;
	if (next !== prev) fs.writeFileSync(file, next);
	console.log(`${page.target}: ${list.length} versions, ${list[0].version} to ${list.at(-1).version}${next === prev ? ', unchanged' : ''}`);
}
