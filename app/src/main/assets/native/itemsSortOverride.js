(() => {
    // Patch once: guard against being injected more than once.
    if (window.__finplayerItemsSortPatched) return;
    window.__finplayerItemsSortPatched = true;

    // Normalize sorting for every <server>/Items query from the Jellyfin web app:
    //   - replace ALL sortBy values with DateCreated, ProductionYear, PremiereDate, SortName
    //   - force sortOrder to Descending
    // All other query parameters are preserved. Modern web code uses fetch(),
    // older paths use XMLHttpRequest, so patch both.
    const SORT_BY = ['DateCreated', 'ProductionYear', 'PremiereDate', 'SortName'];
    const SORT_ORDER = 'Descending';
    // Matches /Items (base-path deployments like /jellyfin/Items too), tolerates
    // a trailing slash, but NOT /Items/{id} or /Users/{id}/Items.
    const ITEMS_ENDPOINT = /\/items\/?$/i;
    const LOG_PREFIX = '[FinPlayer][ItemsSort]';

    // --- Respect a sort explicitly chosen through the UI "排序" / "Sort" button ---
    // Scope it PER LIBRARY: browsing a library sends /Items?parentId=<libraryId>,
    // so we use parentId as the library identity. Only libraries where the user
    // actually picked a sort keep their own sortBy/sortOrder; every other library
    // still gets the default rewrite. A full page reload clears both states.
    let sortMenuArmedAt = 0;
    let pendingUserSortAt = 0; // timestamp of last manual pick; next /Items claims its parentId
    const userSortedLibraries = new Set(); // parentId values the user explicitly sorted
    const SORT_MENU_TTL = 60000; // ms: how long an opened sort menu stays armed
    const PENDING_TTL = 60000; // ms: how long a pending pick may claim a library

    const isSortButton = (el) => {
        if (!el || typeof el.closest !== 'function') return false;
        // Stable across locales: MUI renders a SortByAlphaIcon inside the button.
        if (el.closest('[data-testid="SortByAlphaIcon"]')) return true;
        const btn = el.closest('button, [role="button"]');
        if (!btn) return false;
        const labels = (btn.getAttribute('title') || '') + ' ' + (btn.getAttribute('aria-label') || '');
        return /sort|排序/i.test(labels);
    };

    const getParentId = (params) => {
        for (const key of Array.from(params.keys())) {
            if (key.toLowerCase() === 'parentid') return params.get(key);
        }
        return null;
    };

    document.addEventListener('click', (ev) => {
        const t = ev.target;
        if (!t || typeof t.closest !== 'function') return;

        // Opening the sort button arms the detector.
        if (isSortButton(t)) {
            sortMenuArmedAt = Date.now();
            return;
        }
        // Ignore stale / unrelated clicks.
        if (!sortMenuArmedAt || Date.now() - sortMenuArmedAt > SORT_MENU_TTL) {
            sortMenuArmedAt = 0;
            return;
        }
        // Picking any option in the (armed) sort menu means an explicit sort.
        if (t.closest('[role="menuitem"]')) {
            pendingUserSortAt = Date.now();
            sortMenuArmedAt = 0;
            console.log(LOG_PREFIX + ' 检测到用户手动排序，将仅对当前库保留用户排序');
        }
    }, true); // capture: run before React's bubble-phase handlers

    const rewriteItemsUrl = (value) => {
        if (typeof value !== 'string') return value;
        try {
            const url = new URL(value, window.location.href);
            if (!ITEMS_ENDPOINT.test(url.pathname)) return value;
            const params = url.searchParams;
            const parentId = getParentId(params);

            // Claim a pending manual pick for the library of the next /Items request.
            // That request carries the user's freshly chosen sort params -> pass through.
            if (pendingUserSortAt) {
                const fresh = Date.now() - pendingUserSortAt <= PENDING_TTL;
                if (!fresh) {
                    pendingUserSortAt = 0; // expired: drop the pending pick
                } else if (parentId) {
                    // This is the freshly sorted library view -> claim its id.
                    pendingUserSortAt = 0;
                    userSortedLibraries.add(parentId);
                    console.log(LOG_PREFIX + ' 记录用户手动排序的库: parentId=' + parentId + '，仅该库保留用户排序');
                    return value;
                } else {
                    // Non-library /Items fired first: keep pending, pass through
                    // (it carries the user's chosen sort) and wait for a request
                    // that does carry parentId to claim the library.
                    return value;
                }
            }

            // Only libraries the user explicitly sorted keep their own ordering.
            if (parentId && userSortedLibraries.has(parentId)) {
                console.log(
                    LOG_PREFIX + ' 使用用户设置的排序（不改写）: parentId=' + parentId +
                    ' sortBy=[' + params.getAll('sortBy').join(',') + '] sortOrder=[' +
                    params.getAll('sortOrder').join(',') + ']'
                );
                return value;
            }

            // Default: force the fixed sort for every other library.
            const prevSortBy = params.getAll('sortBy').slice();
            const prevSortOrder = params.getAll('sortOrder').slice();
            // Delete every existing sortBy / sortOrder (any casing, any count).
            for (const key of Array.from(params.keys())) {
                const lower = key.toLowerCase();
                if (lower === 'sortby' || lower === 'sortorder') params.delete(key);
            }
            SORT_BY.forEach((v) => params.append('sortBy', v));
            params.append('sortOrder', SORT_ORDER);
            console.log(
                LOG_PREFIX + ' 替换了排序参数: parentId=' + (parentId || '(none)') +
                ' sortBy=[' + prevSortBy.join(',') + '] -> [' + SORT_BY.join(',') +
                '], sortOrder=[' + prevSortOrder.join(',') + '] -> [' + SORT_ORDER + ']'
            );
            const rewritten = url.toString();
            // Keep relative URLs relative.
            if (/^[a-z][a-z0-9+.-]*:\/\//i.test(value)) return rewritten;
            return url.pathname + url.search + url.hash;
        } catch (e) {
            return value;
        }
    };

    const nativeFetch = window.fetch;
    if (nativeFetch && !nativeFetch.__itemsSortPatched) {
        const patchedFetch = function (input, init) {
            if (typeof input === 'string') {
                input = rewriteItemsUrl(input);
            } else if (input instanceof URL) {
                input = new URL(rewriteItemsUrl(input.toString()), window.location.href);
            } else if (typeof Request !== 'undefined' && input instanceof Request) {
                // Request object: clone with rewritten url, preserving method/body.
                // `url` lives on Request.prototype, so use instanceof (not hasOwnProperty).
                const original = input.url;
                const rewritten = rewriteItemsUrl(original);
                if (rewritten !== original) {
                    try { input = new Request(rewritten, input); } catch (e) { /* ignore */ }
                }
            }
            return nativeFetch.call(this, input, init);
        };
        patchedFetch.__itemsSortPatched = true;
        window.fetch = patchedFetch;
    }

    if (!XMLHttpRequest.prototype.open.__itemsSortPatched) {
        const nativeOpen = XMLHttpRequest.prototype.open;
        const patchedOpen = function (method, url, ...rest) {
            if (typeof url === 'string') {
                url = rewriteItemsUrl(url);
            } else if (url instanceof URL) {
                url = new URL(rewriteItemsUrl(url.toString()), window.location.href);
            }
            return nativeOpen.call(this, method, url, ...rest);
        };
        patchedOpen.__itemsSortPatched = true;
        XMLHttpRequest.prototype.open = patchedOpen;
    }
})();
