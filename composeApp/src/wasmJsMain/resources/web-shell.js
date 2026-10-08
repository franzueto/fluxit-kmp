// Page shell around the Compose app: checks the browser can run Kotlin/Wasm before loading
// it, turns a failed start into a message on the splash, and keeps #app sized to the visual
// viewport (above the iOS on-screen keyboard). The app removes #splash once it has rendered.
(() => {
    "use strict";

    const splash = document.getElementById("splash");

    function showMessage(text, canRetry) {
        if (!splash.isConnected) return; // the app already rendered
        splash.querySelector(".splash-spinner").hidden = true;
        splash.querySelector(".splash-message").hidden = false;
        document.getElementById("splash-text").textContent = text;
        const retry = document.getElementById("splash-retry");
        retry.hidden = !canRetry;
        retry.onclick = () => window.location.reload();
    }

    // The smallest module using a Wasm GC struct type; Kotlin/Wasm needs GC
    // (Safari 18.2+, current Chrome, Edge and Firefox).
    function supportsWasmGc() {
        try {
            return typeof WebAssembly === "object" &&
                WebAssembly.validate(new Uint8Array([0, 97, 115, 109, 1, 0, 0, 0, 1, 5, 1, 95, 1, 120, 0]));
        } catch {
            return false;
        }
    }

    if (!supportsWasmGc()) {
        showMessage("This browser can't run FluxIt. Update to the latest Safari (iOS 18.2 or later), Chrome, Edge or Firefox.", false);
        return;
    }

    const failedToStart = () => showMessage("FluxIt couldn't start. Check your connection and try again.", true);
    // Only matters while the splash is up; afterwards the app handles its own errors.
    window.addEventListener("error", () => failedToStart());
    window.addEventListener("unhandledrejection", () => failedToStart());

    const app = document.createElement("script");
    app.src = "composeApp.js";
    app.onerror = failedToStart;
    document.head.appendChild(app);

    // --- Visual viewport ---------------------------------------------------------------------
    // iOS Safari overlays the keyboard and shrinks only the visual viewport; Chrome on Android
    // resizes the page (interactive-widget=resizes-content). Following the visual viewport
    // covers both. Compose only re-measures on window "resize", so one is dispatched after a
    // change; this listener is on visualViewport, so that cannot loop.
    //
    // Changes wait while a finger is down: on Android a tap on a button while the keyboard is
    // up can start closing the keyboard, and re-laying out mid-tap moves the button from under
    // the finger, so the tap is lost and only closes the keyboard.
    const viewport = window.visualViewport;
    if (!viewport) return;
    const root = document.documentElement;
    let last = "";
    let touches = 0;

    function fit() {
        if (touches > 0) return; // re-run when the last finger lifts
        if (viewport.scale > 1.01) return; // pinch zoom, not a keyboard
        const top = Math.max(0, Math.round(viewport.offsetTop));
        const height = Math.round(viewport.height);
        const keyboardOpen = window.innerHeight - height > 120;
        const next = `${top}/${height}/${keyboardOpen}`;
        if (next === last) return;
        last = next;
        root.style.setProperty("--app-top", `${top}px`);
        root.style.setProperty("--app-height", `${height}px`);
        root.classList.toggle("keyboard-open", keyboardOpen);
        window.dispatchEvent(new Event("resize"));
    }

    // If a touchend never arrives (its target left the DOM mid-touch), stop waiting after a
    // few seconds or when the page is hidden, so the layout cannot stay frozen.
    let touchTimeout = 0;
    const touchesEnded = () => {
        clearTimeout(touchTimeout);
        touches = 0;
        requestAnimationFrame(fit);
    };
    const trackTouches = (event) => {
        touches = event.touches.length;
        clearTimeout(touchTimeout);
        if (touches === 0) touchesEnded();
        else touchTimeout = setTimeout(touchesEnded, 3000);
    };
    document.addEventListener("visibilitychange", () => { if (touches > 0) touchesEnded(); });
    // Capture on window: Compose's canvas lives in a shadow root, and touch events are composed.
    for (const type of ["touchstart", "touchend", "touchcancel"]) {
        window.addEventListener(type, trackTouches, { capture: true, passive: true });
    }

    viewport.addEventListener("resize", fit);
    viewport.addEventListener("scroll", fit);
    fit();
})();
