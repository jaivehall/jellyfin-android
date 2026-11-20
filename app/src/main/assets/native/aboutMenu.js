(() => {
    'use strict';
    if (window.__finplayerAboutApplied) return;
    window.__finplayerAboutApplied = true;

    // ---------------------------------------------------------------
    // Brand information is provided by the native bridge at call time
    // (reading lazily keeps working even if the bridge is not ready yet
    // when this script is injected).
    //   localizedAppName -> R.string.app_name (Finplayer / 飞影视界 / 飛影視界)
    //   appVersion        -> BuildConfig.VERSION_NAME
    // ---------------------------------------------------------------
    const LOGO_URL = '/native/web_icon_transparent.png';
    const DIALOG_ID = 'finplayer-about-dialog';
    const STYLE_ID = 'finplayer-about-style';
    const ABOUT_FLAG = 'data-finplayer-about';

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

    // Labels follow the system locale, derived from the localized app name
    // (same source as the launcher name) so no extra locale plumbing is needed.
    function labels(name) {
        const traditional = /[\u98DB\u8996\u81FA\u7063\u9AD4]/.test(name); // 飛 視 臺 灣 體
        const chinese = /[\u4e00-\u9fff]/.test(name);
        if (traditional) return { about: '關於', version: '版本', close: '關閉' };
        if (chinese) return { about: '关于', version: '版本', close: '关闭' };
        return { about: 'About', version: 'Version', close: 'Close' };
    }

    // ---------------------------------------------------------------
    // "About" dialog
    // ---------------------------------------------------------------
    function injectStyle() {
        if (document.getElementById(STYLE_ID)) return;
        const style = document.createElement('style');
        style.id = STYLE_ID;
        style.textContent = `
          #${DIALOG_ID} {
            position: fixed; inset: 0; z-index: 2147483000;
            display: flex; align-items: center; justify-content: center;
            background: rgba(0, 0, 0, .72);
            -webkit-tap-highlight-color: transparent;
          }
          #${DIALOG_ID} .fp-card {
            width: min(86vw, 340px); box-sizing: border-box;
            background: #1e2226; color: #fff;
            border-radius: 22px; padding: 30px 26px 18px;
            text-align: center; box-shadow: 0 18px 60px rgba(0, 0, 0, .6);
            font-family: Roboto, "Noto Sans SC", "PingFang SC", "Microsoft YaHei", sans-serif;
            animation: fp-pop .18s ease-out;
          }
          @keyframes fp-pop {
            from { transform: scale(.9); opacity: 0; }
            to   { transform: scale(1);  opacity: 1; }
          }
          #${DIALOG_ID} .fp-logo {
            width: 96px; height: 96px; object-fit: contain;
            display: block; margin: 0 auto 18px;
          }
          #${DIALOG_ID} .fp-name {
            font-size: 22px; font-weight: 600; letter-spacing: .3px;
            margin: 0 0 8px; line-height: 1.3;
          }
          #${DIALOG_ID} .fp-version {
            font-size: 14px; color: rgba(255, 255, 255, .62); margin: 0;
          }
          #${DIALOG_ID} .fp-close {
            margin-top: 24px; width: 100%;
            background: transparent; border: 0; color: #00E0A4;
            font-size: 15px; font-weight: 600; letter-spacing: .5px;
            padding: 12px; border-radius: 12px; cursor: pointer;
            font-family: inherit;
          }
          #${DIALOG_ID} .fp-close:active { background: rgba(0, 224, 164, .12); }
        `;
        (document.head || document.documentElement).appendChild(style);
    }

    function onEsc(e) {
        if (e.key === 'Escape') {
            e.stopPropagation();
            closeDialog();
        }
    }

    function closeDialog() {
        const dialog = document.getElementById(DIALOG_ID);
        if (dialog) dialog.remove();
        document.removeEventListener('keydown', onEsc, true);
    }

    function showDialog() {
        injectStyle();
        closeDialog();

        const brand = readBrand();
        const L = labels(brand.name);

        const overlay = document.createElement('div');
        overlay.id = DIALOG_ID;
        overlay.setAttribute('role', 'dialog');
        overlay.setAttribute('aria-modal', 'true');
        overlay.setAttribute('aria-label', L.about);

        const card = document.createElement('div');
        card.className = 'fp-card';

        const logo = document.createElement('img');
        logo.className = 'fp-logo';
        logo.alt = '';
        logo.src = LOGO_URL;
        card.appendChild(logo);

        const name = document.createElement('h2');
        name.className = 'fp-name';
        name.textContent = brand.name;
        card.appendChild(name);

        if (brand.version) {
            const version = document.createElement('p');
            version.className = 'fp-version';
            version.textContent = `${L.version} ${brand.version}`;
            card.appendChild(version);
        }

        const close = document.createElement('button');
        close.className = 'fp-close';
        close.type = 'button';
        close.textContent = L.close;
        close.addEventListener('click', closeDialog);
        card.appendChild(close);

        overlay.appendChild(card);
        overlay.addEventListener('click', (event) => {
            if (event.target === overlay) closeDialog();
        });
        document.body.appendChild(overlay);
        document.addEventListener('keydown', onEsc, true);
        try { close.focus(); } catch (e) { /* ignore */ }
    }

    // ---------------------------------------------------------------
    // Inject the "About" item just above the sign-out item in the
    // header user menu (React / MUI Menu + MenuItem).
    // ---------------------------------------------------------------
    const EXIT_TEXT = /(sign\s*out|log\s*out|logout|退出|登出|註銷|注销|退出登录|退出登入|退出帳號|退出账号|ログアウト|サインアウト|déconnexion|abmelden|cerrar\s*sesión|esci|uitloggen|afmelden|로그아웃)/i;

    const INFO_ICON = '<svg class="MuiSvgIcon-root MuiSvgIcon-fontSizeMedium" '
        + 'focusable="false" aria-hidden="true" viewBox="0 0 24 24" fill="currentColor" '
        + 'style="width:1.25em;height:1.25em;font-size:1.5rem">'
        + '<path d="M11 7h2v2h-2zm0 4h2v6h-2zm1-9C6.48 2 2 6.48 2 12s4.48 10 10 10 '
        + '10-4.48 10-10S17.52 2 12 2zm0 18c-4.41 0-8-3.59-8-8s3.59-8 8-8 '
        + '8 3.59 8 8-3.59 8-8 8z"></path></svg>';

    function normalize(text) {
        return (text || '').replace(/\s+/g, ' ').trim();
    }

    function isMenuItem(el) {
        return el && el.matches && el.matches('[role="menuitem"], .MuiMenuItem-root, li, a');
    }

    // Clone the sign-out MenuItem so the injected row inherits MUI styles,
    // then swap its text and icon. React handlers are not cloned (they live
    // on JS properties), so clicking it will never trigger a real sign-out.
    function buildAboutItem(template, label) {
        const item = template.cloneNode(true);
        item.removeAttribute('id');
        item.removeAttribute('href');
        item.setAttribute(ABOUT_FLAG, 'true');
        item.setAttribute('aria-label', label);

        const textEl = item.querySelector('[class*="ListItemText"] span, '
            + '.MuiListItemText-primary, .MuiTypography-root, [class*="menuItemText"]');
        if (textEl) {
            textEl.textContent = label;
        } else {
            // Fallback: replace the last non-empty text node.
            const walker = document.createTreeWalker(item, NodeFilter.SHOW_TEXT, null, false);
            let last = null;
            let node;
            while ((node = walker.nextNode())) {
                if (node.nodeValue && node.nodeValue.trim()) last = node;
            }
            if (last) last.nodeValue = label;
        }

        const iconHolder = item.querySelector('[class*="ListItemIcon"]');
        if (iconHolder) iconHolder.innerHTML = INFO_ICON;

        item.addEventListener('click', (event) => {
            event.preventDefault();
            event.stopPropagation();
            dismissMenu();
            showDialog();
        });
        return item;
    }

    function dismissMenu() {
        // Ask MUI to close its Menu (Escape + backdrop click), so the menu
        // does not stay open behind the dialog.
        try {
            document.dispatchEvent(new KeyboardEvent('keydown', {
                key: 'Escape', code: 'Escape', bubbles: true, cancelable: true
            }));
        } catch (e) { /* ignore */ }
        const backdrop = document.querySelector('.MuiBackdrop-root');
        if (backdrop) {
            try { backdrop.click(); } catch (e) { /* ignore */ }
        }
    }

    function findMenuContainers() {
        const found = new Set();
        document.querySelectorAll('[role="menu"], .MuiMenu-list, .MuiPopover-root .MuiList-root')
            .forEach((el) => found.add(el));
        document.querySelectorAll('[role="menuitem"], .MuiMenuItem-root')
            .forEach((el) => { if (el.parentElement) found.add(el.parentElement); });
        return Array.from(found);
    }

    function injectInto(container) {
        if (!container || container.querySelector('[' + ABOUT_FLAG + ']')) return;
        const items = Array.from(container.children).filter(isMenuItem);
        if (items.length < 2) return;
        const signOut = items.find((item) => EXIT_TEXT.test(normalize(item.textContent)));
        if (!signOut) return;
        const L = labels(readBrand().name);
        const about = buildAboutItem(signOut, L.about);
        container.insertBefore(about, signOut);
    }

    function scan() {
        findMenuContainers().forEach(injectInto);
    }

    // The MUI Menu is rendered into a portal (and kept mounted), so watch
    // for DOM changes: a re-render may drop our injected row and we add it
    // back. A few delayed scans cover different injection timings.
    const observer = new MutationObserver(scan);
    observer.observe(document.documentElement, { childList: true, subtree: true });
    [0, 300, 1000, 2500].forEach((delay) => setTimeout(scan, delay));

    // Re-check shortly after the header user button is tapped.
    document.addEventListener('click', (event) => {
        const target = event.target;
        if (target && target.closest && target.closest('[aria-haspopup="true"]')) {
            setTimeout(scan, 80);
            setTimeout(scan, 350);
        }
    }, true);
})();
