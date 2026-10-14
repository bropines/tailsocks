// @ts-check
import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';

const repo = 'https://github.com/bropines/tailsocks';

// The site lives on its own orphan branch, `site`, with this folder in it.
export default defineConfig({
	site: 'https://bropines.github.io',
	base: '/tailsocks',
	integrations: [
		starlight({
			title: 'TailSocks',
			description: 'An unofficial Tailscale client for Android: a proxy, a VPN, or root routing.',
			logo: { src: './src/assets/logo.svg', alt: 'TailSocks' },
			favicon: '/favicon.svg',
			defaultLocale: 'root',
			locales: {
				root: { label: 'English', lang: 'en' },
				ru: { label: 'Русский', lang: 'ru' },
			},
			social: [{ icon: 'github', label: 'GitHub', href: repo }],
			editLink: { baseUrl: `${repo}/edit/site/site/` },
			customCss: ['./src/styles/custom.css'],
			lastUpdated: false,
			sidebar: [
				{
					label: 'Start',
					translations: { ru: 'Начало' },
					items: [{ slug: 'getting-started' }, { slug: 'connection-modes' }],
				},
				{
					label: 'Features',
					translations: { ru: 'Возможности' },
					items: [
						{ slug: 'files' },
						{ slug: 'censorship' },
						{ slug: 'automation' },
						{ slug: 'settings-links' },
					],
				},
				{
					label: 'Experimental',
					translations: { ru: 'Экспериментальное' },
					items: [{ slug: 'remote-management' }, { slug: 'android-tv' }],
				},
				{
					label: 'Help',
					translations: { ru: 'Помощь' },
					items: [
						{ slug: 'troubleshooting' },
						{
							label: 'For developers',
							translations: { ru: 'Разработчикам' },
							items: [
								{ slug: 'developers', label: 'Overview', translations: { ru: 'Обзор' } },
								{ slug: 'developers/building' },
								{ slug: 'developers/architecture' },
								{ slug: 'developers/contributing' },
								{ slug: 'developers/translating' },
								{ slug: 'developers/releasing' },
							],
						},
					],
				},
			],
		}),
	],
});
