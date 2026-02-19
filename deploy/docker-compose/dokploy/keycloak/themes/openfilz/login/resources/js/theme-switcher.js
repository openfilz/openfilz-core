/**
 * OpenFilz Keycloak Theme - Theme & Locale Switcher
 *
 * Reads the `theme` query parameter (e.g., ?theme=dark) from the URL
 * and applies the corresponding data-theme attribute to the document.
 * Falls back to localStorage, then system preference.
 */
(function () {
    'use strict';

    var STORAGE_KEY = 'openfilz-theme';

    /**
     * Determine the active theme from (in priority order):
     * 1. URL query parameter `theme`
     * 2. localStorage
     * 3. OS-level prefers-color-scheme
     */
    function resolveTheme() {
        // 1. Query parameter
        var params = new URLSearchParams(window.location.search);
        var paramTheme = params.get('theme');
        if (paramTheme === 'dark' || paramTheme === 'light') {
            return paramTheme;
        }

        // 2. localStorage
        var stored = null;
        try {
            stored = localStorage.getItem(STORAGE_KEY);
        } catch (e) {
            // localStorage may be unavailable
        }
        if (stored === 'dark' || stored === 'light') {
            return stored;
        }

        // 3. System preference
        if (window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches) {
            return 'dark';
        }

        return 'light';
    }

    /**
     * Apply theme to the document and persist it.
     */
    function applyTheme(theme) {
        document.documentElement.setAttribute('data-theme', theme);
        try {
            localStorage.setItem(STORAGE_KEY, theme);
        } catch (e) {
            // Ignore storage errors
        }
    }

    /**
     * Preserve the theme parameter across Keycloak navigation links.
     * Appends `?theme=<current>` to internal links so the theme persists
     * when navigating between login, registration, forgot-password pages.
     */
    function patchLinks() {
        var currentTheme = document.documentElement.getAttribute('data-theme') || 'light';
        var links = document.querySelectorAll('a[href]');
        for (var i = 0; i < links.length; i++) {
            var link = links[i];
            var href = link.getAttribute('href');
            if (!href || href.charAt(0) === '#' || href.indexOf('javascript:') === 0) {
                continue;
            }
            // Only patch same-origin links
            try {
                var url = new URL(href, window.location.origin);
                if (url.origin === window.location.origin) {
                    url.searchParams.set('theme', currentTheme);
                    link.setAttribute('href', url.toString());
                }
            } catch (e) {
                // Skip malformed URLs
            }
        }

        // Also patch form actions
        var forms = document.querySelectorAll('form[action]');
        for (var j = 0; j < forms.length; j++) {
            var form = forms[j];
            var action = form.getAttribute('action');
            if (action) {
                try {
                    var formUrl = new URL(action, window.location.origin);
                    if (formUrl.origin === window.location.origin) {
                        formUrl.searchParams.set('theme', currentTheme);
                        form.setAttribute('action', formUrl.toString());
                    }
                } catch (e) {
                    // Skip malformed URLs
                }
            }
        }
    }

    /**
     * Set up the locale/language switcher dropdown behavior.
     */
    function initLocaleDropdown() {
        var toggle = document.getElementById('of-locale-toggle');
        var dropdown = document.getElementById('of-locale-dropdown');
        if (!toggle || !dropdown) return;

        toggle.addEventListener('click', function (e) {
            e.preventDefault();
            e.stopPropagation();
            var expanded = toggle.getAttribute('aria-expanded') === 'true';
            toggle.setAttribute('aria-expanded', String(!expanded));
            dropdown.classList.toggle('of-locale-dropdown--open');
        });

        // Close on outside click
        document.addEventListener('click', function (e) {
            if (!toggle.contains(e.target) && !dropdown.contains(e.target)) {
                toggle.setAttribute('aria-expanded', 'false');
                dropdown.classList.remove('of-locale-dropdown--open');
            }
        });

        // Close on Escape
        document.addEventListener('keydown', function (e) {
            if (e.key === 'Escape') {
                toggle.setAttribute('aria-expanded', 'false');
                dropdown.classList.remove('of-locale-dropdown--open');
                toggle.focus();
            }
        });
    }

    /**
     * Toggle password field visibility.
     */
    function initPasswordToggle() {
        var toggles = document.querySelectorAll('.of-password-toggle');
        for (var i = 0; i < toggles.length; i++) {
            (function (btn) {
                btn.addEventListener('click', function () {
                    var targetId = btn.getAttribute('data-target');
                    var input = document.getElementById(targetId);
                    if (!input) return;
                    var isPassword = input.type === 'password';
                    input.type = isPassword ? 'text' : 'password';
                    btn.setAttribute('aria-label', isPassword ? btn.getAttribute('data-label-hide') : btn.getAttribute('data-label-show'));
                    var icon = btn.querySelector('.of-eye-icon');
                    if (icon) {
                        icon.innerHTML = isPassword
                            ? '<path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" fill="none"/><line x1="1" y1="1" x2="23" y2="23" stroke="currentColor" stroke-width="2" stroke-linecap="round"/>'
                            : '<path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" fill="none"/><circle cx="12" cy="12" r="3" stroke="currentColor" stroke-width="2" fill="none"/>';
                    }
                });
            })(toggles[i]);
        }
    }

    // Initialize
    var theme = resolveTheme();
    applyTheme(theme);

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', function () {
            patchLinks();
            initLocaleDropdown();
            initPasswordToggle();
        });
    } else {
        patchLinks();
        initLocaleDropdown();
        initPasswordToggle();
    }

    // Listen for system preference changes
    if (window.matchMedia) {
        var mql = window.matchMedia('(prefers-color-scheme: dark)');
        var handler = function () {
            // Only respond if no explicit preference is stored
            var params = new URLSearchParams(window.location.search);
            if (!params.get('theme')) {
                var stored = null;
                try { stored = localStorage.getItem(STORAGE_KEY); } catch (e) {}
                if (!stored) {
                    applyTheme(mql.matches ? 'dark' : 'light');
                }
            }
        };
        if (mql.addEventListener) {
            mql.addEventListener('change', handler);
        }
    }
})();
