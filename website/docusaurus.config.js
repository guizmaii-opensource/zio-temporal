const siteConfig = {
  title: 'ZIO Temporal',
  tagline: 'Build invincible apps with ZIO and Temporal',
  url: 'https://guizmaii-opensource.github.io',
  baseUrl: '/zio-temporal/',
  organizationName: 'guizmaii-opensource',
  projectName: 'zio-temporal',
  favicon: 'img/favicon/favicon.ico',
  trailingSlash: false,
  presets: [
    [
      '@docusaurus/preset-classic',
      {
        docs: {
          path: '../docs/target/mdoc',
          sidebarPath: require.resolve('./sidebars.js'),
        },
        // theme: {
        //   customCss: [require.resolve('./src/css/custom.css')],
        // },
      },
    ],
  ],
  themes: ['@docusaurus/theme-mermaid'],
  themeConfig: {
    prism: {
      theme: require('prism-react-renderer').themes.nightOwl,
      additionalLanguages: [
        'java',
        'scala',
        'protobuf',
        'bash'
      ],
    },
    metadata: [
      {name: "author", content: "guizmaii-opensource"},
      {name: 'twitter:card', content: 'summary'}
    ],
    announcementBar: {
      id: 'support_ukraine',
      content:
        'Support Ukraine 🇺🇦 <a target="_blank" rel="noopener noreferrer" \
          href="http://u24.gov.ua/"> Help Provide Aid to Ukraine</a>.',
      backgroundColor: '#20232a',
      textColor: '#fff',
      isCloseable: false,
    },
    navbar: {
      title: 'ZIO Temporal',
      // logo: {
      //   alt: 'Create React App Logo',
      //   src: 'img/logo.svg',
      // },
      items: [
        { to: 'docs/core/overview', label: 'Documentation', position: 'right' },
        {
           type: 'html',
           value: '<a class="navbar__item navbar__link" rel="nofollow" href="/zio-temporal/api/zio/temporal">API Docs</a>',
           position: 'right'
        },
        {
          href: 'https://github.com/guizmaii-opensource/zio-temporal',
          label: 'GitHub',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Info',
          items: [
            {
              label: 'Contribution',
              to: 'docs/contribution'
            },
            {
              label: 'FAQ',
              to: 'docs/FAQ'
            }
          ],
        },
        {
          title: 'Community & Contacts',
          items: [
            {
              label: 'GitHub Issues',
              href: 'https://github.com/guizmaii-opensource/zio-temporal/issues'
            }
          ]
        }
      ],
      copyright: `Copyright © ${new Date().getFullYear()} zio-temporal contributors. Forked from <a href="https://github.com/vitaliihonta/zio-temporal">vitaliihonta/zio-temporal</a>.`,
    }
  },
  markdown: {
      mermaid: true,
  }
};

module.exports = siteConfig;
