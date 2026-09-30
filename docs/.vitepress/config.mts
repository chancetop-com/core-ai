import { defineConfig } from 'vitepress';
import { resolve } from 'path';
import { fileURLToPath } from 'url';
import { sidebarCn } from './sidebars/cn';
import { sidebarEn } from './sidebars/en';
import { alternateFor } from './language';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const GITHUB_URL = 'https://github.com/chancetop-com/core-ai';

export default defineConfig({
  title: 'core-ai',
  description: 'Core AI Server, CLI and Java framework guides, tutorials and source references',
  base: '/core-ai/',
  cleanUrls: true,
  lastUpdated: true,
  ignoreDeadLinks: false,
  transformPageData(pageData) {
    pageData.frontmatter.alternate = alternateFor(pageData.relativePath, resolve(__dirname, '..'));
    if (/(?:^|\/)(?:design[-_]|DESIGN|experiment[-_]|.*(?:-plan|TODOS|llm-wiki)\.)/.test(pageData.relativePath)) {
      pageData.frontmatter.historical = true;
      pageData.frontmatter.search = false;
    }
  },
  markdown: {
    config(md) {
      const open = md.renderer.rules.table_open;
      const close = md.renderer.rules.table_close;
      md.renderer.rules.table_open = (...args) => '<div class="doc-table-scroll" tabindex="0" role="region" aria-label="表格，可横向滚动 / Scrollable table">' + (open?.(...args) || '<table>');
      md.renderer.rules.table_close = (...args) => (close?.(...args) || '</table>') + '</div>';
    }
  },

  // Serve /assets/ at the site root so README and VitePress share one asset source
  vite: {
    publicDir: resolve(__dirname, '../../assets')
  },

  head: [
    ['link', { rel: 'icon', type: 'image/svg+xml', href: '/core-ai/core-ai-logo-v5-symbol-c-icon.svg' }],
    ['meta', { name: 'theme-color', content: '#A87967' }]
  ],

  themeConfig: {
    i18nRouting: false,
    logo: {
      light: '/core-ai-logo-v5-symbol-c-wordmark.svg',
      dark: '/core-ai-logo-v5-symbol-c-wordmark-dark.svg',
      alt: 'core-ai'
    },
    siteTitle: false,
    search: {
      provider: 'local',
      options: {
        locales: {
          root: {
            translations: {
              button: { buttonText: '搜索文档', buttonAriaLabel: '搜索文档' },
              modal: {
                displayDetails: '显示详情', resetButtonTitle: '清除搜索', backButtonTitle: '返回',
                noResultsText: '没有找到相关结果',
                footer: { selectText: '选择', selectKeyAriaLabel: '回车选择', navigateText: '切换',
                  navigateUpKeyAriaLabel: '上一项', navigateDownKeyAriaLabel: '下一项',
                  closeText: '关闭', closeKeyAriaLabel: '按 Esc 关闭' }
              }
            }
          }
        }
      }
    },
    socialLinks: [{ icon: 'github', link: GITHUB_URL }]
  },

  locales: {
    root: {
      label: '简体中文',
      lang: 'zh-CN',
      link: '/cn/',
      themeConfig: {
        nav: [
          { text: '快速开始', link: '/cn/quickstart' },
          { text: '使用指南', items: [
            { text: 'Server', link: '/cn/server' },
            { text: 'CLI', link: '/cn/cli' },
            { text: 'Java 框架', link: '/cn/framework' },
            { text: '完整手册', link: '/cn/manual/' },
            { text: '技能手册', link: '/skills/' },
            { text: '跨平台排障', link: '/cn/cli-troubleshooting' }
          ] },
          { text: '教程', link: '/cn/tutorials' },
          { text: '设计文档', link: '/cn/design-server-architecture' },
          { text: 'API 参考', link: '/cn/api' }
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
          { text: 'Quick Start', link: '/en/quickstart' },
          { text: 'Use Guides', items: [
            { text: 'Server', link: '/en/server' },
            { text: 'CLI', link: '/en/cli' },
            { text: 'Java framework', link: '/en/framework' },
            { text: 'Skills', link: '/skills/' },
            { text: 'Troubleshooting', link: '/en/cli-troubleshooting' },
            { text: 'Full manual · 中文', link: '/cn/manual/' }
          ] },
          { text: 'Tutorials', link: '/en/tutorials' },
          { text: 'Design', link: '/en/design-server-architecture' },
          { text: 'API Reference', link: '/en/api' }
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
