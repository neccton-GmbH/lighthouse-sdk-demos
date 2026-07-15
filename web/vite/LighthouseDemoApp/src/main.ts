import { LighthouseSession, type CustomerMessage } from "@neccton-gmbh/lighthouse-web";
import { initSlot } from "./slot.js";

// ─── Dev credentials (in production your backend provides the refresh token) ─
const DEV_API_BASE = "https://v3.mentor-dev.neccton.ai/pic";
type Filter = "all" | "unread" | "active" | "archived";
type LighthouseEnv = "prod" | "stage" | "dev" | "pre-dev";

// ─── DOM ─────────────────────────────────────────────────────────────────────
const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T;

const STORAGE_KEY_TENANT = "lh_dev_tenant_key";
const STORAGE_KEY_PLAYER = "lh_dev_player_id";
const STORAGE_KEY_ENV = "lh_dev_env";
const DEFAULT_ENV: LighthouseEnv = "prod";

const credsBackdrop = $("creds-backdrop");
const inputTenantKey = $<HTMLInputElement>("input-tenant-key");
const inputPlayerId = $<HTMLInputElement>("input-player-id");
const inputEnv = $<HTMLSelectElement>("input-env");
const btnCredsSubmit = $<HTMLButtonElement>("btn-creds-submit");
const btnBell = $<HTMLButtonElement>("btn-bell");
const btnHamburger = $<HTMLButtonElement>("btn-hamburger");
const unreadBadge = $("unread-badge");
const overlayBackdrop = $("overlay-backdrop");
const msgPanel = $("msg-panel");
const panelStatus = $("panel-status");
const panelStatusIcon = $("panel-status-icon");
const panelStatusLabel = $("panel-status-label");
const btnMarkAllRead = $<HTMLButtonElement>("btn-mark-all-read");
const btnClosePanel = $("btn-close-panel");
const viewConnecting = $("view-connecting");
const viewError = $("view-error");
const viewMessages = $("view-messages");
const errorHeadline = $("error-headline");
const errorDetail = $("error-detail");
const btnRetry = $<HTMLButtonElement>("btn-retry");
const messagesLoading = $("messages-loading");
const messagesEmpty = $("messages-empty");
const messagesList = $("messages-list");
const snackbar = $("snackbar");

// ─── State ───────────────────────────────────────────────────────────────────
let session: LighthouseSession | null = null;
let currentApiKey = "";
let currentCustomerId = "";
let currentEnv: LighthouseEnv = DEFAULT_ENV;
let isConnecting = false;
let isPanelOpen = false;
let activeFilter: Filter = "active";
let currentPage = 0;
let hasNextPage = false;
let loadSeq = 0;
let snackbarTimer: ReturnType<typeof setTimeout> | undefined;

// ─── Snackbar ─────────────────────────────────────────────────────────────────
function showSnackbar(msg: string, variant: "default" | "error" | "success" = "default") {
    clearTimeout(snackbarTimer);
    snackbar.textContent = msg;
    snackbar.className = `snackbar snackbar--visible${variant !== "default" ? ` snackbar--${variant}` : ""}`;
    snackbarTimer = setTimeout(() => snackbar.classList.remove("snackbar--visible"), 3500);
}

// ─── Panel open / close ───────────────────────────────────────────────────────
function openPanel() {
    isPanelOpen = true;
    msgPanel.classList.add("is-open");
    overlayBackdrop.classList.add("is-visible");
    btnBell.setAttribute("aria-expanded", "true");
    document.addEventListener("keydown", onEscKey);
}

function closePanel() {
    isPanelOpen = false;
    msgPanel.classList.remove("is-open");
    overlayBackdrop.classList.remove("is-visible");
    btnBell.setAttribute("aria-expanded", "false");
    document.removeEventListener("keydown", onEscKey);
}

function onEscKey(e: KeyboardEvent) {
    if (e.key === "Escape") closePanel();
}

btnBell.addEventListener("click", () => isPanelOpen ? closePanel() : openPanel());
btnClosePanel.addEventListener("click", closePanel);
overlayBackdrop.addEventListener("click", closePanel);

