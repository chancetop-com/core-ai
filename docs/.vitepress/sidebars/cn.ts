import type { DefaultTheme } from 'vitepress';

export const sidebarCn: DefaultTheme.SidebarItem[] = [
  {
    text: '入门',
    collapsed: false,
    items: [
      { text: '概览', link: '/cn/overview' },
      { text: '快速开始', link: '/cn/quickstart' }
    ]
  },
  {
    text: '教程',
    collapsed: false,
    items: [
      { text: '基础 Agent', link: '/cn/tutorial-basic-agent' },
      { text: '工具调用', link: '/cn/tutorial-tool-calling' },
      { text: 'Flow 编排', link: '/cn/tutorial-flow' },
      { text: '记忆系统', link: '/cn/tutorial-memory' },
      { text: 'RAG 检索增强', link: '/cn/tutorial-rag' },
      { text: 'Skills 技能', link: '/cn/tutorial-skills' },
      { text: '上下文压缩', link: '/cn/tutorial-compression' },
      { text: '架构总览', link: '/cn/tutorial-architecture' }
    ]
  },
  {
    text: '设计文档',
    collapsed: true,
    items: [
      { text: 'Server 架构', link: '/cn/design-server-architecture' },
      { text: 'CLI Server A2A', link: '/cn/design-cli-server-a2a' },
      { text: 'A2A 远程 Agent', link: '/cn/design-a2a-remote-agent-tool' },
      { text: '记忆系统设计', link: '/cn/design-memory-system' },
      { text: '数据集报告', link: '/cn/design-dataset-report' },
      { text: 'Sandbox 沙箱架构', link: '/cn/design-sandbox-architecture' }
    ]
  },
  {
    text: 'Skills 深度',
    collapsed: true,
    items: [
      { text: '图记忆 Skill', link: '/cn/graph-memory-skill' },
      { text: '自我改进 Skill', link: '/cn/self-impronvment-skill' },
      { text: 'Skill 增强计划', link: '/cn/skill-enhancement-plan' },
      { text: 'Skill 放置实验', link: '/cn/experiment-skill-placement-cn' }
    ]
  }
];
