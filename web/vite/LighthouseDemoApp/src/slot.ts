const SYMBOLS = ["🍒", "🍋", "🍊", "💎", "⭐", "7"];
const JACKPOT_SYMBOL = "7";

const backdrop = document.getElementById("slot-backdrop")!;
const closeBtn = document.getElementById("slot-close")!;
const spinBtn = document.getElementById("slot-spin") as HTMLButtonElement;
const resultEl = document.getElementById("slot-result")!;
const reels = [
    document.getElementById("reel-0")!,
    document.getElementById("reel-1")!,
    document.getElementById("reel-2")!,
];

function openSlot() {
    backdrop.hidden = false;
    resultEl.hidden = true;
    resultEl.className = "slot-result";
}

function closeSlot() {
    backdrop.hidden = true;
}

function randomSymbol() {
    return SYMBOLS[Math.floor(Math.random() * SYMBOLS.length)]!;
}

function spinReel(reel: HTMLElement, durationMs: number): Promise<string> {
    return new Promise(resolve => {
        reel.classList.add("slot-reel--spinning");

        const tick = setInterval(() => {
            reel.textContent = randomSymbol();
        }, 80);

        setTimeout(() => {
            clearInterval(tick);
            reel.classList.remove("slot-reel--spinning");
            const final = randomSymbol();
            reel.textContent = final;
            resolve(final);
        }, durationMs);
    });
}

async function spin() {
    spinBtn.disabled = true;
    resultEl.hidden = true;

    const [s0, s1, s2] = await Promise.all([
        spinReel(reels[0]!, 800),
        spinReel(reels[1]!, 1200),
        spinReel(reels[2]!, 1600),
    ]);

    const isJackpot = s0 === JACKPOT_SYMBOL && s1 === JACKPOT_SYMBOL && s2 === JACKPOT_SYMBOL;
    const isWin = s0 === s1 && s1 === s2;

    resultEl.hidden = false;

    if (isJackpot) {
        resultEl.textContent = "🏆 JACKPOT! The Hour with Auer is yours!";
        resultEl.className = "slot-result slot-result--jackpot";
    } else if (isWin) {
        resultEl.textContent = "🎉 You won a Fleischkassemmel!";
        resultEl.className = "slot-result slot-result--win";
    } else {
        resultEl.textContent = "Not this time. Try again?";
        resultEl.className = "slot-result slot-result--lose";
    }

    spinBtn.disabled = false;
}

export function initSlot() {
    document.querySelector<HTMLAnchorElement>(".site-nav__link[href='#casino']")
        ?.addEventListener("click", e => { e.preventDefault(); openSlot(); });

    closeBtn.addEventListener("click", closeSlot);
    backdrop.addEventListener("click", e => { if (e.target === backdrop) closeSlot(); });
    document.addEventListener("keydown", e => { if (e.key === "Escape" && !backdrop.hidden) closeSlot(); });
    spinBtn.addEventListener("click", () => void spin());
}