btnHamburger.addEventListener("click", () => openCredsModal());

// ─── Panel status ─────────────────────────────────────────────────────────────
type StatusState = "connecting" | "ok" | "expired";

const STATUS_META: Record<StatusState, { icon: string; label: string; cls: string }> = {
    connecting: { icon: "sync", label: "Connecting…", cls: "msg-panel__status--connecting" },
    ok: { icon: "check_circle", label: "Connected", cls: "msg-panel__status--ok" },
    expired: { icon: "error", label: "Session expired", cls: "msg-panel__status--expired" },
};

function setStatus(state: StatusState) {
    const { icon, label, cls } = STATUS_META[state];
    panelStatus.className = `msg-panel__status ${cls}`;
    panelStatusIcon.textContent = icon;
    panelStatusLabel.textContent = label;
}

// ─── Panel content views ──────────────────────────────────────────────────────
function showPanelView(view: "connecting" | "error" | "messages") {
    viewConnecting.hidden = view !== "connecting";
    viewError.hidden = view !== "error";
    viewMessages.hidden = view !== "messages";
    btnMarkAllRead.disabled = view !== "messages";
}

function showError(headline: string, detail: string) {
    errorHeadline.textContent = headline;
    errorDetail.textContent = detail;
    showPanelView("error");
    setStatus("expired");
}

// ─── Unread badge ─────────────────────────────────────────────────────────────
async function refreshUnreadBadge() {
    if (!session) return;
    try {
        const { totalItems } = await session.unreadMessages({ page: 0, limit: 1 });
        const count = Number(totalItems);
        if (count > 0) {
            unreadBadge.textContent = count > 99 ? "99+" : String(count);
            unreadBadge.hidden = false;
        } else {
            unreadBadge.hidden = true;
        }
    } catch {
        unreadBadge.hidden = true;
    }
}

// ─── Backend: get refresh token ───────────────────────────────────────────────
async function getRefreshToken(apiKey: string, customerId: string): Promise<string> {
    const res = await fetch(`${DEV_API_BASE}/api/v1/authentication/refreshToken`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ apiKey, customerId }),
    });
    if (!res.ok) throw new Error(`Refresh token request failed: ${res.status} ${res.statusText}`);
    const { refreshToken } = await res.json() as { refreshToken: string };
    return refreshToken;
}

// ─── Credentials modal ────────────────────────────────────────────────────────
function openCredsModal(): void {
    inputTenantKey.value = "";
    inputPlayerId.value = "";
    inputEnv.value = (localStorage.getItem(STORAGE_KEY_ENV) as LighthouseEnv | null) ?? DEFAULT_ENV;
    credsBackdrop.hidden = false;
    inputTenantKey.focus();
}

function closeCredsModal(): void {
    credsBackdrop.hidden = true;
}

function submitCreds(): void {
    const apiKey = inputTenantKey.value.trim();
    const customerId = inputPlayerId.value.trim();
    const env = inputEnv.value as LighthouseEnv;
    if (!apiKey || !customerId) {
        showSnackbar("Tenant key and player ID are required", "error");
        return;
    }
    localStorage.setItem(STORAGE_KEY_TENANT, apiKey);
    localStorage.setItem(STORAGE_KEY_PLAYER, customerId);
    localStorage.setItem(STORAGE_KEY_ENV, env);
    closeCredsModal();
    void initSession(apiKey, customerId, env);
}

btnCredsSubmit.addEventListener("click", submitCreds);
[inputTenantKey, inputPlayerId].forEach(input =>
    input.addEventListener("keydown", e => {
        if (e.key === "Enter") submitCreds();
    }),
);

