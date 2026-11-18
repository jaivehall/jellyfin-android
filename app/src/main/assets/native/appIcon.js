(() => {
    if (window.__finplayerIconApplied) return;
    window.__finplayerIconApplied = true;

    // Finplayer snowflake: three rotated F glyphs, enlarged for in-app display.
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1024 1024">
      <defs>
        <radialGradient id="fpBlue" gradientUnits="userSpaceOnUse" cx="512" cy="512" r="450">
          <stop offset="0" stop-color="#7CE7FF"/><stop offset="1" stop-color="#3C7BFF"/>
        </radialGradient>
        <radialGradient id="fpMint" gradientUnits="userSpaceOnUse" cx="512" cy="512" r="450">
          <stop offset="0" stop-color="#9BF6D8"/><stop offset="1" stop-color="#2ED3B5"/>
        </radialGradient>
        <radialGradient id="fpCoral" gradientUnits="userSpaceOnUse" cx="512" cy="512" r="450">
          <stop offset="0" stop-color="#FFC08A"/><stop offset="1" stop-color="#FF6F91"/>
        </radialGradient>
      </defs>
      <g fill="none" stroke-width="66" stroke-linecap="round" stroke-linejoin="round">
        <path d="M512 512 L512 170 M512 170 L752 170 M512 341 L668 341" stroke="url(#fpBlue)"/>
        <path d="M512 512 L512 170 M512 170 L752 170 M512 341 L668 341" stroke="url(#fpMint)" transform="rotate(120 512 512)"/>
        <path d="M512 512 L512 170 M512 170 L752 170 M512 341 L668 341" stroke="url(#fpCoral)" transform="rotate(240 512 512)"/>
      </g>
    </svg>`;
    const iconUri = `data:image/svg+xml;charset=utf-8,${encodeURIComponent(svg)}`;

    const style = document.createElement('style');
    style.id = 'finplayer-app-icon-style';
    style.textContent = `
      .splashLogo,
      .loginLogo,
      .pageTitleWithDefaultLogo,
      .pageTitleWithLogo,
      .sidebarLogo,
      .appLogo,
      .detailLogo,
      .cardFooterLogo {
        background-image: url("${iconUri}") !important;
        background-size: contain !important;
        background-position: center !important;
        background-repeat: no-repeat !important;
      }
      .splashLogo { background-size: 60% auto !important; }
      /* Finplayer: casting is provided by the native player controls only.
         Hide Jellyfin Web's global cast button on all webapp pages (home,
         libraries, details, ...). Native player is a separate Activity whose
         cast button lives in XML, so it is unaffected. Both aria-controls
         values cover the idle and the actively-casting button states. */
      [aria-controls="app-remote-play-menu"],
      [aria-controls="app-remote-play-active-menu"] {
        display: none !important;
      }
      /* Enlarge the sidebar/brand logo shown next to the title */
      .adminDrawerLogo { padding: 1.2em 1em !important; }
      .adminDrawerLogo img {
        height: 5.5em !important;
        max-height: none !important;
        width: auto !important;
      }
    `;
    (document.head || document.documentElement).appendChild(style);

    function applyToImages(root) {
        if (!root || !root.querySelectorAll) return;
        root.querySelectorAll('img').forEach((img) => {
            if (img.dataset.finplayerIconApplied) return;
            const src = img.getAttribute('src') || '';
            const className = typeof img.className === 'string' ? img.className : '';
            const alt = img.getAttribute('alt') || '';
            const inLogo = img.closest('.adminDrawerLogo, .sidebarLogo, .loginLogo, .appLogo, .pageTitleWithLogo');
            if (inLogo || /logo|banner|icon-transparent|touchicon|jellyfin/i.test(src)
                || /logo|banner/i.test(className)
                || /jellyfin/i.test(alt)) {
                img.src = iconUri;
                img.dataset.finplayerIconApplied = 'true';
                img.style.objectFit = 'contain';
                if (inLogo) {
                    img.style.height = '5.5em';
                    img.style.width = 'auto';
                    img.style.maxHeight = 'none';
                }
            }
        });
    }

    function replaceBrandText(root) {
        if (!root || !root.createTreeWalker) return;
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
        let node;
        const targets = [];
        while ((node = walker.nextNode())) {
            if (node.nodeValue && node.nodeValue.trim() === 'Jellyfin') targets.push(node);
        }
        targets.forEach((n) => { n.nodeValue = 'Finplayer'; });
    }

    applyToImages(document);
    replaceBrandText(document.body || document.documentElement);

    const observer = new MutationObserver((mutations) => {
        for (const mutation of mutations) {
            mutation.addedNodes.forEach((node) => {
                if (node.nodeType === 1) {
                    applyToImages(node);
                    replaceBrandText(node);
                } else if (node.nodeType === 3 && node.nodeValue && node.nodeValue.trim() === 'Jellyfin') {
                    node.nodeValue = 'Finplayer';
                }
            });
            if (mutation.type === 'characterData' && mutation.target.nodeValue
                && mutation.target.nodeValue.trim() === 'Jellyfin') {
                mutation.target.nodeValue = 'Finplayer';
            }
        }
    });
    observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
})();
