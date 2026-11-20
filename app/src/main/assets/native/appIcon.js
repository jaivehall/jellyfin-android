(() => {
    if (window.__finplayerIconApplied) return;
    window.__finplayerIconApplied = true;

    // Localized app name (follows system locale). Read from the native bridge
    // directly, with the value exposed by nativeshell.js as a fallback.
    let brandName = 'Finplayer';
    try {
        const info = JSON.parse(window.NativeInterface.getDeviceInformation());
        if (info && info.localizedAppName) brandName = info.localizedAppName;
    } catch (e) { /* bridge unavailable; keep fallback */ }
    if (brandName === 'Finplayer' && typeof window.NativeShellLocalizedAppName === 'string'
        && window.NativeShellLocalizedAppName.trim()) {
        brandName = window.NativeShellLocalizedAppName;
    }

    // Finplayer brand mark: Mint rounded square + white fin (matches native logo).
    const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 256 256">
      <defs>
      <clipPath id="finClip"><path d="M69,61 Q137,61 187,128 Q137,195 69,195 Q99,128 69,61 Z"/></clipPath>
      <linearGradient id="reflA" x1="0" y1="128" x2="0" y2="195" gradientUnits="userSpaceOnUse">
        <stop offset="0" stop-color="#0C1417" stop-opacity="0.55"/>
        <stop offset="1" stop-color="#0C1417" stop-opacity="0"/>
      </linearGradient>
    </defs>
      <path fill="#00E0A4" d="M76,28 H180 A48,48 0 0 1 228,76 V180 A48,48 0 0 1 180,228 H76 A48,48 0 0 1 28,180 V76 A48,48 0 0 1 76,28 Z"/>
      <g clip-path="url(#finClip)">
      <rect x="0" y="0" width="256" height="128" fill="#0C1417"/>
      <rect x="0" y="128" width="256" height="256" fill="url(#reflA)"/>
      <rect x="0" y="127" width="256" height="1.6" fill="#FFFFFF" opacity="0.5"/>
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

    function brandify(text) {
        // Replace standalone "Jellyfin" occurrences (header titles, etc.) with the localized app name.
        return typeof text === 'string' ? text.replace(/\bJellyfin\b/g, brandName) : text;
    }

    function replaceBrandText(root) {
        if (!root || !root.createTreeWalker) return;
        const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, null, false);
        let node;
        while ((node = walker.nextNode())) {
            if (node.nodeValue && node.nodeValue.indexOf('Jellyfin') !== -1) {
                const replaced = brandify(node.nodeValue);
                if (replaced !== node.nodeValue) node.nodeValue = replaced;
            }
        }
    }

    applyToImages(document);
    replaceBrandText(document.body || document.documentElement);

    const observer = new MutationObserver((mutations) => {
        for (const mutation of mutations) {
            mutation.addedNodes.forEach((node) => {
                if (node.nodeType === 1) {
                    applyToImages(node);
                    replaceBrandText(node);
                } else if (node.nodeType === 3 && node.nodeValue && node.nodeValue.indexOf('Jellyfin') !== -1) {
                    node.nodeValue = brandify(node.nodeValue);
                }
            });
            if (mutation.type === 'characterData' && mutation.target.nodeValue
                && mutation.target.nodeValue.indexOf('Jellyfin') !== -1) {
                mutation.target.nodeValue = brandify(mutation.target.nodeValue);
            }
        }
    });
    observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
})();