// ─── Session init ─────────────────────────────────────────────────────────────
async function initSession(apiKey: string, customerId: string, env: LighthouseEnv = DEFAULT_ENV): Promise<void> {
    if (isConnecting) return;

    isConnecting = true;
    session = null;
    currentApiKey = apiKey;
    currentCustomerId = customerId;
    currentEnv = env;

    showPanelView("connecting");
    setStatus("connecting");
    unreadBadge.hidden = true;

    try {
        const refreshToken = await getRefreshToken(apiKey, customerId);
        const newSession = await LighthouseSession.initialize(refreshToken, { env, language: "de", country: "ZZ" });

        newSession.addEventListener("refreshTokenExpired", () => {
            if (session !== newSession) return;
            setStatus("expired");
            showError("Session expired", "Reconnecting automatically…");
            showSnackbar("Session expired — reconnecting…", "error");
            isConnecting = false;
            setTimeout(() => void initSession(currentApiKey, currentCustomerId, currentEnv), 3000);
        });

        session = newSession;
        setStatus("ok");
        showPanelView("messages");
        await Promise.all([loadMessages(activeFilter), refreshUnreadBadge()]);
    } catch (err) {
        showError("Could not connect", String(err));
        showSnackbar(String(err), "error");
    } finally {
        isConnecting = false;
    }
}

// ─── Load messages ────────────────────────────────────────────────────────────
async function loadMessages(filter: Filter, page = 0): Promise<void> {
    if (!session) return;

    const isFirstPage = page === 0;
    if (isFirstPage) {
        activeFilter = filter;
        currentPage = 0;
        hasNextPage = false;
        messagesList.innerHTML = "";
        messagesEmpty.hidden = true;
    }

    const seq = ++loadSeq;
    const current = session;

    messagesLoading.hidden = false;
    removeLoadMoreButton();

    const opts = { page, limit: 10 };
    const fetchers: Record<Filter, () => ReturnType<LighthouseSession["messages"]>> = {
        all: () => current.messages(opts),
        unread: () => current.unreadMessages(opts),
        active: () => current.activeMessages(opts),
        archived: () => current.archivedMessages(opts),
    };

    try {
        const result = await fetchers[filter]();
        if (seq !== loadSeq) return;

        messagesLoading.hidden = true;
        currentPage = result.currentPage;
        hasNextPage = result.hasNext;

        if (result.data.length === 0 && isFirstPage) {
            messagesEmpty.hidden = false;
            return;
        }

        result.data.forEach(msg => messagesList.appendChild(createMessageCard(msg)));

        if (result.hasNext) {
            appendLoadMoreButton(filter, result.currentPage + 1, result.totalPages);
        }
    } catch (err) {
        if (seq !== loadSeq) return;
        messagesLoading.hidden = true;
        if (isFirstPage) messagesEmpty.hidden = false;
        showSnackbar(`Could not load messages: ${String(err)}`, "error");
    }
}

function removeLoadMoreButton() {
    document.getElementById("btn-load-more")?.remove();
}

function appendLoadMoreButton(filter: Filter, nextPage: number, totalPages: number) {
    const wrapper = document.createElement("li");
    wrapper.id = "btn-load-more";
    wrapper.className = "load-more-row";

    const btn = document.createElement("button");
    btn.className = "btn-load-more";
    btn.textContent = `Load more  (page ${nextPage + 1} / ${totalPages})`;
    btn.addEventListener("click", async () => {
        btn.disabled = true;
        btn.textContent = "Loading…";
        await loadMessages(filter, nextPage);
    });

    wrapper.appendChild(btn);
    messagesList.appendChild(wrapper);
}

