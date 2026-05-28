import { defineConfig } from 'vitepress';
import { sidebarCn } from './sidebars/cn';
import { sidebarEn } from './sidebars/en';

const GITHUB_URL = 'https://github.com/chancetop-com/core-ai';

export default defineConfig({
  title: 'Core-AI',
  description: 'A powerful Java framework for building AI agents and multi-agent applications',
  base: '/core-ai/',
  cleanUrls: true,
  lastUpdated: true,
  ignoreDeadLinks: true,

  head: [
    ['link', { rel: 'icon', href: '/core-ai/favicon.ico' }],
    ['meta', { name: 'theme-color', content: '#3c8772' }]
  ],

  themeConfig: {
    logo: { src: '/logo.svg', alt: 'Core-AI' },
    search: { provider: 'local' },
    socialLinks: [{ icon: 'github', link: GITHUB_URL }]
  },

  locales: {
    root: {
      label: '简体中文',
      lang: 'zh-CN',
      link: '/cn/',
      themeConfig: {
        nav: [
          { text: '入门', link: '/cn/overview' },
          { text: '教程', link: '/cn/tutorial-basic-agent' },
          { text: '设计文档', link: '/cn/design-server-architecture' },
          { text: 'API 参考', link: '/api/index.html', target: '_blank' }
        ],
        sidebar: sidebarCn,
        outline: { label: '本页目录', level: [2, 3] },
        docFooter: { prev: '上一页', next: '下一页' },
        lastUpdatedText: '最后更新',
        darkModeSwitchLabel: '主题',
        sidebarMenuLabel: '菜单',
        returnToTopLabel: '回到顶部',
        editLink: {
          pattern: `${GITHUB_URL}/edit/master/docs/:path`,
          text: '在 GitHub 上编辑此页'
        }
      }
    },
    en: {
      label: 'English',
      lang: 'en-US',
      link: '/en/',
      themeConfig: {
        nav: [
          { text: 'Getting Started', link: '/en/overview' },
          { text: 'Tutorials', link: '/en/tutorial-basic-agent' },
          { text: 'Design', link: '/en/design-server-architecture' },
          { text: 'API Reference', link: '/api/index.html', target: '_blank' }
        ],
        sidebar: sidebarEn,
        editLink: {
          pattern: `${GITHUB_URL}/edit/master/docs/:path`,
          text: 'Edit this page on GitHub'
        }
      }
    }
  }
});
