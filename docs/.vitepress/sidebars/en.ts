import type { DefaultTheme } from 'vitepress';

export const sidebarEn: DefaultTheme.SidebarItem[] = [
  {
    text: 'Getting Started',
    collapsed: false,
    items: [
      { text: 'Overview', link: '/en/overview' },
      { text: 'Quick Start', link: '/en/quickstart' },
      { text: 'Tutorials Index', link: '/en/tutorials' }
    ]
  },
  {
    text: 'Tutorials',
    collapsed: false,
    items: [
      { text: 'Basic Agent', link: '/en/tutorial-basic-agent' },
      { text: 'Tool Calling', link: '/en/tutorial-tool-calling' },
      { text: 'Flow Orchestration', link: '/en/tutorial-flow' },
      { text: 'Memory System', link: '/en/tutorial-memory' },
      { text: 'RAG', link: '/en/tutorial-rag' },
      { text: 'Skills', link: '/en/tutorial-skills' },
      { text: 'Compression', link: '/en/tutorial-compression' },
      { text: 'Architecture', link: '/en/tutorial-architecture' }
    ]
  },
  {
    text: 'Architecture & Design',
    collapsed: true,
    items: [
      { text: 'Server Architecture', link: '/en/design-server-architecture' },
      { text: 'Client/Server', link: '/en/design-client-server-architecture' },
      { text: 'A2A Protocol', link: '/en/design-a2a-protocol' },
      { text: 'Agent Platform', link: '/en/design-agent-platform-architecture' },
      { text: 'Progressive Tool Disclosure', link: '/en/design-progressive-tool-disclosure' },
      { text: 'Native Image', link: '/en/design-native-image' }
    ]
  },
  {
    text: 'Design Phases',
    collapsed: true,
    items: [
      { text: 'P0', link: '/en/DESIGN_P0' },
      { text: 'P1', link: '/en/DESIGN_P1' },
      { text: 'P2', link: '/en/DESIGN_P2' },
      { text: 'P3', link: '/en/DESIGN_P3' }
    ]
  },
  {
    text: 'References',
    collapsed: true,
    items: [
      { text: 'LLM Wiki', link: '/en/llm-wiki' },
      { text: 'Skill Placement Experiment', link: '/en/experiment-skill-placement' }
    ]
  }
];