// ─── Message card ─────────────────────────────────────────────────────────────
function createMessageCard(msg: CustomerMessage): HTMLLIElement {
    const isUnread = !msg.readDate;
    const isArchived = !!msg.archivedDate;
    const { message: m, callToActions, customerMessageId } = msg;

    const li = document.createElement("li");
    li.className = `message-card${isUnread ? " message-card--unread" : ""}`;

    // Header chips
    const header = document.createElement("div");
    header.className = "message-card__header";

    const categoryChip = document.createElement("span");
    categoryChip.className = `chip chip--${m.category}`;
    categoryChip.textContent = m.category.replace(/_/g, " ");
    header.appendChild(categoryChip);

    if (isUnread) {
        const chip = document.createElement("span");
        chip.className = "chip chip--unread";
        chip.textContent = "New";
        header.appendChild(chip);
    }

    // Title + body
    const title = document.createElement("p");
    title.className = "message-card__title";
    title.textContent = m.title;

    const body = document.createElement("p");
    body.className = "message-card__body";
    body.innerHTML = m.body;

    li.append(header, title, body);

    // Footer actions
    const footerActions: HTMLElement[] = [];

    if (isUnread) {
        const btn = document.createElement("button");
        btn.className = "btn-tonal";
        btn.innerHTML = `<span class="material-symbols-rounded">done</span>Mark read`;
        btn.addEventListener("click", async () => {
            if (!session) return;
            btn.disabled = true;
            try {
                await session.markMessageRead(customerMessageId);
                showSnackbar("Marked as read", "success");
                await Promise.all([loadMessages(activeFilter), refreshUnreadBadge()]);
            } catch (err) {
                btn.disabled = false;
                showSnackbar(String(err), "error");
            }
        });
        footerActions.push(btn);
    }

    if (!isArchived) {
        const btn = document.createElement("button");
        btn.className = "btn-tonal";
        btn.innerHTML = `<span class="material-symbols-rounded">archive</span>Archive`;
        btn.addEventListener("click", async () => {
            if (!session) return;
            btn.disabled = true;
            try {
                await session.archiveMessage(customerMessageId);
                showSnackbar("Archived", "success");
                await loadMessages(activeFilter);
            } catch (err) {
                btn.disabled = false;
                showSnackbar(String(err), "error");
            }
        });
        footerActions.push(btn);
    } else {
        const btn = document.createElement("button");
        btn.className = "btn-tonal";
        btn.innerHTML = `<span class="material-symbols-rounded">unarchive</span>Restore`;
        btn.addEventListener("click", async () => {
            if (!session) return;
            btn.disabled = true;
            try {
                await session.restoreMessage(customerMessageId);
                showSnackbar("Restored", "success");
                await loadMessages(activeFilter);
            } catch (err) {
                btn.disabled = false;
                showSnackbar(String(err), "error");
            }
        });
        footerActions.push(btn);
    }

    callToActions.forEach(cta => {
        const btn = document.createElement("button");
        btn.className = "btn-cta";
        btn.textContent = cta.translation;
        btn.addEventListener("click", async () => {
            if (!session) return;
            btn.disabled = true;
            try {
                await session.engageCallToAction(customerMessageId, cta.identifier);
                showSnackbar(`"${cta.translation}" recorded`, "success");
            } catch (err) {
                showSnackbar(String(err), "error");
            } finally {
                btn.disabled = false;
            }
        });
        footerActions.push(btn);
    });

    if (footerActions.length > 0) {
        const footer = document.createElement("div");
        footer.className = "message-card__footer";
        footerActions.forEach(el => footer.appendChild(el));
        li.appendChild(footer);
    }

    return li;
}

// ─── Mark all read ────────────────────────────────────────────────────────────
btnMarkAllRead.addEventListener("click", async () => {
    if (!session) return;
    btnMarkAllRead.disabled = true;
    try {
        const { updatedMessages } = await session.markAllMessagesRead();
        if (updatedMessages > 0) {
            showSnackbar(`${updatedMessages} message${updatedMessages > 1 ? "s" : ""} marked as read`, "success");
            await Promise.all([loadMessages(activeFilter), refreshUnreadBadge()]);
        } else {
            showSnackbar("No unread messages");
        }
    } catch (err) {
        showSnackbar(String(err), "error");
    } finally {
        btnMarkAllRead.disabled = false;
    }
});

// ─── Tab bar ──────────────────────────────────────────────────────────────────
document.querySelectorAll<HTMLButtonElement>(".tab").forEach(tab => {
    tab.addEventListener("click", async () => {
        const filter = tab.dataset.filter as Filter;
        if (filter === activeFilter) return;
        document.querySelectorAll(".tab").forEach(t => t.classList.remove("tab--active"));
        tab.classList.add("tab--active");
        await loadMessages(filter);
    });
});

// ─── Retry ────────────────────────────────────────────────────────────────────
btnRetry.addEventListener("click", () => void initSession(currentApiKey, currentCustomerId, currentEnv));

// ─── Boot ─────────────────────────────────────────────────────────────────────
initSlot();
openCredsModal();
