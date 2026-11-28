module.exports = {
    title: 'cordis-java 教程',
    description: '从 0 构建一个 Agent：以 cordis-java 为标本，讲清楚一个可长期运行的 agent 到底由哪些零件构成',
    base: '/cordis-java/',
    head: [
        ['link', { rel: 'icon', href: '/favicon.ico' }]
    ],
    themeConfig: {
        nav: [
            { text: '首页', link: '/' },
            { text: '课程目录', link: '/course/' },
            { text: 'GitHub', link: 'https://github.com/yangziwen/cordis-java', target: '_blank' }
        ],
        displayAllHeaders: true,
        sidebar: {
            '/course/': [
                {
                    title: '教程：从 0 构建一个 Agent',
                    path: '/course/',
                    collapsable: false,
                    sidebarDepth: 2,
                    children: [
                        ['', '00 · 课程总览'],
                        ['01-from-call-to-agent.md', '01 · 从一次模型调用到 agent'],
                        ['02-kernel-why.md', '02 · 为什么先要一个内核'],
                        ['03-thread-model.md', '03 · 主循环与扩展池'],
                        ['04-promise-and-events.md', '04 · Promise、Deferred 与事件'],
                        ['05-capability-layer.md', '05 · 能力层的形状'],
                        ['06-tool-contract.md', '06 · 工具的契约'],
                        ['07-agent-loop.md', '07 · agent loop'],
                        ['08-llm-wire.md', '08 · 模型适配层'],
                        ['09-memory.md', '09 · 记忆与会话'],
                        ['10-context-budget.md', '10 · 上下文预算'],
                        ['11-safety.md', '11 · 安全边界'],
                        ['12-serving.md', '12 · 服务化与可观测'],
                        ['13-verification.md', '13 · 验证与演进']
                    ]
                }
            ]
    },
        smoothScroll: false
    },
    plugins: [
        ['mermaidjs', { 'theme': 'default' }]
    ]
}
