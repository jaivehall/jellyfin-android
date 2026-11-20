(() => {
    'use strict';
    if (window.__finplayerDrawerBrandApplied) return;
    window.__finplayerDrawerBrandApplied = true;

    // ---------------------------------------------------------------
    // The drawer header (DrawerHeaderLink.tsx) renders:
    //   primary   = systemInfo?.ServerName || 'Jellyfin'
    //   secondary = getDisplayVersion(systemInfo?.Version)
    // React flips "Jellyfin" -> server name and adds the server version
    // after login / when system info resolves (and when the title is
    // tapped, which navigates to '/'). To avoid that change entirely we
    // do NOT keep rewriting React-owned text nodes. Instead we hide the
    // original ListItemText and render a stable brand block of our own.
    // ---------------------------------------------------------------
    const STYLE_ID = 'finplayer-drawer-brand-style';
    const BRAND_FLAG = 'data-finplayer-drawer-brand';
    const ROW_FLAG = 'data-finplayer-drawer-row';

    function readBrand() {
        let info = {};
        try {
            info = JSON.parse(window.NativeInterface.getDeviceInformation()) || {};
        } catch (e) { /* bridge not available yet */ }

        const name = info.localizedAppName
            || window.NativeShellLocalizedAppName
            || info.appName
            || 'Finplayer';

        let version = info.appVersion || '';
        if (!version && window.NativeShell && window.NativeShell.AppHost
            && typeof window.NativeShell.AppHost.appVersion === 'function') {
            try { version = window.NativeShell.AppHost.appVersion() || ''; } catch (e) { /* ignore */ }
        }
        return { name, version };
    }

    // Labels follow the system locale, derived from the localized app name.
    function labels(name) {
        const traditional = /[\u98DB\u8996\u81FA\u7063\u9AD4]/.test(name); // 飛 視 臺 灣 體
        const chinese = /[\u4e00-\u9fff]/.test(name);
        if (traditional) return { version: '版本' };
        if (chinese) return { version: '版本' };
        return { version: 'Version' };
    }

    function injectStyle() {
        if (document.getElementById(STYLE_ID)) return;
        const style = document.createElement('style');
        style.id = STYLE_ID;
        style.textContent = `
          [${ROW_FLAG}] > .MuiListItemText-root,
          [${ROW_FLAG}] > [class*="ListItemText-root"] {
            display: none !important;
          }
          .fp-drawer-brand {
            flex: 1 1 auto; min-width: 0;
            margin: 4px 0; padding: 0 16px 0 0;
            display: flex; flex-direction: column; justify-content: center;
            color: inherit; text-align: left;
            font-family: inherit;
          }
          .fp-drawer-name {
            font-size: 1.25rem; font-weight: 500; line-height: 1.4;
            letter-spacing: .0075em; color: inherit;
            overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
          }
          .fp-drawer-version {
            font-size: .875rem; line-height: 1.43; opacity: .7;
            margin-top: 1px; color: inherit;
            overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
          }
        `;
        (document.head || document.documentElement).appendChild(style);
    }

    function setText(el, value) {
        if (!el || el.textContent === value) return;
        // Keep the existing text node so React can still reconcile its
        // own fiber; only mutate nodeValue (never replace the node).
        let first = null;
        if (document.createTreeWalker && window.NodeFilter) {
            const walker = document.createTreeWalker(el, window.NodeFilter.SHOW_TEXT, null, false);
            let node;
            while ((node = walker.nextNode())) {
                if (!first) first = node;
                else node.nodeValue = '';
            }
        }
        if (first) first.nodeValue = value;
        else el.textContent = value;
    }

    function findHeaderRows() {
        const rows = new Set();
        const textNodes = document.querySelectorAll('.MuiListItemText-root, [class*="ListItemText-root"]');
        for (const text of textNodes) {
            const primary = text.querySelector('.MuiListItemText-primary, [class*="ListItemText-primary"]');
            if (!primary) continue;
            const row = text.closest
                ? text.closest('a, .MuiListItemButton-root, [class*="ListItemButton"], li')
                : text.parentElement;
            if (!row) continue;

            // The brand header is the only row whose ListItemIcon holds an
            // <img> (the app logo). Every other menu row uses an <svg> icon.
            const iconHolder = row.querySelector('.MuiListItemIcon-root, [class*="ListItemIcon"]');
            const hasLogoImg = !!(iconHolder && iconHolder.querySelector('img'));
            const hasSecondary = !!text.querySelector('.MuiListItemText-secondary, [class*="ListItemText-secondary"]');
            const looksBrandy = /\bJellyfin\b/i.test((primary.textContent || '').trim());

            if (hasLogoImg || hasSecondary || looksBrandy) rows.add(row);
        }
        return Array.from(rows);
    }

    function ensureBrandBlock(row, brand, L) {
        // Mark the row so the injected CSS keeps the original text hidden.
        if (!row.hasAttribute(ROW_FLAG)) row.setAttribute(ROW_FLAG, 'true');

        // Belt and braces: hide the original ListItemText inline as well,
        // so it stays hidden even if React drops the row attribute.
        const original = row.querySelector('.MuiListItemText-root, [class*="ListItemText-root"]');
        if (original && original.style && original.style.display !== 'none') {
            original.style.setProperty('display', 'none', 'important');
        }

        let block = row.querySelector('[' + BRAND_FLAG + ']');
        if (!block) {
            block = document.createElement('div');
            block.className = 'fp-drawer-brand';
            block.setAttribute(BRAND_FLAG, 'true');

            const name = document.createElement('div');
            name.className = 'fp-drawer-name';
            const version = document.createElement('div');
            version.className = 'fp-drawer-version';

            block.appendChild(name);
            block.appendChild(version);
            row.appendChild(block);
        }

        const nameEl = block.querySelector('.fp-drawer-name');
        const versionEl = block.querySelector('.fp-drawer-version');
        setText(nameEl, brand.name);

        const versionText = brand.version ? `${L.version} ${brand.version}` : '';
        setText(versionEl, versionText);
        if (versionEl) versionEl.style.display = versionText ? '' : 'none';
    }

    function apply() {
        injectStyle();
        const brand = readBrand();
        const L = labels(brand.name);
        findHeaderRows().forEach((row) => ensureBrandBlock(row, brand, L));
    }

    // Re-apply whenever React re-renders the drawer (login, system info
    // resolution, navigation). Values are compared before writing, so the
    // observer converges instead of looping forever.
    const observer = new MutationObserver(apply);
    observer.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
    [0, 300, 1000, 2500].forEach((delay) => setTimeout(apply, delay));
    // Low-frequency safety net: guarantees the brand block is restored even
    // if a full remount happens outside a watched mutation.
    setInterval(apply, 3000);
})();
